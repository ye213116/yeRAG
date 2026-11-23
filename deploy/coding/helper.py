"""Immutable container helper. Repository code only runs in the COMMAND/TEST subprocess.

The Java Docker adapter supplies requests; this program is never a host executor.
"""
import base64
import difflib
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import selectors
import shutil
import signal
import stat
import subprocess
import sys
import time
import urllib.parse
import xml.etree.ElementTree as ET

ROOT = Path('/workspace')
BASE = Path('/base/repo')
RESERVED = {'.git', '.yerag-cache'}
MAX_FILE = 1024 * 1024


def relative(value, allow_root=False):
    if not isinstance(value, str) or len(value) > 1024 or '\\' in value or any(ord(c) < 32 for c in value):
        raise ValueError('invalid_path')
    path = PurePosixPath(value)
    if path.is_absolute() or '..' in path.parts or any(p in RESERVED for p in path.parts):
        raise ValueError('path_not_allowed')
    if not allow_root and str(path) == '.':
        raise ValueError('file_path_required')
    current = ROOT
    for part in path.parts:
        current = current / part
        if current.is_symlink():
            raise ValueError('symlink_not_allowed')
    return current


def bounded_file(path, limit=MAX_FILE):
    if path.is_symlink() or not path.is_file():
        raise ValueError('regular_file_required')
    with path.open('rb') as stream:
        data = stream.read(limit + 1)
    if len(data) > limit:
        raise ValueError('file_too_large')
    return data


def git(*args, input=None):
    result = subprocess.run(['git', '-c', 'core.hooksPath=/dev/null', '-c', 'core.fsmonitor=false',
                             '-c', 'core.attributesFile=/dev/null', '-C', str(BASE), *args],
                            input=input, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=30)
    if result.returncode:
        raise ValueError('git_operation_failed')
    return result.stdout


def files():
    result = []
    for directory, dirs, names in os.walk(ROOT, followlinks=False):
        dirs[:] = sorted(d for d in dirs if d != '.yerag-cache')
        for name in dirs + names:
            path = Path(directory) / name
            if path.is_symlink():
                raise ValueError('symlink_not_allowed')
            if name == '.git':
                raise ValueError('git_metadata_is_not_a_workspace_file')
        for name in sorted(names):
            path = Path(directory) / name
            if not stat.S_ISREG(path.lstat().st_mode):
                raise ValueError('special_file_not_allowed')
            result.append(path.relative_to(ROOT).as_posix())
            if len(result) > 20000:
                raise ValueError('too_many_files')
    return result


def file_digest(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(65536), b''):
            digest.update(chunk)
    return digest.digest()


