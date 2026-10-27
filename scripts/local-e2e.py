#!/usr/bin/env python3
"""Exercise the local pai_smart stack and remove only this run's fixture accounts."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def sql(query):
    result = subprocess.run(
        ['docker', 'exec', '-i', 'mysql', 'sh', '-c',
         'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N paismart'],
        input=query, text=True, capture_output=True, check=True)
    return result.stdout.strip()


def cleanup(folder):
    path = folder / 'fixtures.json'
    if not path.exists():
        return
    fixtures = json.loads(path.read_text())
    names = [u['username'] for u in fixtures['users']]
    if not names:
        path.unlink()
        return
    assert all(re.fullmatch(r'e2e[0-9a-f]{10}', name) for name in names)
    names_sql = ','.join("'" + name + "'" for name in names)
    ids = [str(int(value)) for value in sql(f'SELECT id FROM users WHERE username IN ({names_sql})').splitlines()]
    sessions = []
    if ids:
        selected = ','.join(ids)
        sessions = sql(f'SELECT id FROM chat_session WHERE user_id IN ({selected})').splitlines()
        # Document deletion is performed through the authenticated API before this cleanup.
        remaining = sql(f'SELECT COUNT(*) FROM file_upload WHERE user_id IN ({selected})')
        if int(remaining):
            raise RuntimeError('Fixture documents still exist; retain fixtures.json for recovery')
        sql(f'''START TRANSACTION;
DELETE t FROM memory_extraction_task t JOIN chat_turn c ON c.id=t.turn_id WHERE c.user_id IN ({selected});
DELETE s FROM memory_document_section s JOIN memory_document d ON d.id=s.document_id WHERE d.user_id IN ({selected});
DELETE FROM memory_document WHERE user_id IN ({selected});
DELETE FROM chat_turn WHERE user_id IN ({selected});
DELETE FROM chat_session WHERE user_id IN ({selected});
DELETE FROM organization_tags WHERE created_by IN ({selected});
DELETE FROM users WHERE id IN ({selected});
COMMIT;''')
    container = json.loads(subprocess.check_output(['docker', 'inspect', 'redis']))[0]
    args = container['Args']
    password = args[args.index('--requirepass') + 1] if '--requirepass' in args else ''
    keys = [prefix + name for name in names for prefix in ['user:org_tags:', 'user:primary_org:', 'user:effective_org_tags:']]
    keys += [f'memory:user:{uid}:current' for uid in ids]
    keys += [f'memory:session:{sid}:context' for sid in sessions]
    result = subprocess.run(['docker', 'exec', '-i', 'redis', 'sh', '-c',
                             'IFS= read -r REDISCLI_AUTH; export REDISCLI_AUTH; exec redis-cli "$@"',
                             'sh', 'DEL', *keys], input=password + '\n', text=True, capture_output=True, check=True)
    if result.stdout.startswith(('NOAUTH', 'WRONGPASS', 'ERR')):
        raise RuntimeError('Fixture cache cleanup failed')
    path.unlink()
    print(f'Cleaned {len(names)} fixture accounts and their sessions/cache; no credentials retained')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cleanup', type=Path, help='Retry cleanup of a failed run without creating new fixtures')
    args = parser.parse_args()
    if args.cleanup:
        cleanup(args.cleanup)
        return
    folder = Path(tempfile.mkdtemp(prefix='yerag-live-e2e-'))
    browser = os.environ.get('BROWSER_EXECUTABLE_PATH')
    if not browser:
        browsers = sorted(Path.home().glob('.cache/ms-playwright/chromium-*/chrome-linux64/chrome'))
        if not browsers:
            raise RuntimeError('Set BROWSER_EXECUTABLE_PATH to an installed Chromium')
        browser = str(browsers[-1])
    print(f'Run output: {folder}', flush=True)
    try:
        result = subprocess.run(['node', 'frontend/tests/live-e2e.mjs'], cwd=ROOT,
                                env={**os.environ, 'YERAG_E2E_OUTPUT': str(folder), 'BROWSER_EXECUTABLE_PATH': browser})
    finally:
        cleanup(folder)
    raise SystemExit(result.returncode)


if __name__ == '__main__':
    main()
