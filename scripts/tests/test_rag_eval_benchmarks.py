import importlib.util
import importlib.metadata
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('benchmarks', Path(__file__).parents[1] / 'rag-eval-benchmarks.py')
benchmarks = importlib.util.module_from_spec(spec); spec.loader.exec_module(benchmarks)


class NativeMetricsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup); self.root = Path(self.tmp.name)

    def test_rejects_replaced_chinese_scorer_before_execution(self):
        scorer = self.root / 'scorer.py'; scorer.write_text('raise RuntimeError("must not run")')
        with self.assertRaisesRegex(ValueError, 'pinned official'):
            benchmarks.dureader(self.root / 'missing', self.root / 'missing', scorer, 'unit-test')

    def test_rejects_duplicate_qrels(self):
        qrels = self.root / 'qrels.tsv'; qrels.write_text('query-id\tcorpus-id\tscore\nq1\td1\t1\nq1\td1\t1\n')
        with self.assertRaisesRegex(ValueError, 'Duplicate'): benchmarks.qrels(qrels)

    @unittest.skipUnless(importlib.util.find_spec('pytrec_eval'), 'opt-in native scorer environment required')
    def test_all_judged_queries_stay_in_denominator(self):
        qrels = self.root / 'qrels.tsv'; qrels.write_text('query-id\tcorpus-id\tscore\nq1\td1\t1\nq2\td2\t1\n')
        run = self.root / 'run.json'; run.write_text(json.dumps({'q1': {'d1': 1.0}}))
        scored = benchmarks.beir(qrels, run, [1, 5], 'unit-test')
        self.assertEqual(scored['metrics']['ndcg_cut_1'], 0.5)
        self.assertEqual(scored['metrics']['recall_5'], 0.5)
        self.assertEqual(scored['missingOrEmptyQueries'], 1)

    @unittest.skipUnless(importlib.util.find_spec('pytrec_eval'), 'opt-in native scorer environment required')
    def test_graded_gain_uses_trec_linear_semantics(self):
        import math
        qrels = self.root / 'qrels.tsv'; qrels.write_text('query-id\tcorpus-id\tscore\nq1\td1\t1\nq1\td2\t3\n')
        run = self.root / 'run.json'; run.write_text(json.dumps({'q1': {'d1': 2, 'd2': 1}}))
        scored = benchmarks.beir(qrels, run, [2], 'unit-test')
        expected = (1 + 3 / math.log2(3)) / (3 + 1 / math.log2(3))
        self.assertAlmostEqual(scored['metrics']['ndcg_cut_2'], expected)


if __name__ == '__main__': unittest.main()
