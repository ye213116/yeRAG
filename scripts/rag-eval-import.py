#!/usr/bin/env python3
"""Import public labels without inventing evidence or human approvals (stdlib only)."""
import argparse
import hashlib
import json
from pathlib import Path


def digest(data, algorithm="sha256"):
    return hashlib.new(algorithm, data).hexdigest()


def read_records(path):
    if path.suffix == ".parquet":
        try:
            import pyarrow.parquet as parquet
        except ImportError:
            raise SystemExit("Parquet import requires pyarrow; JSON/JSONL import uses only the standard library") from None
        return parquet.read_table(path).to_pylist()
    text = path.read_text(encoding="utf-8")
    return json.loads(text) if text.lstrip().startswith("[") else [json.loads(line) for line in text.splitlines() if line.strip()]


def native_hotpot(row):
    """Accept original JSON and the official Hugging Face column representation."""
    row = dict(row)
    row["_id"] = str(row.get("_id", row.get("id", "")))
    if isinstance(row.get("context"), dict):
        row["context"] = list(map(list, zip(row["context"]["title"], row["context"]["sentences"], strict=True)))
    if isinstance(row.get("supporting_facts"), dict):
        facts = row["supporting_facts"]
        row["supporting_facts"] = list(map(list, zip(facts["title"], facts["sent_id"], strict=True)))
    return row


