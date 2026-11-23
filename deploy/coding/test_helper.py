"""Contract tests of the immutable helper with fixed local fixtures, not a production executor."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('coding_helper', Path(__file__).with_name('helper.py'))
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)


class HelperTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        helper.ROOT = self.root / 'work'
        helper.BASE = self.root / 'base'
        helper.ROOT.mkdir()
        helper.BASE.mkdir()
        subprocess.run(['git', 'init', '-q', str(helper.BASE)], check=True)
        (helper.BASE / 'Example.java').write_text('class Example {\n  int value = 1;\n}\n')
        (helper.BASE / '.gitignore').write_text('*.log\n')
        subprocess.run(['git', '-C', str(helper.BASE), 'add', '.'], check=True)
        subprocess.run(['git', '-C', str(helper.BASE), '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                        '-c', 'commit.gpgsign=false', 'commit', '-qm', 'baseline'], check=True)
        for path in helper.BASE.iterdir():
            if path.is_file():
                shutil.copy2(path, helper.ROOT / path.name)

    def tearDown(self):
        self.temp.cleanup()

    def request(self, op, args=None):
        return helper.handle({'operation': op, 'arguments': args or {}, 'timeoutSeconds': 1,
                              'maxLogBytes': 1024, 'maxResultBytes': 2 * 1024 * 1024})

    def test_path_traversal_symlinks_and_special_files_are_rejected(self):
        for path in ['../outside', '/etc/passwd', '.git/config', 'nested/../../escape', 'x\\y']:
            with self.assertRaises(ValueError):
                helper.relative(path)
        (helper.ROOT / 'outside').symlink_to(self.root)
        with self.assertRaises(ValueError):
            helper.relative('outside/secret')
        (helper.ROOT / 'outside').unlink()
        os.mkfifo(helper.ROOT / 'pipe')
        with self.assertRaises(ValueError):
            helper.snapshot()

    def test_patch_is_version_checked_and_failed_patch_leaves_every_file_unchanged(self):
        initial = (helper.ROOT / 'Example.java').read_bytes()
        patch = '--- a/Example.java\n+++ b/Example.java\n@@ -1,3 +1,3 @@\n class Example {\n-  int value = 1;\n+  int value = 2;\n }\n'
        with self.assertRaises(ValueError):
            self.request('PATCH', {'patch': patch, 'expectedHashes': {'Example.java': 'stale'}})
        self.assertEqual(initial, (helper.ROOT / 'Example.java').read_bytes())
        first = self.request('PATCH', {'patch': patch, 'expectedHashes': {'Example.java': hashlib.sha256(initial).hexdigest()}})
        self.assertTrue(first['ok'])
        self.assertIn('value = 2', self.request('SNAPSHOT')['diff'])
        failed = patch.replace('value = 2', 'value = 3').replace('value = 1', 'value = 2') + '--- a/Missing.java\n+++ b/Missing.java\n@@ -1 +1 @@\n-missing\n+changed\n'
        before = (helper.ROOT / 'Example.java').read_bytes()
        with self.assertRaises(ValueError):
            self.request('PATCH', {'patch': failed, 'expectedHashes': {'Example.java': hashlib.sha256(before).hexdigest(), 'Missing.java': None}})
        self.assertEqual(before, (helper.ROOT / 'Example.java').read_bytes())

    def test_snapshot_includes_new_and_deleted_files_and_cannot_hide_changes_with_new_ignore_rules(self):
        (helper.ROOT / 'Example.java').unlink()
        (helper.ROOT / 'New.java').write_text('class New {}')
        (helper.ROOT / '.gitignore').write_text('New.java\n')
        (helper.ROOT / 'build.log').write_text('ignored by original rules')
        snapshot = self.request('SNAPSHOT')
        paths = {change['path']: change for change in snapshot['changes']}
        self.assertIsNone(paths['Example.java']['content'])
        self.assertEqual('class New {}', paths['New.java']['content'])
        self.assertNotIn('build.log', paths)
        self.assertEqual(snapshot['changeSetHash'], self.request('SNAPSHOT')['changeSetHash'])

    def test_unchanged_large_binary_assets_do_not_block_text_edits(self):
        payload = b'\0' * (2 * 1024 * 1024)
        (helper.BASE / 'asset.bin').write_bytes(payload)
        subprocess.run(['git', '-C', str(helper.BASE), 'add', 'asset.bin'], check=True)
        (helper.ROOT / 'asset.bin').write_bytes(payload)
        (helper.ROOT / 'Example.java').write_text('class Example { int value = 2; }')
        snapshot = self.request('SNAPSHOT')
        self.assertEqual(['Example.java'], [change['path'] for change in snapshot['changes']])

    def test_read_continuation_handles_long_lines_and_no_final_newline(self):
        content = 'x' * 40000 + '\nlast line'
        (helper.ROOT / 'Long.java').write_text(content)
        first = self.request('READ', {'path': 'Long.java'})
        self.assertTrue(first['truncated'])
        second = self.request('READ', {'path': 'Long.java', 'startLine': first['nextLine'], 'column': first['nextColumn']})
        self.assertEqual(content, first['content'] + second['content'])
        self.assertFalse(second['truncated'])

    def test_output_is_bounded_and_fixed_fixture_process_is_killed_on_timeout(self):
        result = helper.command([sys.executable, '-c', 'print("x" * 100000)'], helper.ROOT, 2, 1024)
        self.assertEqual(0, result['exitCode'])
        self.assertTrue(result['truncated'])
        self.assertEqual(1024, len(result['text']))
        result = helper.command([sys.executable, '-c', 'import time; time.sleep(5)'], helper.ROOT, .1, 1024)
        self.assertTrue(result['timedOut'])
        self.assertFalse(result['ok'])


if __name__ == '__main__':
    unittest.main()
