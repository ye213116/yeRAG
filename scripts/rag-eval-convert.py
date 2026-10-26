#!/usr/bin/env python3
"""One-time migration template. Never invent evidence quotes or carry historical Judge labels."""
import json
from pathlib import Path
import sys


def convert(item, number):
    result = {"id": item.get("id", item.get("caseId", f"legacy-{number}")), "type": "answerable", "question": item.get("question", ""), "referenceAnswer": item.get("referenceAnswer", ""), "keyPoints": item.get("keyPoints") or ["<需人工确认答案要点>"], "evidenceUnits": []}
    if item.get("retrievalExpectation") == "miss":
        result["type"] = "acl" if result["id"].startswith("acl-") else "unanswerable"
        if result["type"] == "acl":
            result["forbiddenDocuments"] = ["<需映射文档ID>"]
    else:
        phrases = item.get("expectedEvidence") or []
        if phrases:
            # Legacy ANY-phrase labels become alternatives of one unit; human review must split necessary units.
            result["evidenceUnits"] = [{"id": "u1", "alternatives": [{"document": "<需映射文档ID>", "quote": p} for p in phrases]}]
        else:
            for i, chunk in enumerate(item.get("goldChunkIds") or ["unknown"], 1):
                result["evidenceUnits"].append({"id": f"u{i}", "alternatives": [{"document": "<需映射文档ID>", "quote": "<需从原文人工摘录必要证据>"}], "legacyChunkHint": chunk})
    result["migrationStatus"] = "DRAFT_REQUIRES_MANUAL_ANNOTATION"
    return result


def main():
    if len(sys.argv) != 3:
        raise SystemExit("Usage: rag-eval-convert.py INPUT_JSON_OR_JSONL NEW_OUTPUT_JSONL")
    source = Path(sys.argv[1]).read_text()
    records = json.loads(source) if source.lstrip().startswith("[") else [json.loads(line) for line in source.splitlines() if line.strip() and not line.lstrip().startswith("#")]
    rows = [convert(record, i) for i, record in enumerate(records, 1)]
    with Path(sys.argv[2]).open("x") as out:
        out.write("# Migration template only: resolve document IDs and necessary quotes, review annotations, and version dataset.json.\n")
        out.writelines(json.dumps(row, ensure_ascii=False) + "\n" for row in rows)
    print(f"Converted {len(rows)} DRAFT templates; historical run IDs and model assessments discarded")


if __name__ == "__main__":
    main()
