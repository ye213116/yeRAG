import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from argparse import Namespace


spec = importlib.util.spec_from_file_location("experiment", Path(__file__).parents[1] / "rag-eval-experiment.py")
experiment = importlib.util.module_from_spec(spec)
spec.loader.exec_module(experiment)


class ComparisonTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.addCleanup(self.temp.cleanup)

    def run_data(self):
        rows = {}
        for name in ["one", "two"]:
            rows[name] = {"id": name, "labelSource": "PUBLIC_SOURCE_MAPPED_NOT_HUMAN_REVIEWED",
                          "deterministic": {"status": "AVAILABLE", "generationComplete": True,
                                            "top5": {"required": 2, "state": "FULL", "hit": 1, "coverage": 1}},
                          "nativeAnswer": {"em": 1, "f1": 1}, "performance": {"generationConfiguration": {"model": "synthetic"}}}
        capture = {"datasetHash": "dataset", "corpusFingerprint": "corpus", "corpusVersion": "v1", "owner": "id:1",
                   "generationParameters": {"temperature": 0}, "codeVersion": "synthetic-code", "cases": [{"id": name} for name in rows]}
        return {"root": self.root, "rows": rows, "capture": capture, "preflight": {"permissionFingerprint": "permission"},
                "signature": "same pipeline settings", "generation": "same model", "supportVersion": [],
                "summary": {"measurements": {"pipelineLatencyMs": {"p95": 100, "count": 2}, "generationUsage": {"totalTokens": 20, "reportedCalls": 2}}}}

    def test_complete_hit_and_exact_match_regressions_are_paired_and_reported(self):
        left = self.run_data(); right = copy.deepcopy(left)
        right["rows"]["one"]["deterministic"]["top5"].update(state="PARTIAL", coverage=.5)
        result = experiment.compare(left, right, self.root / "out", {"maxDrops": {"completeHitAt5": 0}})
        self.assertEqual(result["gateStatus"], "FAIL")
        self.assertEqual(result["metrics"]["hitAt5"]["delta"], 0)
        self.assertEqual(result["metrics"]["completeHitAt5"]["losses"], 1)
        self.assertEqual(result["metrics"]["completeHitAt5"]["changes"][0]["caseId"], "one")

    def test_unconfigured_policy_is_never_pass(self):
        result = experiment.compare(self.run_data(), self.run_data(), self.root / "out")
        self.assertEqual(result["gateStatus"], "NOT_CONFIGURED")

    def test_missing_metric_and_missing_tokens_are_inconclusive_not_zero_or_pass(self):
        left = self.run_data(); right = copy.deepcopy(left)
        right["summary"]["measurements"]["generationUsage"]["reportedCalls"] = 1
        result = experiment.compare(left, right, self.root / "out", {"maxDrops": {"judgeClaimSupportRate": 0}, "maxGenerationTokenRatio": 1.1})
        self.assertEqual(result["gateStatus"], "INCONCLUSIVE")
        self.assertNotIn("delta", result["metrics"]["judgeClaimSupportRate"])

    def test_dataset_permission_config_and_case_mismatches_are_rejected(self):
        for key in ["datasetHash", "corpusFingerprint", "owner", "generationParameters"]:
            left = self.run_data(); right = copy.deepcopy(left); right["capture"][key] = "changed"
            with self.subTest(key=key), self.assertRaises(ValueError):
                experiment.compare(left, right, self.root / "rejected")
        left = self.run_data(); right = copy.deepcopy(left); right["signature"] = "changed token budget"
        with self.assertRaises(ValueError): experiment.compare(left, right, self.root / "rejected")
        right = copy.deepcopy(left); del right["rows"]["two"]
        with self.assertRaises(ValueError): experiment.compare(left, right, self.root / "rejected")
        right = copy.deepcopy(left); right["preflight"]["permissionFingerprint"] = "other identity scope"
        with self.assertRaises(ValueError): experiment.compare(left, right, self.root / "rejected")
        right = copy.deepcopy(left); right["judges"] = {"support": [("different-judge-model", "same-prompt")]}
        with self.assertRaises(ValueError): experiment.compare(left, right, self.root / "rejected")

    def test_code_changes_require_explicit_comparison_mode(self):
        left = self.run_data(); right = copy.deepcopy(left); right["capture"]["codeVersion"] = "new implementation"
        with self.assertRaises(ValueError): experiment.compare(left, right, self.root / "rejected")
        result = experiment.compare(left, right, self.root / "allowed", {"maxDrops": {"em": 0}}, allow_code_change=True)
        self.assertEqual(result["gateStatus"], "PASS")

    def test_policy_typos_negative_budgets_and_nan_are_rejected(self):
        for policy in [{"maxDrops": {"recallAt5": 0}}, {"maxDrops": {"em": -1}}, {"maxP95LatencyRatio": float("nan")}, {"maxDrops": {"em": True}}]:
            with self.subTest(policy=policy), self.assertRaises(ValueError): experiment.validate_policy(policy)

    def rerank_pair(self):
        left = self.run_data(); right = copy.deepcopy(left)
        settings = {'options': {'rerank': False, 'rewrite': False, 'evidenceTokens': 4000}, 'topK': 5,
                    'retrieval': {'rerank': {'model': 'synthetic-reranker', 'topN': 50, 'maxDocChars': 1000, 'timeoutMs': 1500, 'serviceFingerprint': 'synthetic'}}}
        left['signature'] = json.dumps(settings)
        settings['options']['rerank'] = True; right['signature'] = json.dumps(settings)
        right['rerankHealth'] = {'requested': 2, 'statuses': {'SUCCEEDED': 2}}
        return left, right

    def test_rerank_ablation_only_allows_declared_switch_not_model_window_or_budget(self):
        left, right = self.rerank_pair()
        with self.assertRaises(ValueError): experiment.compare(left, right, self.root / 'implicit')
        result = experiment.compare(left, right, self.root / 'allowed', {'maxDrops': {'em': 0}}, allowed_config_changes=['options.rerank'])
        self.assertEqual(result['gateStatus'], 'PASS')
        self.assertEqual(result['configurationChanges'], [{'field': 'options.rerank', 'baseline': False, 'candidate': True}])
        for section, key, value in [('options', 'evidenceTokens', 8000), ('retrieval', 'rerank', {'model': 'other-model'}), ('topK', None, 10)]:
            changed = copy.deepcopy(right); settings = json.loads(changed['signature'])
            if key: settings[section][key] = value
            else: settings[section] = value
            changed['signature'] = json.dumps(settings)
            with self.subTest(key=key), self.assertRaises(ValueError):
                experiment.compare(left, changed, self.root / 'rejected', allowed_config_changes=['options.rerank'])

    def test_skipped_or_degraded_rerank_never_passes_as_pure_rerank(self):
        for statuses in [{'SKIPPED': 2}, {'SUCCEEDED': 1, 'DEGRADED': 1}]:
            left, right = self.rerank_pair(); right['rerankHealth']['statuses'] = statuses
            result = experiment.compare(left, right, self.root / str(len(statuses)), {'maxDrops': {'em': 0}}, allowed_config_changes=['options.rerank'])
            self.assertEqual(result['gateStatus'], 'INCONCLUSIVE')

    def test_rerank_comparison_requires_provider_configuration_evidence(self):
        left, right = self.rerank_pair()
        for run in [left, right]:
            settings = json.loads(run['signature']); del settings['retrieval']['rerank']; run['signature'] = json.dumps(settings)
        with self.assertRaises(ValueError):
            experiment.compare(left, right, self.root / 'missing-config', allowed_config_changes=['options.rerank'])

    def test_successful_rerank_without_quality_policy_is_not_quality_pass(self):
        left, right = self.rerank_pair()
        result = experiment.compare(left, right, self.root / 'no-policy', allowed_config_changes=['options.rerank'])
        self.assertEqual(result['gateStatus'], 'NOT_CONFIGURED')

    def test_latency_ratio_and_incomplete_answers_prevent_false_acceptance(self):
        left = self.run_data(); right = copy.deepcopy(left)
        right["summary"]["measurements"]["pipelineLatencyMs"]["p95"] = 200
        self.assertEqual(experiment.compare(left, right, self.root / "latency", {"maxP95LatencyRatio": 1.1})["gateStatus"], "FAIL")
        right = copy.deepcopy(left); right["rows"]["one"]["deterministic"]["generationComplete"] = False
        self.assertEqual(experiment.compare(left, right, self.root / "incomplete", {"maxDrops": {"em": 0}})["gateStatus"], "INCONCLUSIVE")

    def write_bundle(self):
        root = self.root / "bundle"; (root / "report").mkdir(parents=True); (root / "dataset").mkdir(); (root / "artifacts").mkdir()
        data = self.run_data(); data["capture"]["experiment"] = {"baseline": True, "recallStrategy": "BM25", "supportEnabled": False}
        data["preflight"].update(datasetHash="dataset", corpusFingerprint="corpus")
        data["summary"].update(datasetHash="dataset", corpusFingerprint="corpus")
        for number, case in enumerate(data["capture"]["cases"], 1):
            run_id = f"00000000-0000-4000-8000-{number:012d}"; case["pipelineRunId"] = run_id
            pipeline = {"owner": "id:1", "corpusVersion": "v1", "events": [
                {"name": "request", "data": {"options": {"rewrite": False, "rerank": False}, "topK": 5, "mode": "FIXED_QA"}},
                {"name": "retrievalConfig", "data": {"mode": "RRF"}}]}
            (root / "artifacts" / (run_id + ".json")).write_text(json.dumps(pipeline))
        (root / "capture.json").write_text(json.dumps(data["capture"]))
        (root / "preflight.json").write_text(json.dumps(data["preflight"]))
        (root / "report/summary.json").write_text(json.dumps(data["summary"]))
        (root / "report/results.jsonl").write_text("\n".join(json.dumps(row) for row in data["rows"].values()))
        (root / "report/ragas.jsonl").write_text(""); (root / "report/ragas-index.jsonl").write_text("")
        (root / "dataset/cases.jsonl").write_text("\n".join(json.dumps({"id": name}) for name in data["rows"]))
        (root / "dataset/dataset.json").write_text("{}")
        proof = {key: experiment.sha(root / name) for name, key in [("capture.json", "captureSha256"), ("preflight.json", "preflightSha256"),
                 ("report/results.jsonl", "resultsSha256"), ("report/summary.json", "summarySha256"), ("report/ragas.jsonl", "ragasSha256"), ("report/ragas-index.jsonl", "ragasIndexSha256")]}
        proof["artifacts"] = {p.name: experiment.sha(p) for p in (root / "artifacts").iterdir()}
        proof["datasetFiles"] = {p.name: experiment.sha(p) for p in (root / "dataset").iterdir()}
        (root / "report/provenance.json").write_text(json.dumps(proof)); return root

    def test_mutated_frozen_artifact_is_detected(self):
        root = self.write_bundle(); self.assertEqual(len(experiment.load_run(root)["rows"]), 2)
        next((root / "artifacts").iterdir()).write_text("{}")
        with self.assertRaises(ValueError): experiment.load_run(root)

    def test_changed_labels_in_dataset_proof_are_rejected(self):
        root = self.write_bundle(); (root / "dataset/cases.jsonl").write_text('{"id":"changed"}')
        with self.assertRaises(ValueError): experiment.load_run(root)

    def test_successful_rerank_requires_actual_rerank_scores_in_top_k(self):
        root = self.write_bundle(); proof = experiment.read(root / 'report/provenance.json')
        capture = experiment.read(root / 'capture.json'); capture['experiment']['baseline'] = False
        (root / 'capture.json').write_text(json.dumps(capture)); proof['captureSha256'] = experiment.sha(root / 'capture.json')
        path = next((root / 'artifacts').iterdir()); pipeline = experiment.read(path)
        pipeline['events'][0]['data']['options']['rerank'] = True
        pipeline['events'] += [{'name': 'RERANK', 'data': {'outcome': {'status': 'SUCCEEDED', 'reason': 'RERANKED'}}},
                               {'name': 'TOP_K', 'data': {'candidates': [{'scoreKind': 'RRF'}]}}]
        path.write_text(json.dumps(pipeline)); proof['artifacts'][path.name] = experiment.sha(path)
        (root / 'report/provenance.json').write_text(json.dumps(proof))
        with self.assertRaisesRegex(ValueError, 'actual reranked TopK'): experiment.load_run(root)

    def test_external_paths_in_dataset_proof_are_rejected(self):
        root = self.write_bundle(); external = self.root / "external.json"; external.write_text("{}")
        proof = experiment.read(root / "report/provenance.json")
        proof["datasetFiles"]["../../external.json"] = experiment.sha(external)
        (root / "report/provenance.json").write_text(json.dumps(proof))
        with self.assertRaises(ValueError): experiment.load_run(root)

    def resume_args(self, root):
        (root / "matrix.json").write_text(json.dumps({"repeats": 1, "status": "RUNNING"}))
        return Namespace(output=root, dataset=self.root / "dataset", resume=True, repeats=1,
                         support=False, policy=None, spring_arg=[])

    def test_resume_reuses_complete_verified_answers_before_starting_next_strategy(self):
        bundle = self.write_bundle(); root = self.root / "matrix"; root.mkdir(); bundle.rename(root / "bm25-01")
        args = self.resume_args(root)
        with patch.dict(experiment.os.environ, {"AI_RAG_EVAL_IDENTITY": "id:1", "RAG_EVAL_SKIP_BUILD": "1"}), \
                patch.object(experiment.subprocess, "run", side_effect=RuntimeError("stop before model calls")) as execute:
            with self.assertRaises(RuntimeError): experiment.matrix(args)
        self.assertEqual(execute.call_count, 1)
        self.assertIn(str(root / "vector-01"), execute.call_args.args[0])
        self.assertEqual(len(experiment.read(root / "matrix.json")["runs"]), 1)

    def test_resume_never_overwrites_a_partial_run(self):
        root = self.root / "matrix"; root.mkdir(); partial = root / "bm25-01"; partial.mkdir()
        checkpoint = partial / "capture.partial.json"; checkpoint.write_text('{"partial":"preserve"}')
        args = self.resume_args(root)
        with patch.dict(experiment.os.environ, {"AI_RAG_EVAL_IDENTITY": "id:1", "RAG_EVAL_SKIP_BUILD": "1"}), \
                patch.object(experiment.subprocess, "run") as execute:
            with self.assertRaises(FileNotFoundError): experiment.matrix(args)
        execute.assert_not_called()
        self.assertEqual(checkpoint.read_text(), '{"partial":"preserve"}')

    def test_invalid_policy_is_rejected_before_build_or_model_calls(self):
        root = self.root / "matrix"; policy = self.root / "policy.json"
        policy.write_text('{"maxDrops":{"misspelledMetric":0}}')
        args = Namespace(output=root, dataset=self.root / "dataset", resume=False, repeats=1,
                         support=False, policy=policy, spring_arg=[])
        with patch.dict(experiment.os.environ, {"AI_RAG_EVAL_IDENTITY": "id:1"}), \
                patch.object(experiment.subprocess, "run") as execute:
            with self.assertRaises(ValueError): experiment.matrix(args)
        execute.assert_not_called()
        self.assertFalse(root.exists())

    def test_rerank_command_keeps_hybrid_and_only_changes_rerank_option(self):
        root = self.root / 'rerank'; left, _ = self.rerank_pair()
        left['capture']['experiment'] = {'baseline': False, 'recallStrategy': 'HYBRID'}
        args = Namespace(action='rerank', output=root, dataset=self.root / 'dataset', resume=False, repeats=1,
                         support=False, policy=None, spring_arg=['--ai.rag.eval.baseline=true'])
        with patch.dict(experiment.os.environ, {'AI_RAG_EVAL_IDENTITY': 'id:1', 'RAG_EVAL_SKIP_BUILD': '1'}), \
                patch.object(experiment, 'load_run', return_value=left), \
                patch.object(experiment.subprocess, 'run', side_effect=[None, RuntimeError('stop before model')]) as execute:
            with self.assertRaises(RuntimeError): experiment.matrix(args)
        control, candidate = [call.args[0] for call in execute.call_args_list]
        for command in [control, candidate]:
            self.assertIn('--ai.rag.eval.recall-strategy=HYBRID', command)
            self.assertGreater(command.index('--ai.rag.eval.baseline=false'), command.index('--ai.rag.eval.baseline=true'))
            self.assertIn('--ai.rag.rewrite-enabled=false', command)
        self.assertIn('--ai.rag.rerank-enabled=false', control)
        self.assertIn('--ai.rag.rerank-enabled=true', candidate)
        self.assertIn('--ai.rag.eval.require-rerank=true', candidate)


if __name__ == "__main__":
    unittest.main()
