#!/usr/bin/env python3
"""Build and deploy yeRAG to an existing Docker middleware network; keep secrets out of images/logs."""
import argparse
import datetime
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def docker_cli():
    choices = [os.environ.get('DOCKER_BIN'), shutil.which('docker'),
               '/mnt/c/Users/zyh/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe']
    for candidate in filter(None, choices):
        try:
            if subprocess.run([candidate, 'info', '--format', '{{.ServerVersion}}'], stdout=subprocess.DEVNULL,
                              stderr=subprocess.DEVNULL, timeout=10).returncode == 0:
                return candidate
        except (OSError, subprocess.TimeoutExpired):
            pass
    raise RuntimeError('No reachable Docker engine; set DOCKER_BIN to a working Docker CLI')


def read_env(path):
    result = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        key, separator, value = line.removeprefix('export ').partition('=')
        if separator:
            result[key.strip()] = ' '.join(shlex.split(value, comments=True))
    return result


def path_for_cli(cli, path):
    if cli.endswith('.exe'):
        return subprocess.check_output(['wslpath', '-w', str(path.resolve())], text=True).strip()
    return str(path.resolve())


def redact(text, values):
    for key, value in values.items():
        if value and any(word in key.upper() for word in ('KEY', 'TOKEN', 'SECRET', 'PASSWORD')):
            text = text.replace(value, '[REDACTED]')
    return re.sub(r'eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', '[JWT]', text)


def run(command, *, values, capture=False, **kwargs):
    result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, **kwargs)
    if result.returncode:
        raise RuntimeError(redact((result.stdout + result.stderr)[-6000:], values))
    if not capture and result.stdout:
        print(redact(result.stdout[-1600:], values), flush=True)
    return result.stdout


def database_name(values):
    url = values.get('DOCKER_DATASOURCE_URL', values.get('SPRING_DATASOURCE_URL', ''))
    matched = re.fullmatch(r'jdbc:mysql://[^/]+/([A-Za-z0-9_]+)(?:\?.*)?', url)
    if not matched:
        raise RuntimeError('Set DOCKER_DATASOURCE_URL with the existing database and container hostname')
    return matched.group(1)


def sql(cli, container, database, statement, values):
    # The root password stays in the existing MySQL container environment, not command arguments.
    script = 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot --default-character-set=utf8mb4 -N "$1"'
    return run([cli, 'exec', '-i', container, 'sh', '-c', script, 'sh', database],
               values=values, capture=True, input=statement)


def migrate(cli, container, database, values, state):
    sql(cli, container, database, 'SELECT 1;', values)
    dump = subprocess.run([cli, 'exec', container, 'sh', '-c',
                           'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysqldump -uroot --single-transaction --routines --triggers "$1"',
                           'sh', database], capture_output=True)
    if dump.returncode:
        raise RuntimeError('MySQL backup failed; migration was not started')
    backup = state / ('database-before-memory-' + datetime.datetime.now().strftime('%Y%m%d-%H%M%S') + '.sql')
    descriptor = os.open(backup, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'wb') as output:
        output.write(dump.stdout)
    print('Database backup saved privately:', backup.relative_to(ROOT), flush=True)
    def exists(table, column=None):
        statement = "SELECT COUNT(*) FROM information_schema." + ('columns' if column else 'tables')
        statement += " WHERE table_schema=DATABASE() AND table_name='" + table + "'"
        if column:
            statement += " AND column_name='" + column + "'"
        return int(sql(cli, container, database, statement, values).strip()) > 0
    if not exists('users'):
        sql(cli, container, database, (ROOT / 'docs/databases/ddl.sql').read_text(), values)
        print('Initialized empty database with current DDL', flush=True)
        return
    if not exists('memory_document') or not exists('chat_session'):
        raise RuntimeError('Existing database requires the 20260927 durable memory migration first')
    nullable = sql(cli, container, database,
                   "SELECT is_nullable FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='chat_turn' AND column_name='content_expires_at'", values).strip()
    if nullable == 'NO':
        sql(cli, container, database, (ROOT / 'docs/databases/migrations/20261102_persistent_conversation_history.sql').read_text(), values)
        print('Applied persistent conversation migration', flush=True)
    has_version = exists('memory_document', 'version')
    if not has_version:
        if exists('memory_document_version') or exists('memory_document_section', 'section_version'):
            raise RuntimeError('Partial memory migration detected; restore/reconcile before starting services')
        sql(cli, container, database, (ROOT / 'docs/databases/migrations/20261102_on_demand_markdown_memory.sql').read_text(), values)
        print('Applied on-demand Markdown memory migration and initial snapshots', flush=True)
    elif not exists('memory_document_version') or not exists('memory_document_section', 'description') or not exists('memory_extraction_task', 'failure_stage'):
        raise RuntimeError('Incomplete on-demand memory schema; do not start application workers')