def snapshot():
    tracked = set(git('ls-files', '-z').decode('utf-8').split('\0')) - {''}
    current = files()
    untracked = [name for name in current if name not in tracked]
    ignored = set()
    if untracked:
        result = subprocess.run(['git', '-c', 'core.excludesFile=/dev/null', '-C', str(BASE),
                                 'check-ignore', '--no-index', '-z', '--stdin'],
                                input=('\0'.join(untracked) + '\0').encode(), stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, timeout=10)
        if result.returncode not in (0, 1):
            raise ValueError('ignore_rules_failed')
        ignored = set(result.stdout.decode().split('\0'))
    changes, diff = [], []
    for name in sorted(set(current) | tracked):
        if name not in tracked and (name in ignored or 'target' in PurePosixPath(name).parts or name.startswith('.yerag-cache/')):
            continue
        path = relative(name)
        oldpath = BASE / name
        oldmode = '100755' if oldpath.exists() and oldpath.stat().st_mode & 0o111 else '100644'
        mode = '100755' if path.exists() and path.stat().st_mode & 0o111 else '100644'
        if oldpath.is_file() and path.is_file() and oldmode == mode and oldpath.stat().st_size == path.stat().st_size and file_digest(oldpath) == file_digest(path):
            continue
        old = bounded_file(oldpath) if name in tracked else None
        new = bounded_file(path) if path.exists() else None
        if (old and b'\0' in old) or (new and b'\0' in new):
            raise ValueError('binary_changes_not_supported')
        oldtext = old.decode('utf-8') if old is not None else ''
        text = new.decode('utf-8') if new is not None else None
        changes.append({'path': name, 'content': text, 'mode': mode})
        diff.append('diff --git a/' + name + ' b/' + name + '\n')
        if oldmode != mode:
            diff.append('old mode ' + oldmode + '\nnew mode ' + mode + '\n')
        for line in difflib.unified_diff(oldtext.splitlines(keepends=True), (text or '').splitlines(keepends=True),
                                         fromfile='a/' + name if old is not None else '/dev/null',
                                         tofile='b/' + name if new is not None else '/dev/null'):
            diff.append(line if line.endswith('\n') else line + '\n\\ No newline at end of file\n')
    canonical = json.dumps(changes, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()
    return {'ok': True, 'changes': changes, 'diff': ''.join(diff), 'changeSetHash': hashlib.sha256(canonical).hexdigest()}


def command(argv, cwd, seconds, max_log):
    if not isinstance(argv, list) or not argv or len(argv) > 64 or any(not isinstance(x, str) or '\0' in x or len(x) > 8192 for x in argv):
        raise ValueError('invalid_command')
    env = {'PATH': '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/share/maven/bin',
           'HOME': '/tmp', 'LANG': 'C.UTF-8', 'MAVEN_OPTS': '-Xmx2048m'}
    proc = subprocess.Popen(argv, cwd=cwd, env=env, stdin=subprocess.DEVNULL,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True)
    selector = selectors.DefaultSelector()
    selector.register(proc.stdout, selectors.EVENT_READ)
    start = time.monotonic()
    output = bytearray()
    timed_out = False
    total = 0
    try:
        while selector.get_map():
            if time.monotonic() - start > seconds:
                timed_out = True
                os.killpg(proc.pid, signal.SIGKILL)
            for key, _ in selector.select(0.1):
                part = os.read(key.fileobj.fileno(), 8192)
                if not part:
                    selector.unregister(key.fileobj)
                else:
                    total += len(part)
                    output.extend(part[:max(0, max_log - len(output))])
        code = proc.wait(timeout=2)
    finally:
        selector.close()
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        proc.stdout.close()
    return {'ok': code == 0 and not timed_out, 'exitCode': code, 'timedOut': timed_out,
            'text': output.decode('utf-8', errors='replace'), 'truncated': total > max_log,
            'elapsedMs': int((time.monotonic() - start) * 1000), 'argv': argv}


def tests(request):
    before = snapshot()['changeSetHash']
    cache = ROOT / '.yerag-cache'
    cache.mkdir(exist_ok=True)
    # Reports from an earlier build cannot verify this execution.
    for report in ROOT.glob('**/target/surefire-reports/TEST-*.xml'):
        if not report.is_symlink():
            report.unlink()
    proxy = urllib.parse.urlsplit(os.environ.get('HTTPS_PROXY', 'http://egress:3128'))
    if proxy.scheme != 'http' or not proxy.hostname or proxy.username or proxy.password:
        raise ValueError('invalid_proxy')
    settings = Path('/tmp/yerag-settings.xml')
    settings.write_text('<settings><proxies><proxy><id>controlled</id><active>true</active><protocol>http</protocol><host>'
                        + proxy.hostname + '</host><port>' + str(proxy.port or 3128)
                        + '</port></proxy></proxies><mirrors><mirror><id>approved</id><mirrorOf>*</mirrorOf>'
                        + '<url>https://repo.maven.apache.org/maven2</url></mirror></mirrors></settings>')
    result = command(['mvn', '-B', '-ntp', '-s', str(settings), '-Dmaven.repo.local=' + str(cache),
                      '-DskipTests=false', '-Dmaven.test.skip=false', 'test'], ROOT,
                     request['timeoutSeconds'], request['maxLogBytes'])
    stats = {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
    for report in ROOT.glob('**/target/surefire-reports/TEST-*.xml'):
        if report.is_symlink():
            raise ValueError('symlink_not_allowed')
        suite = ET.fromstring(bounded_file(report))
        for key in stats:
            stats[key] += int(suite.get(key, '0'))
    after = snapshot()['changeSetHash']
    passed = result['ok'] and stats['tests'] > stats['skipped'] and stats['failures'] == 0 and stats['errors'] == 0 and before == after
    result['verification'] = {'id': hashlib.sha256((after + str(time.time_ns())).encode()).hexdigest(),
                              'changeSetHash': after, 'status': 'PASSED' if passed else 'FAILED',
                              'codeChangedDuringTest': before != after, **stats}
    return result


def setup(request):
    args = request['arguments']
    repo, sha = args['repository'], args['sha']
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repo) or '..' in repo or not re.fullmatch('[a-f0-9]{40}', sha):
        raise ValueError('invalid_repository')
    # The trusted setup container receives a read-only credential. It never executes repository code.
    BASE.mkdir(parents=True, exist_ok=True)
    subprocess.run(['git', 'init', '-q', str(BASE)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    token = os.environ.pop('YERAG_CLONE_TOKEN', '')
    env = os.environ.copy()
    env['GIT_TERMINAL_PROMPT'] = '0'
    if token:
        env.update({'GIT_CONFIG_COUNT': '1', 'GIT_CONFIG_KEY_0': 'http.https://github.com/.extraheader',
                    'GIT_CONFIG_VALUE_0': 'Authorization: Basic ' + base64.b64encode(('x-access-token:' + token).encode()).decode()})
    subprocess.run(['git', '-C', str(BASE), '-c', 'core.hooksPath=/dev/null', 'fetch', '-q', '--depth=1',
                    'https://github.com/' + repo + '.git', sha], env=env, check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=request['timeoutSeconds'])
    git('checkout', '-q', '--detach', sha)
    paths = git('ls-files', '-z').decode().split('\0')
    total = 0
    for name in filter(None, paths):
        relative(name)
        source = BASE / name
        if source.is_symlink() or not source.is_file():
            raise ValueError('symlinks_and_submodules_not_supported')
        total += source.stat().st_size
        if total > request['maxWorkspaceBytes'] // 2:
            raise ValueError('repository_too_large')
        destination = relative(name)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
    # tmpfs capacity is enforced by Docker's volume mount, not by trusting a scan.
    capacity = shutil.disk_usage(ROOT).total
    if capacity > request['maxWorkspaceBytes'] + 4096:
        raise ValueError('workspace_quota_not_enforced')
    return {'ok': True, 'baseSha': sha, 'fileCount': len(paths) - 1}


def apply_patch(args):
    patch = args.get('patch')
    hashes = args.get('expectedHashes')
    if not isinstance(patch, str) or len(patch.encode()) > 256 * 1024 or not isinstance(hashes, dict):
        raise ValueError('invalid_patch')
    headers = []
    for line in patch.splitlines():
        if line.startswith(('--- ', '+++ ')):
            name = line[4:].split('\t')[0]
            if name == '/dev/null':
                continue
            if not name.startswith(('a/', 'b/')):
                raise ValueError('invalid_patch_path')
            name = name[2:]
            path = relative(name)
            if name not in hashes:
                raise ValueError('missing_expected_file_hash')
            actual = hashlib.sha256(bounded_file(path)).hexdigest() if path.exists() else None
            if hashes[name] != actual:
                raise ValueError('file_version_conflict')
            headers.append(name)
        if line.startswith(('GIT binary patch', 'rename from ', 'rename to ', 'new file mode 120000', 'new file mode 160000', 'new mode 120000', 'new mode 160000')):
            raise ValueError('unsupported_patch')
    if not headers:
        raise ValueError('empty_patch')
    # --no-add is deliberately not used: newly created text files are part of the deliverable.
    for check in (True, False):
        argv = ['git', '-c', 'core.hooksPath=/dev/null', 'apply', '--whitespace=nowarn']
        if check:
            argv.append('--check')
        result = subprocess.run(argv + ['-'], cwd=ROOT, input=patch.encode(), stdout=subprocess.DEVNULL,
                                stderr=subprocess.DEVNULL, timeout=10)
        if result.returncode:
            raise ValueError('patch_conflict')
    return {'ok': True, 'files': sorted(set(headers)), 'changeSetHash': snapshot()['changeSetHash']}


def handle(request):
    op, args = request['operation'], request.get('arguments', {})
    if op == 'SETUP':
        return setup(request)
    if op == 'SNAPSHOT':
        return snapshot()
    if op == 'PATCH':
        return apply_patch(args)
    if op == 'TEST':
        return tests(request)
    if op == 'COMMAND':
        return command(args.get('argv'), relative(args.get('cwd', '.'), True), request['timeoutSeconds'], request['maxLogBytes'])
    if op == 'READ':
        path = relative(args.get('path'))
        data = bounded_file(path)
        if b'\0' in data:
            raise ValueError('binary_file_not_supported')
        lines = data.decode('utf-8').splitlines(keepends=True)
        start, count = args.get('startLine', 1), args.get('lineCount', 200)
        if type(start) is not int or type(count) is not int or not 1 <= start <= 1000000 or not 1 <= count <= 400:
            raise ValueError('invalid_line_range')
        column = args.get('column', 0)
        if type(column) is not int or not 0 <= column <= MAX_FILE:
            raise ValueError('invalid_column')
        selected = ''.join(lines[start - 1:start - 1 + count])[column:]
        content = selected[:32768]
        next_line = start + content.count('\n')
        next_column = (column if '\n' not in content else 0) + len(content.rsplit('\n', 1)[-1])
        position = sum(map(len, lines[:start - 1])) + column + len(content)
        return {'ok': True, 'path': args['path'], 'sha256': hashlib.sha256(data).hexdigest(), 'content': content,
                'startLine': start, 'nextLine': next_line, 'nextColumn': next_column, 'truncated': position < sum(map(len, lines))}
    if op == 'LIST':
        scope = relative(args.get('path', '.'), True).relative_to(ROOT).as_posix()
        offset, depth = args.get('offset', 0), args.get('depth', 4)
        if type(offset) is not int or not 0 <= offset <= 20000 or type(depth) is not int or not 1 <= depth <= 10:
            raise ValueError('invalid_pagination')
        matches = [name for name in files() if (scope == '.' or name.startswith(scope + '/'))
                   and len(PurePosixPath(name).relative_to(scope).parts) <= depth and 'target' not in PurePosixPath(name).parts]
        page = matches[offset:offset + 200]
        return {'ok': True, 'files': page, 'nextOffset': offset + 200 if len(matches) > offset + 200 else None}
    if op == 'SEARCH':
        query = args.get('query')
        if not isinstance(query, str) or not 1 <= len(query) <= 500:
            raise ValueError('invalid_query')
        path = relative(args.get('path', '.'), True)
        result = command(['rg', '--fixed-strings', '--line-number', '--max-count', '20', '--glob', '!target/**',
                          '--glob', '!.yerag-cache/**', '--', query, str(path)], ROOT, min(10, request['timeoutSeconds']), 32768)
        result['ok'] = result['exitCode'] in (0, 1)
        return result
    raise ValueError('unknown_operation')


if __name__ == '__main__':
    try:
        request = json.loads(base64.b64decode(os.environ['YERAG_REQUEST']))
        result = handle(request)
        encoded = json.dumps(result, ensure_ascii=False).encode()
        if len(encoded) > request['maxResultBytes']:
            result = {'ok': False, 'error': 'result_too_large'}
    except Exception as failure:
        safe = str(failure) if isinstance(failure, ValueError) and re.fullmatch('[a-z_]+', str(failure)) else 'sandbox_operation_failed'
        result = {'ok': False, 'error': safe}
    sys.stdout.write(json.dumps(result, ensure_ascii=False))
