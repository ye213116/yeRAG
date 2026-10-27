import argparse
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


def module(name):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), Path(__file__).parents[1] / (name + '.py'))
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value


archive = module('rag-eval-archive'); holdout = module('rag-eval-holdout')


class CloseoutTest(unittest.TestCase):
    def test_archive_relocates_and_detects_mutated_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'old'; root.mkdir(); (root / 'evidence.json').write_text('{"answer":"fixture"}')
            manifest = {'files': archive.inventory(root)}
            (root / 'archive.json').write_text(json.dumps(manifest)); (root / 'archive.sha256').write_text(archive.sha(root / 'archive.json'))
            relocated = root.with_name('relocated'); root.rename(relocated)
            self.assertEqual(archive.verify(relocated)['files'], manifest['files'])
            (relocated / 'evidence.json').write_text('{"answer":"changed"}')
            with self.assertRaisesRegex(ValueError, 'Changed/missing'): archive.verify(relocated)

    def test_archive_refuses_symlink_and_manifest_path_escape(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'archive'; root.mkdir(); secret = Path(directory) / 'secret'; secret.write_text('private')
            link = root / 'link'; link.symlink_to(secret)
            with self.assertRaises(ValueError): archive.copy_file(link, root / 'copied')
            (root / 'archive.json').write_text(json.dumps({'files': {'../secret': archive.sha(secret)}}))
            (root / 'archive.sha256').write_text(archive.sha(root / 'archive.json'))
            with self.assertRaises(ValueError): archive.verify(root)

    def rows(self):
        rows = [{'_id': f'{kind}-{i}', 'type': kind, 'question': f'{kind} question {i}'} for kind in ['bridge', 'comparison'] for i in range(7)]
        rows.append({'_id': 'duplicate-question', 'type': 'bridge', 'question': 'BRIDGE question 0!'})
        return rows

    def test_fresh_selection_is_balanced_stable_and_excludes_prior_questions(self):
        rows = self.rows(); excluded_ids = {'comparison-0'}; questions = {'bridgequestion0'}
        chosen = holdout.select(rows, excluded_ids, questions, 8, 123)
        self.assertEqual(chosen, holdout.select(list(reversed(rows)), excluded_ids, questions, 8, 123))
        self.assertEqual(len(chosen), len(set(chosen)))
        self.assertFalse(set(chosen) & {'comparison-0', 'bridge-0', 'duplicate-question'})
        kinds = [next(r['type'] for r in rows if r['_id'] == value) for value in chosen]
        self.assertEqual(kinds.count('bridge'), 4); self.assertEqual(kinds.count('comparison'), 4)

    def test_held_out_labels_and_protocol_cannot_change_after_lock(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); (root / 'dataset').mkdir(); path = root / 'dataset/cases.jsonl'; path.write_text('frozen labels')
            (root / 'protocol.json').write_text(json.dumps({'datasetFiles': {'cases.jsonl': holdout.sha(path)}}))
            (root / 'protocol.sha256').write_text(holdout.sha(root / 'protocol.json'))
            holdout.verify(root, check_runtime=False)
            path.write_text('changed labels')
            with self.assertRaisesRegex(ValueError, 'Changed holdout'): holdout.verify(root, check_runtime=False)

    def test_odd_or_unbounded_question_counts_are_rejected(self):
        for count in [1, 3, 102]:
            with self.subTest(count=count), self.assertRaises(ValueError): holdout.select(self.rows(), set(), set(), count, 1)

    def test_frozen_judge_json_container_types_do_not_change_model_identity(self):
        signature = {'options': {'rerank': False}, 'topK': 5}
        protocol = {'datasetFiles': {}, 'codeVersion': 'code', 'owner': 'id:1', 'generation': 'model-config',
                    'generationParameters': {'temperature': 0}, 'judges': {'judge': [['model', 'prompt']]}, 'expectedSignature': signature}
        run = {'root': Path('/unused'), 'capture': {'codeVersion': 'code', 'owner': 'id:1', 'generationParameters': {'temperature': 0}},
               'generation': 'model-config', 'judges': {'judge': [('model', 'prompt')]}, 'signature': json.dumps(signature)}
        holdout.validate_run(protocol, Path('/unused'), run, 'rrf-01')
        run['judges'] = {'judge': [('other-model', 'prompt')]}
        with self.assertRaises(ValueError): holdout.validate_run(protocol, Path('/unused'), run, 'rrf-01')


if __name__ == '__main__': unittest.main()