def wait_http(url, expected, seconds=180):
    until = time.monotonic() + seconds
    while time.monotonic() < until:
        try:
            with urllib.request.urlopen(url, timeout=3) as response:
                status = response.status
        except urllib.error.HTTPError as error:
            status = error.code
        except OSError:
            status = 0
        if status == expected:
            return
        time.sleep(1)
    raise RuntimeError('Service readiness timed out: ' + url)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env', required=True, type=Path)
    parser.add_argument('--mysql-container', default='mysql')
    parser.add_argument('--network', default='pai_smart_default')
    parser.add_argument('--skip-build', action='store_true', help='Use artifacts and images already built and verified')
    args = parser.parse_args()
    source = args.env.expanduser().resolve(strict=True)
    values = read_env(source)
    required = ['SPRING_DATASOURCE_USERNAME', 'SPRING_DATASOURCE_PASSWORD', 'JWT_SECRET_KEY', 'ADMIN_BOOTSTRAP_PASSWORD',
                'DEEPSEEK_API_URL', 'DEEPSEEK_API_KEY', 'EMBEDDING_API_URL', 'EMBEDDING_API_KEY', 'EMBEDDING_API_MODEL',
                'MINIO_ACCESS_KEY', 'MINIO_SECRET_KEY', 'MINIO_PUBLIC_URL']
    missing = [key for key in required if not values.get(key)]
    if missing:
        raise RuntimeError('Missing private configuration keys: ' + ', '.join(missing))
    cli = docker_cli()
    run([cli, 'network', 'inspect', args.network, '--format', '{{.Name}}'], values=values, capture=True)
    database = database_name(values)
    state = ROOT / '.codex/deployment'
    state.mkdir(parents=True, exist_ok=True)
    state.chmod(0o700)
    runtime = state / 'runtime.env'
    runtime.write_text(source.read_text() + '\nYERAG_ENV_FILE=' + shlex.quote(path_for_cli(cli, runtime))
                       + '\nYERAG_DOCKER_NETWORK=' + args.network + '\n')
    runtime.chmod(0o600)
    compose = [cli, 'compose', '--project-directory', path_for_cli(cli, ROOT), '--env-file', path_for_cli(cli, runtime),
               '-f', path_for_cli(cli, ROOT / 'compose.yaml')]
    run(compose + ['config', '--quiet'], values=values, capture=True)
    if not args.skip_build:
        run(['mvn', '-q', 'package'], values=values)
        env = dict(os.environ, VITE_SERVICE_BASE_URL='/api/v1', VITE_OTHER_SERVICE_BASE_URL='{"api":"/api/v1","ws":""}')
        run(['pnpm', '--dir', 'frontend', 'build'], values=values, env=env)
        run(compose + ['build'], values=values)
    # Stop only this Compose project's backend; operators must stop any older non-Compose worker separately.
    run(compose + ['stop', 'backend'], values=values)
    migrate(cli, args.mysql_container, database, values, state)
    run(compose + ['up', '-d', '--no-build'], values=values)
    backend = int(values.get('YERAG_BACKEND_PORT', 8081))
    frontend = int(values.get('YERAG_FRONTEND_PORT', 9527))
    wait_http(f'http://127.0.0.1:{backend}/api/v1/users/me', 401)
    wait_http(f'http://127.0.0.1:{frontend}/', 200)
    wait_http(f'http://127.0.0.1:{frontend}/chat', 200)
    wait_http(f'http://127.0.0.1:{frontend}/api/v1/users/me', 401)
    print(f'Docker deployment ready: http://localhost:{frontend}; backend http://localhost:{backend}', flush=True)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # No command strings, environments or raw configuration objects are printed.
        print('Deployment stopped:', str(error), file=sys.stderr)
        sys.exit(1)
