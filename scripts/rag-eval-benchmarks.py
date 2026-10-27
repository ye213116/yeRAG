#!/usr/bin/env python3
"""Task-specific official Chinese QA and TREC/BEIR scoring; never maps evidence Hit@K to qrels Recall."""
import argparse
import csv
import hashlib
import importlib.metadata
import json
import math
from pathlib import Path
import subprocess
import sys
import tempfile

DUREADER_URL = 'https://raw.githubusercontent.com/baidu/DuReader/c625076b06da8f56d59f19c41c73bd580a98a347/DuReader-Robust/evaluate.py'
DUREADER_SHA256 = 'f04d391a690147e6842d261ca4e878737a6e5936dcdbe091626fda4f19dcbb04'
BEIR_REFERENCE = 'https://github.com/beir-cellar/beir/blob/ef83d29307061c65d04b035b4f4e7c18bd8374af/beir/retrieval/evaluation.py'


def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()


def write(output, result):
    with output.open('x') as stream: json.dump(result, stream, ensure_ascii=False, indent=2); stream.write('\n')
    print('SCORED', result['task'], 'queries=', result['count'])


def dureader(reference, predictions, scorer, setting):
    if sha(scorer) != DUREADER_SHA256:
        raise ValueError('Expected the pinned official DuReader-Robust scorer; do not substitute English token F1')
    data = json.loads(reference.read_text()); answers = json.loads(predictions.read_text())
    ids = [str(qa['id']) for paragraph in data['data'][0]['paragraphs'] for qa in paragraph['qas']]
    if not ids or len(ids) != len(set(ids)): raise ValueError('Unique nonempty source question IDs required')
    if not set(answers).issubset(ids) or any(not isinstance(a, str) for a in answers.values()):
        raise ValueError('Predictions must be original full answer strings keyed by source question ID')
    missing = set(ids) - answers.keys()
    with tempfile.TemporaryDirectory() as temporary:
        complete = Path(temporary) / 'predictions.json'
        complete.write_text(json.dumps({key: answers.get(key, '') for key in ids}, ensure_ascii=False))
        # Keep the upstream executable unchanged, including Chinese normalization and longest contiguous match F1.
        run = subprocess.run([sys.executable, str(scorer.resolve()), str(reference.resolve()), str(complete)],
                             text=True, capture_output=True, check=True)
    metrics = json.loads(run.stdout)
    return {'task': 'DuReader-Robust', 'setting': setting, 'count': len(ids), 'missingPredictionsCountedAsEmpty': len(missing),
            'metrics': metrics, 'answerSurface': 'FULL_GENERATED_ANSWER_NO_EXTRACTION',
            'source': {'officialScorer': DUREADER_URL, 'scorerSha256': sha(scorer),
                       'referenceSha256': sha(reference), 'predictionsSha256': sha(predictions)},
            'notice': 'Native Chinese EM/F1 only. Dataset setting and corpus coverage are separate; no automatic human approval.'}


def qrels(path):
    result = {}
    with path.open() as stream:
        reader = csv.DictReader(stream, delimiter='\t')
        if reader.fieldnames != ['query-id', 'corpus-id', 'score']: raise ValueError('Expected BEIR qrels TSV header')
        for row in reader:
            qid, doc = row['query-id'], row['corpus-id']
            if doc in result.get(qid, {}): raise ValueError('Duplicate qrel')
            result.setdefault(qid, {})[doc] = int(row['score'])
    if not result: raise ValueError('No judged queries')
    return result


def beir(reference, run_file, ks, setting):
    import pytrec_eval
    judgments = qrels(reference); results = json.loads(run_file.read_text())
    if not set(results).issubset(judgments): raise ValueError('Run contains unjudged query IDs')
    filtered = {}
    for query, docs in results.items():
        if any(not isinstance(score, (float, int)) or not math.isfinite(score) for score in docs.values()): raise ValueError('Nonfinite ranking score')
        # Match BEIR's default exclusion of identical query/document IDs; scores are ranks after chunk deduplication.
        filtered[query] = {doc: float(score) for doc, score in docs.items() if doc != query}
    metrics = {f'{metric}.{",".join(map(str, ks))}' for metric in ['ndcg_cut', 'map_cut', 'recall', 'P']}
    evaluator = pytrec_eval.RelevanceEvaluator(judgments, metrics)
    measured = evaluator.evaluate(filtered)
    # Include missing queries as zero. BEIR's averaging over returned scores alone can silently drop missing runs.
    per_query = {query: measured.get(query, {f'{metric}_{k}': 0.0 for metric in ['ndcg_cut', 'map_cut', 'recall', 'P'] for k in ks}) for query in judgments}
    aggregate = {key: sum(row[key] for row in per_query.values()) / len(judgments) for key in next(iter(per_query.values()))}
    return {'task': 'BEIR-qrels', 'setting': setting, 'count': len(judgments), 'k': ks,
            'missingOrEmptyQueries': sum(not filtered.get(query) for query in judgments),
            'metrics': aggregate, 'perQuery': per_query, 'denominator': 'ALL_JUDGED_QUERIES_MISSING_RESULTS_ZERO',
            'source': {'officialReference': BEIR_REFERENCE, 'scorer': 'pytrec-eval-terrier',
                       'scorerVersion': importlib.metadata.version('pytrec-eval-terrier'), 'qrelsSha256': sha(reference), 'runSha256': sha(run_file)},
            'notice': 'Document-level qrels metrics with TREC gain semantics. No evidence-span metric substitution. Partial corpus runs must be explicitly labelled subset experiments.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__); sub = parser.add_subparsers(dest='task', required=True)
    p = sub.add_parser('dureader'); p.add_argument('reference', type=Path); p.add_argument('predictions', type=Path); p.add_argument('output', type=Path); p.add_argument('--scorer', required=True, type=Path); p.add_argument('--setting', required=True)
    p = sub.add_parser('beir'); p.add_argument('qrels', type=Path); p.add_argument('run', type=Path); p.add_argument('output', type=Path); p.add_argument('--k', default='1,3,5,10'); p.add_argument('--setting', required=True)
    args = parser.parse_args()
    if args.task == 'dureader': result = dureader(args.reference, args.predictions, args.scorer, args.setting)
    else:
        ks = sorted(set(map(int, args.k.split(','))))
        if not ks or min(ks) < 1 or max(ks) > 10000: parser.error('k must be within 1..10000')
        result = beir(args.qrels, args.run, ks, args.setting)
    write(args.output, result)


if __name__ == '__main__': main()
