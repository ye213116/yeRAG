import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('archive_tool', Path(__file__).parents[1] / 'rag-eval-archive.py')
archive_tool = importlib.util.module_from_spec(spec); spec.loader.exec_module(archive_tool)


class BundleTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def test_round_trip_checks_all_members(self):
        source = self.root / 'archive'; source.mkdir()
        (source / 'evidence.txt').write_text('frozen evidence')
        manifest = {'files': {'evidence.txt': archive_tool.sha(source / 'evidence.txt')}}
        (source / 'archive.json').write_text(json.dumps(manifest))
        (source / 'archive.sha256').write_text(archive_tool.sha(source / 'archive.json'))
        bundle = self.root / 'transfer.tar.gz'
        archive_tool.bundle(source, bundle)
        output = self.root / 'restored'
        self.assertEqual(archive_tool.unpack(bundle, output)['files'], 1)
        self.assertEqual((output / 'evidence.txt').read_text(), 'frozen evidence')

    def test_rejects_changed_transfer(self):
        bundle = self.root / 'transfer.tar.gz'; bundle.write_bytes(b'changed')
        bundle.with_name(bundle.name + '.sha256').write_text('0' * 64)
        with self.assertRaisesRegex(ValueError, 'checksum'): archive_tool.unpack(bundle, self.root / 'restored')

    def test_rejects_path_traversal_before_writing_files(self):
        bundle = self.root / 'transfer.tar.gz'
        with tarfile.open(bundle, 'w:gz') as stream:
            member = tarfile.TarInfo('../outside'); member.size = 3
            stream.addfile(member, io.BytesIO(b'bad'))
        bundle.with_name(bundle.name + '.sha256').write_text(hashlib.sha256(bundle.read_bytes()).hexdigest())
        with self.assertRaisesRegex(ValueError, 'Unsafe'): archive_tool.unpack(bundle, self.root / 'restored')
        self.assertFalse((self.root / 'outside').exists())


if __name__ == '__main__': unittest.main()
