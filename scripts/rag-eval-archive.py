#!/usr/bin/env python3
"""Copy explicit evaluation evidence outside target and restore reports without Maven/services."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''): h.update(block)
    return h.hexdigest()


def copy_file(source, destination):
    if source.is_symlink() or not source.is_file(): raise ValueError('Archive only regular files: ' + str(source))
    destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    shutil.copyfile(source, destination); destination.chmod(0o600)


def copy_tree(source, destination, predicate=lambda p: True):
    for path in sorted(source.rglob('*')):
        if not predicate(path): continue
        if path.is_symlink(): raise ValueError('Symlinks are not archive inputs')
        if path.is_file(): copy_file(path, destination / path.relative_to(source))


def inventory(root):
    return {str(p.relative_to(root)): sha(p) for p in sorted(root.rglob('*'))
            if p.is_file() and p.name not in {'archive.json', 'archive.sha256'}}


def verify(root):
    root = root.resolve(); manifest = json.loads((root / 'archive.json').read_text())
    if sha(root / 'archive.json') != (root / 'archive.sha256').read_text().strip(): raise ValueError('Changed archive manifest')
    for name, checksum in manifest['files'].items():
        path = root / name
        if not path.resolve().is_relative_to(root) or path.is_symlink() or not path.is_file() or sha(path) != checksum:
            raise ValueError('Changed/missing archive member: ' + name)
    return manifest


def replay(root, output):
    root = root.resolve(); manifest = verify(root)
    output.mkdir(parents=True, exist_ok=False, mode=0o700)
    classpath = os.pathsep.join(str(root / p) for p in manifest['classpath'])
    results = []
    for name in manifest['runs']:
        run = root / name; destination = output / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        with (output / (name.replace('/', '_') + '.log')).open('x') as log:
            subprocess.run(['java', '-Xmx512m', '-cp', classpath,
                            'com.yizhaoqi.smartpai.rag.eval.EvaluationRunner', str(run / 'dataset'),
                            str(run / 'preflight.json'), str(run / 'capture.json'), str(destination)],
                           cwd=output, stdout=log, stderr=subprocess.STDOUT, check=True)
        for filename in ['results.jsonl', 'summary.json', 'ragas.jsonl', 'ragas-index.jsonl']:
            if sha(run / 'report' / filename) != sha(destination / filename): raise ValueError('Offline report changed: ' + name + '/' + filename)
        results.append({'run': name, 'fourReportFilesByteIdentical': True})
        print('RESTORED', name, flush=True)
        if (run / 'reviewed-report').is_dir():
            labels = run / 'reviews/labels.jsonl'
            if not labels.is_file(): raise ValueError('Reviewed report has no archived human review input')
            reviewed = output / (name + '-reviewed')
            with (output / (name.replace('/', '_') + '-reviewed.log')).open('x') as log:
                subprocess.run(['java', '-Xmx512m', '-cp', classpath,
                                'com.yizhaoqi.smartpai.rag.eval.EvaluationRunner', str(run / 'dataset'),
                                str(run / 'preflight.json'), str(run / 'capture.json'), str(reviewed), str(labels)],
                               cwd=output, stdout=log, stderr=subprocess.STDOUT, check=True)
            for filename in ['results.jsonl', 'summary.json', 'ragas.jsonl', 'ragas-index.jsonl']:
                if sha(run / 'reviewed-report' / filename) != sha(reviewed / filename): raise ValueError('Reviewed report changed')
            results.append({'run': name + ':reviewed', 'fourReportFilesByteIdentical': True, 'humanReviewInputSha256': sha(labels)})
            print('RESTORED', name + ':reviewed', flush=True)
    result = {'status': 'VERIFIED', 'archiveManifestSha256': sha(root / 'archive.json'), 'runs': results,
              'servicesContacted': False, 'mavenRequired': False, 'notice': 'Java is required; dependencies/classes come from this archive.'}
    (output / 'recovery.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


def create(args):
    repo = Path.cwd().resolve(); root = args.output.resolve()
    if root.is_relative_to(repo / 'target'): raise ValueError('Persistent archive must be outside target')
    root.mkdir(parents=True, exist_ok=False, mode=0o700)
    for group in args.run_group:
        # Evidence directories only. Runtime properties, auth files and service logs
        # are intentionally not swept from target/public-eval-runtime.
        if group.resolve().is_relative_to(repo / 'target/public-eval-runtime'): raise ValueError('Private runtime is not an evidence group')
        for path in sorted(group.rglob('*')):
            if path.is_symlink(): raise ValueError('Symlinks are not archive inputs')
            if path.is_file() and path.suffix in {'.json', '.jsonl', '.md', '.txt', '.sha256'}:
                copy_file(path, root / 'runs' / group.name / path.relative_to(group))
    for group in args.review_pack: copy_tree(group, root / 'reviews' / group.name)
    for path in args.include_file: copy_file(path, root / 'diagnostics' / path.name)
    def public_resource(path):
        return not (path.name.startswith('application') and path.suffix in {'.yml', '.yaml', '.properties'})
    for directory in ['src/main/java', 'src/test/java', 'src/main/resources', 'src/test/resources', 'evaluation']:
        copy_tree(repo / directory, root / 'source' / directory, public_resource if directory == 'src/main/resources' else lambda p: True)
    copy_tree(repo / 'scripts', root / 'source/scripts', lambda p: p.suffix in {'.py', '.sh'})
    for name in ['pom.xml', 'README.md']: copy_file(repo / name, root / 'source' / name)
    copy_tree(repo / 'target/classes', root / 'runtime/classes', lambda p: p.suffix == '.class')
    copy_tree(repo / 'src/main/resources', root / 'runtime/classes', public_resource)
    classpath = ['runtime/classes']; seen = {}
    for name in (repo / 'target/rag-eval-classpath.txt').read_text().strip().split(os.pathsep):
        jar = Path(name).resolve()
        if jar.suffix != '.jar': raise ValueError('Unexpected classpath member')
        checksum = sha(jar)
        if jar.name in seen and seen[jar.name] != checksum: raise ValueError('Conflicting dependency filename')
        if jar.name not in seen:
            copy_file(jar, root / 'runtime/lib' / jar.name); classpath.append('runtime/lib/' + jar.name); seen[jar.name] = checksum
    secrets = []
    for config in args.private_config:
        for line in config.read_text().splitlines():
            if '=' not in line or line.lstrip().startswith('#'): continue
            key, value = line.split('=', 1); value = value.strip().strip('\"\'')
            if any(x in key.lower() for x in ['key', 'password', 'token', 'secret']) and len(value) >= 12 and '${' not in value:
                secrets.append(value.encode())
    for path in root.rglob('*'):
        if path.is_file():
            data = path.read_bytes()
            if any(value in data for value in secrets): raise ValueError('Private credential detected; archive left unsealed: ' + str(path.relative_to(root)))
    manifest = {'version': 'yerag-evaluation-archive-v1', 'classpath': classpath,
                'runs': sorted(str(p.parent.relative_to(root)) for p in (root / 'runs').rglob('capture.json')),
                'files': inventory(root), 'baseHead': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
                'worktree': 'UNCOMMITTED_SOURCE_SNAPSHOT', 'privateConfigurationIncluded': False,
                'excludedPrivateResources': ['src/main/resources/application*.yml', 'src/main/resources/application*.yaml', 'src/main/resources/application*.properties'],
                'restore': 'python3 source/scripts/rag-eval-archive.py replay ARCHIVE NEW_OUTPUT'}
    (root / 'archive.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    (root / 'archive.sha256').write_text(sha(root / 'archive.json') + '\n')
    verify(root)
    print('SEALED', root, 'files', len(manifest['files']), 'runs', len(manifest['runs']), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__); sub = parser.add_subparsers(dest='action', required=True)
    p = sub.add_parser('create'); p.add_argument('output', type=Path)
    p.add_argument('--run-group', action='append', type=Path, required=True)
    p.add_argument('--review-pack', action='append', type=Path, default=[])
    p.add_argument('--include-file', action='append', type=Path, default=[])
    p.add_argument('--private-config', action='append', type=Path, default=[], help='Only scan for leaked values; configuration is never copied')
    p = sub.add_parser('verify'); p.add_argument('archive', type=Path)
    p = sub.add_parser('replay'); p.add_argument('archive', type=Path); p.add_argument('output', type=Path)
    args = parser.parse_args()
    if args.action == 'create': create(args)
    elif args.action == 'verify': print('VERIFIED', len(verify(args.archive)['files']), 'files')
    else: replay(args.archive, args.output.resolve())


if __name__ == '__main__': main()