def import_dataset(args):
    source_bytes = args.input.read_bytes()
    rows = read_records(args.input)
    if args.format == "hotpotqa":
        rows = [native_hotpot(row) for row in rows]
    id_field = "_id" if args.format == "hotpotqa" else "id"
    ids = [str(row[id_field]) for row in rows]
    if len(set(ids)) != len(ids) or any(not value for value in ids):
        raise ValueError("Source IDs must be nonempty and unique")
    key = lambda row: digest(f'{args.seed}:{row[id_field]}'.encode())
    explicit = getattr(args, "selected_ids", None)
    if explicit is not None:
        if len(explicit) != args.count or len(set(explicit)) != len(explicit) or not set(explicit).issubset(set(ids)):
            raise ValueError("Explicit IDs must be unique source IDs matching count")
        selected = sorted((row for row in rows if str(row[id_field]) in set(explicit)), key=key)
    elif args.format == "hotpotqa":
        if args.count % 2:
            raise ValueError("HotpotQA sample count must be even (bridge/comparison strata)")
        selected = []
        for kind in ("bridge", "comparison"):
            group = sorted((row for row in rows if row.get("type") == kind), key=key)
            if len(group) < args.count // 2:
                raise ValueError(f"Insufficient {kind} source records")
            selected.extend(group[:args.count // 2])
        selected.sort(key=key)
    else:
        if len(rows) < args.count:
            raise ValueError("Insufficient source records")
        selected = sorted(rows, key=key)[:args.count]
    documents, bodies, cases = {}, {}, []

    def document(body, title):
        data = body.encode("utf-8")
        doc_id = "doc-" + digest(data)
        if doc_id not in documents:
            documents[doc_id] = {"id": doc_id, "path": f"documents/{doc_id}.txt", "title": title,
                                 "visibility": "public", "sha256": digest(data), "fileMd5": digest(data, "md5")}
            bodies[doc_id] = body
        return doc_id

    if args.format == "ultrarag" and args.corpus:
        for row in read_records(args.corpus):
            doc_id = document(row["contents"], str(row["id"]))
            documents[doc_id].setdefault("sourceIds", []).append(str(row["id"]))
    for number, row in enumerate(selected):
        if args.format == "ultrarag" and any(field in row for field in ("choices", "image", "image_id", "image_path")):
            raise ValueError("Generic UltraRAG importer supports text QA only; choices/images require a task-specific adapter")
        source_id = str(row[id_field])
        answers = [row["answer"]] if args.format == "hotpotqa" else row["golden_answers"]
        if not isinstance(answers, list) or not answers or any(not isinstance(a, str) or not a.strip() for a in answers):
            raise ValueError(f"Invalid reference answers: {source_id}")
        case = {"id": f"{args.format}-{source_id}", "type": "answerable", "question": row["question"],
                "referenceAnswer": answers[0], "referenceAnswers": answers, "keyPoints": [], "evidenceUnits": [],
                "sourceRecordIndex": number, "sourceId": source_id,
                "evidenceAnnotation": "supporting-sentence" if args.format == "hotpotqa" else "unavailable"}
        if args.format == "hotpotqa":
            contexts = {}
            for title, sentences in row["context"]:
                if title in contexts:
                    raise ValueError(f"Ambiguous context title: {source_id}/{title}")
                contexts[title] = (document(title + "\n\n" + "".join(sentences), title), sentences)
            seen = set()
            for title, index in row["supporting_facts"]:
                if (title, index) in seen:
                    continue
                seen.add((title, index))
                if title not in contexts or not isinstance(index, int) or index < 0 or index >= len(contexts[title][1]):
                    raise ValueError(f"Missing supporting sentence: {source_id}/{title}/{index}")
                doc_id, sentences = contexts[title]
                quote = sentences[index]
                if not quote.strip():
                    raise ValueError("Empty supporting sentence")
                case["evidenceUnits"].append({"id": f"u{len(seen)}", "sourceTitle": title, "sentenceIndex": index,
                                              "alternatives": [{"document": doc_id, "quote": quote}]})
            if not case["evidenceUnits"]:
                raise ValueError("HotpotQA requires supporting facts")
        cases.append(case)
    # Finish all validation before creating output; never overwrite an existing dataset.
    args.output.mkdir(parents=True, exist_ok=False)
    (args.output / "documents").mkdir()
    for doc_id, body in bodies.items():
        (args.output / documents[doc_id]["path"]).write_text(body, encoding="utf-8")
    snapshot = (json.dumps(selected, ensure_ascii=False, indent=2) + "\n").encode()
    (args.output / "source.json").write_bytes(snapshot)
    corpus_hash = digest("\n".join(sorted(documents)).encode())
    manifest = {"schemaVersion": 3, "datasetVersion": f"{args.format}-{args.count}-seed{args.seed}-v1",
                "corpusVersion": "public-subset-" + corpus_hash[:16], "expectedCount": len(cases),
                "annotationsStatus": "PUBLIC_SOURCE_MAPPED_NOT_HUMAN_REVIEWED",
                "benchmark": {"dataset": args.dataset_name or args.format, "language": args.language, "split": args.split,
                              "setting": "shared-candidate-subset", "answerStyle": "short",
                              "scorer": "hotpotqa-answer-v1" if args.format == "hotpotqa" else "unavailable"},
                "source": {"format": args.format, "url": args.source_url, "revision": args.revision,
                           "inputSha256": digest(source_bytes), "path": "source.json", "sha256": digest(snapshot),
                           "license": "CC-BY-SA-4.0" if args.format == "hotpotqa" else "CHECK_UPSTREAM",
                           "sampling": {"algorithm": "sha256-seed-id-stratified" if args.format == "hotpotqa" else "sha256-seed-id",
                                        "seed": args.seed, "population": len(rows), "selectedIds": [str(r[id_field]) for r in selected]}},
                "documents": sorted(documents.values(), key=lambda d: d["id"])}
    if args.corpus:
        manifest["source"]["corpusInputSha256"] = digest(args.corpus.read_bytes())
    (args.output / "dataset.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (args.output / "cases.jsonl").write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in cases), encoding="utf-8")
    (args.output / "SOURCE.md").write_text(
        f"# Public dataset provenance\n\nSource: {args.source_url}\n\nRevision: {args.revision}\n\n"
        f"License: {manifest['source']['license']}. Retain upstream attribution and license terms.\n\n" +
        ("HotpotQA attribution: Yang, Qi, Zhang et al., HotpotQA: A Dataset for Diverse, Explainable Multi-hop Question Answering, EMNLP 2018. https://hotpotqa.github.io/\n\nLicense: https://creativecommons.org/licenses/by-sa/4.0/\n\n" if args.format == "hotpotqa" else "Check each upstream dataset and corpus license before redistribution.\n\n") +
        "Modifications: deterministic question sampling, candidate deduplication into a shared corpus, text export and supporting-sentence mapping. "
        "This is a local subset experiment, not the official distractor/fullwiki setting. Public source labels are not human approval.\n",
        encoding="utf-8")
    print(f"IMPORTED cases={len(cases)} documents={len(documents)} output={args.output}")
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("format", choices=["hotpotqa", "ultrarag"])
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--corpus", type=Path, help="UltraRAG id/contents JSONL; optional for offline-only answer labels")
    parser.add_argument("--count", type=int, default=50)
    parser.add_argument("--seed", type=int, default=20261026)
    parser.add_argument("--split", default="validation")
    parser.add_argument("--dataset-name", help="Name of the source QA dataset when importing the UltraRAG wrapper")
    parser.add_argument("--language", default="en")
    parser.add_argument("--source-url", required=True)
    parser.add_argument("--revision", required=True)
    args = parser.parse_args()
    if args.count < 1 or args.count > 10000:
        parser.error("count must be in 1..10000")
    import_dataset(args)


if __name__ == "__main__":
    main()
