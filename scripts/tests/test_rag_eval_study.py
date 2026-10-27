import argparse
import copy
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace


def load(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).parents[1] / (name + '.py'))
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module); return module


study = load('rag-eval-study')
review = load('rag-eval-review')
experiment = load('rag-eval-experiment')


class StudyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup); self.root = Path(self.temp.name)

    def rows(self):
        return [{'_id': kind + str(i), 'type': kind, 'question': f'Question {kind} {i}?', 'answer': 'A',
                 'context': [[f'Fact {kind} {i}', ['The answer is A.']], [f'Distractor {kind} {i}', ['Unrelated evidence.']]],
                 'supporting_facts': [[f'Fact {kind} {i}', 0]]} for kind in ['bridge', 'comparison'] for i in range(12)]

    def prepare(self):
        source = self.root / 'source.json'; source.write_text(json.dumps(self.rows()))
        args = argparse.Namespace(input=source, output=self.root / 'study', exclude_dataset=[], development_count=4,
                                  holdout_count=4, seed=7, source_url='https://example.org/fixture', revision='synthetic-test')
        study.prepare(args); return args.output

    def test_sampling_is_order_independent_stratified_and_excludes_old_and_duplicate_questions(self):
        rows = self.rows(); duplicate = copy.deepcopy(rows[0]); duplicate['_id'] = 'another-id'; duplicate['question'] = 'ＱＵＥＳＴＩＯＮ bridge 0!'; rows.append(duplicate)
        excluded = {study.question_key('Question comparison 0?')}
        a = study.select(rows, {'bridge1'}, excluded, 8, 8, 7)
        b = study.select(list(reversed(rows)), {'bridge1'}, excluded, 8, 8, 7)
        self.assertEqual(a, b)
        flat = a['development'] + a['holdout']
        self.assertEqual(len({r['_id'] for r in flat}), 16)
        self.assertEqual(len({study.question_key(r['question']) for r in flat}), 16)
        self.assertNotIn('bridge1', {r['_id'] for r in flat})
        self.assertTrue(excluded.isdisjoint(study.question_key(r['question']) for r in flat))
        for records in a.values(): self.assertEqual(sum(r['type'] == 'bridge' for r in records), 4)

    def test_insufficient_or_unbounded_split_requests_fail(self):
        for counts in [(3, 4), (102, 4), (100, 100)]:
            with self.subTest(counts=counts), self.assertRaises(ValueError): study.select(self.rows(), set(), set(), *counts, 7)

    def test_freeze_retains_distractors_and_detects_label_changes(self):
        root = self.prepare(); protocol = study.verify(root)
        manifest = json.loads((root / 'development/dataset.json').read_text())
        self.assertEqual(len(manifest['documents']), 8)
        self.assertEqual(manifest['study']['role'], 'development')
        self.assertTrue(set(protocol['splits']['development']['sourceIds']).isdisjoint(protocol['splits']['holdout']['sourceIds']))
        (root / 'holdout/cases.jsonl').write_text('{"changed":"label"}')
        with self.assertRaises(ValueError): study.verify(root)

    def test_selection_uses_evidence_only_and_prefers_the_unchanged_baseline_on_ties(self):
        baseline = {'name': 'baseline', 'constant': 60, 'bm25Weight': 1., 'vectorWeight': 1.}
        rows = [{'configuration': baseline, 'completeHitAt5': .6, 'coverageAt5': .8, 'hitAt5': 1},
                {'configuration': dict(baseline, name='candidate', bm25Weight=.5), 'completeHitAt5': .6, 'coverageAt5': .8, 'hitAt5': 1, 'em': 1}]
        self.assertEqual(study.choose(rows, baseline)['configuration'], baseline)
        rows[1]['completeHitAt5'] = .7
        self.assertEqual(study.choose(rows, baseline)['configuration']['name'], 'candidate')

    def test_tuning_rejects_a_holdout_capture_before_launching_any_replay(self):
        root = self.prepare(); run = self.root / 'run'; shutil.copytree(root / 'holdout', run / 'dataset')
        with patch.object(study, 'module', return_value=SimpleNamespace(load_run=lambda p: {'root': run})), \
                patch.object(study.subprocess, 'run') as execute:
            with self.assertRaises(ValueError): study.tune(argparse.Namespace(study=root, run=run, output=self.root / 'selection'))
        execute.assert_not_called(); self.assertFalse((self.root / 'selection').exists())

    def test_changed_selection_is_rejected_before_opening_holdout(self):
        root = self.prepare(); selection = self.root / 'selection.json'
        selection.write_text(json.dumps({'studySha256': study.checksum(root / 'study.json')}))
        selection.with_suffix('.sha256').write_text('wrong-checksum')
        with patch.object(study.subprocess, 'run') as execute:
            with self.assertRaises(ValueError): study.validate(argparse.Namespace(study=root, selection=selection, output=self.root / 'validation'))
        execute.assert_not_called(); self.assertFalse((self.root / 'holdout-opened.json').exists())

    def test_protocol_changes_and_retuning_after_holdout_exposure_are_rejected(self):
        root = self.prepare()
        (root / 'holdout-opened.json').write_text('{"opened":true}')
        with patch.object(study.subprocess, 'run') as execute:
            with self.assertRaises(ValueError): study.tune(argparse.Namespace(study=root, run=self.root / 'unused', output=self.root / 'new-selection'))
        execute.assert_not_called()
        protocol = json.loads((root / 'study.json').read_text()); protocol['grid'][0]['bm25Weight'] = .01
        (root / 'study.json').write_text(json.dumps(protocol))
        with self.assertRaises(ValueError): study.verify(root)

    def test_declared_weight_change_never_authorizes_budget_or_top_k_changes(self):
        baseline = {'retrieval': {'bm25Weight': 1, 'knnWeight': 1}, 'options': {'rrfK': 60, 'evidenceTokens': 4000}, 'topK': 5}
        candidate = copy.deepcopy(baseline); candidate['retrieval']['bm25Weight'] = .5
        changes = experiment.configuration_changes(json.dumps(baseline), json.dumps(candidate), ['retrieval.bm25Weight'])
        self.assertEqual(changes[0]['candidate'], .5)
        candidate['options']['evidenceTokens'] = 5000
        with self.assertRaises(ValueError): experiment.configuration_changes(json.dumps(baseline), json.dumps(candidate), ['retrieval.bm25Weight'])
        with self.assertRaises(ValueError): experiment.configuration_changes('{}', '{}', ['owner'])

    def test_score_disagreements_remain_review_leads_instead_of_new_gold_labels(self):
        row = {'deterministic': {'generationComplete': True, 'wire': {'state': 'FULL'}}, 'nativeAnswer': {'em': 0},
               'semantic': {'assessment': {'verdict': 'correct'}}, 'answerSupport': {'metrics': {'judgeClaimSupportRate': 1}}}
        original = copy.deepcopy(row)
        self.assertIn('NATIVE_SEMANTIC_DISAGREEMENT', review.flags(row))
        self.assertEqual(row, original)
        row['semantic']['assessment']['verdict'] = 'wrong'
        self.assertIn('REFERENCE_SUPPORT_DISAGREEMENT', review.flags(row))

    def test_different_answer_wording_does_not_falsely_become_native_score_instability(self):
        a = {'answer': 'Pine', 'actualContexts': ['source'], 'nativeAnswer': {'prediction': 'Pine', 'em': 0, 'f1': 0}, 'answerSupport': {}}
        b = dict(a, answer='Pine genus', nativeAnswer={'prediction': 'Pine genus', 'em': 0, 'f1': 0})
        self.assertEqual(review.variation_flags(a, b), {'ANSWER_VARIATION_SAME_CONTEXT'})
        b['nativeAnswer']['f1'] = .5
        self.assertIn('NATIVE_SCORE_VARIATION_SAME_CONTEXT', review.variation_flags(a, b))

    def test_java_nanosecond_timestamps_compare_correctly_with_python_microsecond_locks(self):
        self.assertGreater(study.instant('2026-10-27T03:25:00.337558089Z'), study.instant('2026-10-27T03:24:59.999999+00:00'))
        self.assertEqual(study.instant('2026-10-27T03:25:00.337558089Z'), study.instant('2026-10-27T03:25:00.337558+00:00'))
        with self.assertRaises(ValueError): study.instant('2026-10-27T03:25:00')


if __name__ == '__main__': unittest.main()
