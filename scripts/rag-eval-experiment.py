#!/usr/bin/env python3
"""Bound, paired offline comparisons and sequential baseline runs; no evaluation framework dependency."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import subprocess
import tempfile


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def event(run, name):
    values = [item["data"] for item in run["events"] if item["name"] == name]
    if len(values) != 1:
        raise ValueError(f"Missing/ambiguous artifact event: {name}")
    return values[0]


def load_run(root):
    root = Path(root).resolve()
    proof = read(root / "report/provenance.json")
    for name, field in [("capture.json", "captureSha256"), ("preflight.json", "preflightSha256"),
                        ("report/results.jsonl", "resultsSha256"), ("report/summary.json", "summarySha256"),
                        ("report/ragas.jsonl", "ragasSha256"), ("report/ragas-index.jsonl", "ragasIndexSha256")]:
        if sha(root / name) != proof[field]:
            raise ValueError(f"Changed report input: {name}")
    if not {"dataset.json", "cases.jsonl"}.issubset(proof["datasetFiles"]):
        raise ValueError("Dataset proof incomplete")
    dataset_root = (root / "dataset").resolve()
    for name, checksum in proof["datasetFiles"].items():
        path = (dataset_root / name).resolve()
        if not path.is_relative_to(dataset_root) or sha(path) != checksum:
            raise ValueError("Changed dataset input")
    for name, checksum in proof["artifacts"].items():
        if not re.fullmatch(r"[a-f0-9-]{36}\.json", name) or sha(root / "artifacts" / name) != checksum:
            raise ValueError("Changed or invalid artifact")
    capture, preflight, summary = (read(root / name) for name in ["capture.json", "preflight.json", "report/summary.json"])
    rows_list = [json.loads(line) for line in (root / "report/results.jsonl").read_text().splitlines() if line.strip()]
    rows = {row["id"]: row for row in rows_list}
    cases = [json.loads(line) for line in (root / "dataset/cases.jsonl").read_text().splitlines() if line.strip() and not line.startswith("#")]
    if len(rows) != len(rows_list) or set(rows) != {case["id"] for case in cases} or set(rows) != {case["id"] for case in capture["cases"]}:
        raise ValueError("Duplicate or missing paired cases")
    for field in ["datasetHash", "corpusFingerprint"]:
        if summary[field] != capture[field] or preflight[field] != capture[field]:
            raise ValueError("Dataset/preflight/capture binding mismatch")
    signatures, model_configs, support_versions = set(), set(), set()
    rerank_health = {"requested": 0, "statuses": {}, "reasons": {}, "models": {}}
    judge_configs = {"judge": set(), "support": set()}
    for attempt in capture["cases"]:
        if not attempt.get("pipelineRunId"):
            continue
        name = attempt["pipelineRunId"] + ".json"
        if name not in proof["artifacts"]:
            raise ValueError("Pipeline missing from artifact proof")
        pipeline = read(root / "artifacts" / name)
        request = event(pipeline, "request")
        config = dict(event(pipeline, "retrievalConfig"))
        config.pop("recallStrategy", None)
        if any(item["name"] == "rerankConfig" for item in pipeline["events"]):
            config["rerank"] = event(pipeline, "rerankConfig")
        options = request["options"]
        if capture.get("experiment", {}).get("baseline") and (options["rewrite"] or options["rerank"]):
            raise ValueError("Baseline contains query rewrite/rerank confound")
        if pipeline["owner"] != capture["owner"] or pipeline["corpusVersion"] != capture["corpusVersion"]:
            raise ValueError("Pipeline owner/corpus mismatch")
        rerank_health["requested"] += bool(options["rerank"])
        stages = [item["data"] for item in pipeline["events"] if item["name"] == "RERANK"]
        outcome = stages[0].get("outcome", stages[0]) if len(stages) == 1 else {}
        for field, value in [("statuses", outcome.get("status", "MISSING")), ("reasons", outcome.get("reason", "UNKNOWN")),
                             ("models", outcome.get("evidence", {}).get("model", "NOT_USED"))]:
            rerank_health[field][value] = rerank_health[field].get(value, 0) + 1
        if outcome.get("status") == "SUCCEEDED":
            top = event(pipeline, "TOP_K")["candidates"]
            if not options["rerank"] or any(chunk.get("scoreKind") != "RERANK" for chunk in top):
                raise ValueError("Successful rerank lacks actual reranked TopK evidence")
        signatures.add(json.dumps({"options": options, "retrieval": config, "topK": request["topK"], "mode": request["mode"],
                                   "corpusMd5s": request.get("corpusMd5s", []), "conciseAnswer": request.get("conciseAnswer", False)}, sort_keys=True))
        performance = rows[attempt["id"]].get("performance", {})
        if performance.get("generationConfiguration"):
            model_configs.add(json.dumps(performance["generationConfiguration"], sort_keys=True))
        if performance.get("supportPromptVersion"):
            support_versions.add(performance["supportPromptVersion"])
        for kind, configs in judge_configs.items():
            if performance.get(kind + "Model"):
                configs.add((performance[kind + "Model"], performance.get(kind + "PromptVersion")))
    if len(signatures) != 1 or len(model_configs) != 1 or len(support_versions) > 1 or any(len(v) > 1 for v in judge_configs.values()):
        raise ValueError("Missing or inconsistent model/retrieval configuration within run")
    return {"root": root, "rows": rows, "capture": capture, "preflight": preflight, "summary": summary,
            "signature": next(iter(signatures)), "generation": next(iter(model_configs)), "supportVersion": sorted(support_versions),
            "judges": {kind: sorted(configs) for kind, configs in judge_configs.items()}, "rerankHealth": rerank_health}


def metric(row, name):
    evidence = row.get("deterministic", {}).get("top5", {})
    labels = row.get("labelSource") == "PUBLIC_SOURCE_MAPPED_NOT_HUMAN_REVIEWED" or row.get("humanReview", {}).get("labels") == "APPROVED"
    if not labels:
        return None
    if name in {"hitAt5", "coverageAt5", "completeHitAt5"}:
        if row.get("deterministic", {}).get("status") != "AVAILABLE" or not evidence.get("required", 0):
            return None
        return float(evidence["state"] == "FULL") if name == "completeHitAt5" else evidence[{"hitAt5": "hit", "coverageAt5": "coverage"}[name]]
    if name in {"em", "f1"}:
        return row.get("nativeAnswer", {}).get(name)
    if name == "judgeClaimSupportRate":
        return row.get("answerSupport", {}).get("metrics", {}).get(name)
    raise ValueError("Unknown metric: " + name)


METRICS = ["hitAt5", "coverageAt5", "completeHitAt5", "em", "f1", "judgeClaimSupportRate"]


def paired(left, right, name):
    values, changes = [], []
    for case_id in sorted(left):
        a, b = metric(left[case_id], name), metric(right[case_id], name)
        if a is None or b is None:
            continue
        values.append((a, b))
        if not math.isclose(a, b, abs_tol=1e-12):
            changes.append({"caseId": case_id, "baseline": a, "candidate": b, "delta": b - a})
    result = {"pairedCount": len(values), "totalCases": len(left), "unpaired": len(left) - len(values),
              "wins": sum(b > a + 1e-12 for a, b in values), "losses": sum(b < a - 1e-12 for a, b in values),
              "ties": sum(math.isclose(a, b, abs_tol=1e-12) for a, b in values), "changes": changes}
    if values:
        result.update(baseline=sum(a for a, _ in values) / len(values), candidate=sum(b for _, b in values) / len(values))
        result["delta"] = result["candidate"] - result["baseline"]
    return result


def validate_policy(policy):
    if set(policy) - {"maxDrops", "maxP95LatencyRatio", "maxGenerationTokenRatio", "notice"}:
        raise ValueError("Unknown regression policy fields")
    if not isinstance(policy.get("maxDrops", {}), dict) or set(policy.get("maxDrops", {})) - set(METRICS):
        raise ValueError("Unknown regression metric")
    for value in policy.get("maxDrops", {}).values():
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0:
            raise ValueError("Metric drop budgets must be finite and nonnegative")
    for name in ["maxP95LatencyRatio", "maxGenerationTokenRatio"]:
        value = policy.get(name)
        if value is not None and (isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0):
            raise ValueError("Ratio budgets must be finite and positive")


CONFIG_CHANGES = {'retrieval.bm25Weight', 'retrieval.knnWeight', 'options.rrfK', 'options.rerank'}


def configuration_changes(left, right, allowed=()):
    if set(allowed) - CONFIG_CHANGES:
        raise ValueError('Unsupported configuration-change declaration')
    if left == right:
        return []
    if not allowed:
        raise ValueError('Runs are not comparable: signature')
    a, b = json.loads(left), json.loads(right); changes = []
    for path in sorted(set(allowed)):
        section, key = path.split('.')
        x, y = a[section].pop(key), b[section].pop(key)
        if x != y:
            changes.append({'field': path, 'baseline': x, 'candidate': y})
    if a != b:
        raise ValueError('Runs differ outside declared retrieval parameters')
    return changes


def compare(left, right, output, policy=None, allow_code_change=False, allowed_config_changes=()):
    policy = policy or {}
    validate_policy(policy)
    for field in ["datasetHash", "corpusFingerprint", "corpusVersion", "owner", "generationParameters"]:
        if left["capture"].get(field) != right["capture"].get(field):
            raise ValueError("Runs are not comparable: " + field)
    if left["preflight"]["permissionFingerprint"] != right["preflight"]["permissionFingerprint"]:
        raise ValueError("Runs are not comparable: permissions")
    if set(left["rows"]) != set(right["rows"]):
        raise ValueError("Runs are not comparable: case IDs")
    if not allow_code_change and left["capture"]["codeVersion"] != right["capture"]["codeVersion"]:
        raise ValueError("Runs are not comparable: code version (explicit --allow-code-change required)")
    changes = configuration_changes(left['signature'], right['signature'], allowed_config_changes)
    if 'options.rerank' in allowed_config_changes:
        for run in [left, right]:
            config = json.loads(run['signature']).get('retrieval', {}).get('rerank') or {}
            if not {'model', 'topN', 'maxDocChars', 'timeoutMs', 'serviceFingerprint'}.issubset(config):
                raise ValueError('Rerank ablation requires a recorded model, window, timeout and service fingerprint')
    for field in ["generation", "supportVersion", "judges"]:
        if left.get(field) != right.get(field):
            raise ValueError("Runs are not comparable: " + field)
    report = {"baseline": str(left["root"]), "candidate": str(right["root"]), "allowCodeChange": allow_code_change,
              "datasetHash": left["capture"]["datasetHash"], "comparability": "MATCHED",
              "allowedConfigChanges": sorted(set(allowed_config_changes)), "configurationChanges": changes,
              "notice": "Paired exploratory comparison on this frozen sample; a single run does not establish stable model gains or production acceptance.",
              "metrics": {name: paired(left["rows"], right["rows"], name) for name in METRICS}, "measurements": {}, "gates": []}
    for name in ["pipelineLatencyMs", "generationUsage"]:
        report["measurements"][name] = {"baseline": left["summary"].get("measurements", {}).get(name), "candidate": right["summary"].get("measurements", {}).get(name)}
    for name, maximum in policy.get("maxDrops", {}).items():
        values = report["metrics"][name]
        state = "INCONCLUSIVE" if values["unpaired"] else "PASS" if values["delta"] >= -maximum - 1e-12 else "FAIL"
        report["gates"].append({"metric": name, "maxDrop": maximum, "status": state})
    for setting, name, field in [("maxP95LatencyRatio", "pipelineLatencyMs", "p95"), ("maxGenerationTokenRatio", "generationUsage", "totalTokens")]:
        if policy.get(setting) is None:
            continue
        values = report["measurements"][name]; a, b = values["baseline"] or {}, values["candidate"] or {}
        available = a.get(field) is not None and b.get(field) is not None and a[field] > 0
        available = available and (a.get("reportedCalls") == b.get("reportedCalls") == len(left["rows"]) if name == "generationUsage" else a.get("count") == b.get("count") == len(left["rows"]))
        ratio = b[field] / a[field] if available else None
        report["gates"].append({"metric": name, "maximumRatio": policy[setting], "observedRatio": ratio,
                                "status": "INCONCLUSIVE" if not available else "PASS" if ratio <= policy[setting] else "FAIL"})
    health = {side: sum(not row.get("deterministic", {}).get("generationComplete", False) for row in run["rows"].values()) for side, run in [("baseline", left), ("candidate", right)]}
    report["incompleteAnswers"] = health
    quality_configured = bool(report['gates'])
    if 'options.rerank' in allowed_config_changes:
        report["rerankExecution"] = {side: run.get("rerankHealth") for side, run in [("baseline", left), ("candidate", right)]}
        for side, run in [("baseline", left), ("candidate", right)]:
            execution = run.get("rerankHealth") or {}
            if json.loads(run['signature'])['options']['rerank']:
                complete = execution.get('requested') == len(run['rows']) and execution.get('statuses', {}).get('SUCCEEDED', 0) == len(run['rows'])
                report['gates'].append({'metric': side + '.rerankExecution', 'status': 'PASS' if complete else 'INCONCLUSIVE',
                                        'notice': 'Every case must actually rerank to establish a pure RRF + rerank comparison; fallback counts remain visible.'})
    states = [gate["status"] for gate in report["gates"]]
    report["gateStatus"] = "NOT_CONFIGURED" if not quality_configured else "FAIL" if "FAIL" in states else "INCONCLUSIVE" if "INCONCLUSIVE" in states or any(health.values()) else "PASS"
    output.mkdir(parents=True, exist_ok=False)
    (output / "comparison.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    lines = ["# yeRAG 配对回归对照", "", report["notice"], "", f"门禁状态：{report['gateStatus']}（开发策略，不代表生产上线标准）。", "",
             "| 指标 | 配对题数 | Baseline | Candidate | 差值 | 改善/退化/持平 |", "|---|---:|---:|---:|---:|---|"]
    for name, value in report["metrics"].items():
        numbers = [f"{value[k]:.4f}" if k in value else "N/A" for k in ["baseline", "candidate", "delta"]]
        lines.append(f"| {name} | {value['pairedCount']} | {' | '.join(numbers)} | {value['wins']}/{value['losses']}/{value['ties']} |")
    lines.extend(["", "P95 为 nearest-rank Pipeline 延迟，不含离线 Judge；Token 仅计供应商报告值，不推算金额。逐题变化、缺测与门禁阈值见 comparison.json。"])
    if 'options.rerank' in allowed_config_changes:
        lines.extend(['', '实际重排执行：' + json.dumps(report['rerankExecution'], ensure_ascii=False)])
    (output / "comparison.md").write_text("\n".join(lines) + "\n")
    return report


def matrix(args):
    rerank_ablation = getattr(args, 'action', 'matrix') == 'rerank'
    if not os.environ.get("AI_RAG_EVAL_IDENTITY"):
        raise ValueError("AI_RAG_EVAL_IDENTITY is required; credentials belong in private runtime configuration")
    if not 1 <= args.repeats <= 5:
        raise ValueError("repeats must be in 1..5")
    policy = read(args.policy) if args.policy else {}
    validate_policy(policy)
    root = args.output.resolve()
    if args.resume:
        previous = read(root / "matrix.json")
        if previous["repeats"] != args.repeats or previous["status"] == "COMPLETED":
            raise ValueError("Resume requires the same repetition count and an unfinished matrix")
        if "policy" in previous and previous["policy"] != policy:
            raise ValueError("Resumed regression policy differs")
        if previous.get('kind', 'matrix') != ('rerank' if rerank_ablation else 'matrix'):
            raise ValueError('Resumed experiment kind differs')
    else:
        root.mkdir(parents=True, exist_ok=False, mode=0o700)
    env = dict(os.environ)
    if env.get("RAG_EVAL_SKIP_BUILD") != "1":
        with (root / "build.log").open("w") as log:
            subprocess.run(["mvn", "-q", "-DskipTests", "compile", "dependency:build-classpath", "-Dmdep.outputFile=target/rag-eval-classpath.txt"], stdout=log, stderr=subprocess.STDOUT, check=True)
    env["RAG_EVAL_SKIP_BUILD"] = "1"
    state = {"status": "RUNNING", "repeats": args.repeats, "runs": [], "policy": policy,
             "kind": 'rerank' if rerank_ablation else 'matrix',
             "scope": "Sequential RRF vs RRF + rerank; identical query/options except rerank" if rerank_ablation else "Sequential BM25/Vector/Hybrid; no rewrite or rerank; balanced order for repeated runs"}
    def save():
        (root / "matrix.tmp").write_text(json.dumps(state, indent=2) + "\n"); (root / "matrix.tmp").replace(root / "matrix.json")
    save()
    try:
        for repeat in range(1, args.repeats + 1):
            variants = ["rrf", "rrf-rerank"] if rerank_ablation else ["BM25", "VECTOR", "HYBRID"]
            if not repeat % 2:
                variants.reverse()
            for variant in variants:
                strategy = 'HYBRID' if rerank_ablation else variant
                destination = root / f"{variant.lower()}-{repeat:02}"
                command = ["scripts/rag-eval.sh", "run", str(args.dataset), str(destination), *args.spring_arg,
                           f"--ai.rag.eval.baseline={str(not rerank_ablation).lower()}", f"--ai.rag.eval.recall-strategy={strategy}",
                           f"--ai.rag.eval.support-enabled={str(args.support).lower()}"]
                if rerank_ablation:
                    enabled = str(variant == 'rrf-rerank').lower()
                    command += ['--ai.retrieval.mode=rrf', '--ai.rag.rewrite-enabled=false', '--ai.retrieval.rerank.enabled=true',
                                '--ai.rag.rerank-enabled=' + enabled, '--ai.rag.eval.require-rerank=' + enabled]
                if destination.exists():
                    if not args.resume:
                        raise ValueError("Run directory already exists")
                    # Only a complete, hash-verified run can be reused. A partial run
                    # must remain intact; never silently regenerate its answers.
                    run = load_run(destination)
                    if run["capture"].get("experiment", {}).get("supportEnabled") != args.support:
                        raise ValueError("Resumed support configuration differs")
                else:
                    log_path = root / f"{destination.name}.log"
                    attempt = 1
                    while log_path.exists():
                        attempt += 1
                        log_path = root / f"{destination.name}.attempt-{attempt:02}.log"
                    with log_path.open("x") as log:
                        subprocess.run(command, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
                run = load_run(destination)
                if run["capture"].get("experiment", {}).get("recallStrategy") != strategy:
                    raise ValueError("Baseline strategy does not match actual capture")
                if rerank_ablation:
                    options = json.loads(run['signature'])['options']
                    if options['rewrite'] or options['rerank'] != (variant == 'rrf-rerank') or run['capture']['experiment']['baseline']:
                        raise ValueError('Rerank variant does not match actual capture')
                state["runs"].append({"strategy": strategy, "variant": variant, "repeat": repeat, "directory": str(destination), "summary": run["summary"]})
                save(); print(f"COMPLETED {destination.name}", flush=True)
        for repeat in range(1, args.repeats + 1):
            candidate = load_run(root / f"{'rrf-rerank' if rerank_ablation else 'hybrid'}-{repeat:02}")
            for baseline in (["rrf"] if rerank_ablation else ["bm25", "vector"]):
                left = load_run(root / f"{baseline}-{repeat:02}")
                destination = root / f"{baseline}-vs-{'rerank' if rerank_ablation else 'hybrid'}-{repeat:02}"
                allowed = ['options.rerank'] if rerank_ablation else []
                if args.resume and destination.exists():
                    with tempfile.TemporaryDirectory(prefix="yerag-comparison-") as temporary:
                        check = Path(temporary) / "report"
                        compare(left, candidate, check, policy, allowed_config_changes=allowed)
                        for name in ["comparison.json", "comparison.md"]:
                            if (destination / name).read_bytes() != (check / name).read_bytes():
                                raise ValueError("Resumed comparison changed; existing report remains intact")
                else:
                    compare(left, candidate, destination, policy, allowed_config_changes=allowed)
        state["status"] = "COMPLETED"; save()
    except Exception as error:
        state["status"] = "FAILED"; state["errorType"] = type(error).__name__; save(); raise


def main():
    parser = argparse.ArgumentParser(description=__doc__); sub = parser.add_subparsers(dest="action", required=True)
    compare_parser = sub.add_parser("compare"); compare_parser.add_argument("baseline", type=Path); compare_parser.add_argument("candidate", type=Path); compare_parser.add_argument("output", type=Path)
    compare_parser.add_argument("--policy", type=Path); compare_parser.add_argument("--allow-code-change", action="store_true")
    compare_parser.add_argument('--allow-config-change', action='append', default=[], choices=sorted(CONFIG_CHANGES))
    for action in ['matrix', 'rerank']:
        matrix_parser = sub.add_parser(action); matrix_parser.add_argument("dataset", type=Path); matrix_parser.add_argument("output", type=Path)
        matrix_parser.add_argument("--repeats", type=int, default=1); matrix_parser.add_argument("--support", action="store_true"); matrix_parser.add_argument("--policy", type=Path)
        matrix_parser.add_argument("--spring-arg", action="append", default=[])
        matrix_parser.add_argument("--resume", action="store_true", help="Reuse only complete hash-verified runs in an unfinished matrix; partial runs are never overwritten")
    args = parser.parse_args()
    if args.action in {'matrix', 'rerank'}:
        matrix(args)
    else:
        report = compare(load_run(args.baseline), load_run(args.candidate), args.output, read(args.policy) if args.policy else {}, args.allow_code_change, args.allow_config_change)
        print("Comparison:", report["gateStatus"])
        if report["gateStatus"] in {"FAIL", "INCONCLUSIVE"}:
            raise SystemExit(1)


if __name__ == "__main__":
    main()
