import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('calibrate', Path(__file__).parents[1] / 'rag-eval-calibrate.py')
calibrate = importlib.util.module_from_spec(spec); spec.loader.exec_module(calibrate)


class CalibrationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        item = {'id': 'case-1', 'machineVerdict': 'partial', 'answer': 'frozen answer'}
        self.packet = {'state': 'PENDING_HUMAN_REVIEW', 'items': [dict(item, itemSha256=calibrate.digest(item))]}
        self.decisions = {'packetSha256': calibrate.digest(self.packet), 'reviewer': 'unit-test-fixture',
                          'reviewedAt': '2026-10-28T00:00:00+08:00', 'decisions': [
                              {'id': 'case-1', 'itemSha256': self.packet['items'][0]['itemSha256'],
                               'labels': 'valid', 'verdict': 'wrong', 'reason': 'test disagreement'}]}

    def run_score(self):
        packet = self.root / 'packet.json'; packet.write_text(json.dumps(self.packet))
        decisions = self.root / 'decisions.json'; decisions.write_text(json.dumps(self.decisions))
        output = self.root / 'result.json'
        calibrate.score(packet, decisions, output)
        return json.loads(output.read_text())

    def test_records_disagreement_without_modifying_labels(self):
        result = self.run_score()
        self.assertEqual(result['judgeAgreement'], 0)
        self.assertEqual(result['confusionMatrix'], {'partial→wrong': 1})
        self.assertFalse(result['historicalLabelsAndScoresChanged'])

    def test_changed_answer_rejects_prior_decisions(self):
        self.packet['items'][0]['answer'] = 'changed answer'
        with self.assertRaisesRegex(ValueError, 'another packet'): self.run_score()

    def test_blank_review_is_not_approval(self):
        self.decisions['reviewer'] = ''
        with self.assertRaisesRegex(ValueError, 'reviewer'): self.run_score()

    def test_duplicate_decision_rejected(self):
        self.decisions['decisions'] *= 2
        with self.assertRaisesRegex(ValueError, 'exactly one'): self.run_score()


if __name__ == '__main__': unittest.main()
