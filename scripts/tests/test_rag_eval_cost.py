import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('cost', Path(__file__).parents[1] / 'rag-eval-cost.py')
cost = importlib.util.module_from_spec(spec); spec.loader.exec_module(cost)


class UsageLedgerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup); self.root = Path(self.tmp.name)

    def trace(self, usage):
        trace = {'runId': 'fixture', 'kind': 'model-call', 'events': [
            {'name': 'request', 'data': {'model': 'test-model'}}, {'name': 'generation', 'data': {'usage': usage}}]}
        (self.root / 'fixture.json').write_text(json.dumps(trace))

    def test_missing_usage_is_not_zero_cost(self):
        self.trace(None)
        result = cost.usage_ledger(self.root)
        self.assertEqual(result['models']['test-model']['missingUsage'], 1)
        self.assertFalse(result['totalBillAvailable'])
        self.assertNotIn('reportedUsageCost', result['models']['test-model'])

    def test_explicit_rates_only_price_recorded_model_calls(self):
        self.trace({'prompt_tokens': 1000, 'completion_tokens': 500, 'total_tokens': 1500})
        rates = {'source': 'unit-test fixture, not market price', 'effectiveAt': '2026-10-28', 'currency': 'TEST',
                 'models': {'test-model': {'inputPerMillion': '2', 'outputPerMillion': '4'}}}
        result = cost.usage_ledger(self.root, rates)
        self.assertEqual(result['models']['test-model']['reportedUsageCost'], '0.004')
        self.assertFalse(result['totalBillAvailable'])

    def test_invalid_rates_do_not_produce_money(self):
        self.trace({'prompt_tokens': 1, 'completion_tokens': 1, 'total_tokens': 2})
        rates = {'source': 'test', 'effectiveAt': 'test', 'currency': 'TEST',
                 'models': {'test-model': {'inputPerMillion': 'NaN', 'outputPerMillion': '1'}}}
        with self.assertRaisesRegex(ValueError, 'finite'): cost.usage_ledger(self.root, rates)


if __name__ == '__main__': unittest.main()
