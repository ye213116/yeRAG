#!/usr/bin/env python3
"""Aggregate provider-reported usage from frozen model traces; optional explicit rates are never a total bill."""
import argparse
from decimal import Decimal
import hashlib
import json
from pathlib import Path


def usage_ledger(root, rates=None):
    models = {}; proofs = {}; seen = set(); missing_children = set()
    documents = [json.loads(path.read_text()) for path in sorted(root.glob('*.json'))]
    all_ids = {row.get('runId') for row in documents}
    for row in documents:
        for event in row.get('events', []):
            if event['name'] == 'child' and event['data']['runId'] not in all_ids: missing_children.add(event['data']['runId'])
        if row.get('kind') != 'model-call': continue
        run_id = row['runId']
        if run_id in seen: raise ValueError('Duplicate model run')
        seen.add(run_id)
        model = next(e['data']['model'] for e in row['events'] if e['name'] == 'request')
        group = models.setdefault(model, {'calls': 0, 'reported': 0, 'missingUsage': 0, 'promptTokens': 0, 'completionTokens': 0, 'totalTokens': 0})
        group['calls'] += 1
        raw = next((e['data'].get('usage') for e in row['events'] if e['name'] == 'generation'), None)
        if raw is None: raw = next((e['data'].get('usage') for e in row['events'] if e['name'] == 'response'), None)
        fields = ['prompt_tokens', 'completion_tokens', 'total_tokens']
        if not raw or any(type(raw.get(key)) is not int or raw[key] < 0 for key in fields):
            group['missingUsage'] += 1; continue
        group['reported'] += 1
        for native, output in zip(fields, ['promptTokens', 'completionTokens', 'totalTokens']): group[output] += raw[native]
    for path in sorted(root.glob('*.json')): proofs[path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
    if rates is not None:
        if not rates.get('currency') or not rates.get('source') or not rates.get('effectiveAt'): raise ValueError('Currency, rate source and effective date required')
        for model, group in models.items():
            price = rates.get('models', {}).get(model)
            if price is None: group['costStatus'] = 'RATE_UNAVAILABLE'; continue
            prompt, completion = [Decimal(str(price[key])) for key in ['inputPerMillion', 'outputPerMillion']]
            if not prompt.is_finite() or not completion.is_finite() or min(prompt, completion) < 0: raise ValueError('Nonnegative finite rates required')
            group['reportedUsageCost'] = str((prompt * group['promptTokens'] + completion * group['completionTokens']) / Decimal(1000000))
            group['costStatus'] = 'PARTIAL_RECORDED_USAGE_ONLY' if group['missingUsage'] or missing_children else 'RECORDED_MODEL_CALLS_ONLY'
    return {'version': 'yerag-provider-usage-ledger-v1', 'models': models, 'traceSha256': proofs,
            'missingChildArtifacts': sorted(missing_children), 'rates': rates, 'currency': rates.get('currency') if rates else None,
            'costStatus': 'EXPLICIT_RATE_MODEL_SUBTOTAL_ONLY' if rates else 'USAGE_ONLY_NO_RATES_SUPPLIED',
            'totalBillAvailable': False,
            'excluded': ['embedding', 'rerank', 'storage', 'network', 'cache pricing differences not represented by supplied rates', 'unrecorded or missing usage'],
            'notice': 'Provider usage is separate from character/token estimates. Optional explicit rates produce model-call subtotals, not an actual complete invoice.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('artifacts', type=Path); parser.add_argument('output', type=Path); parser.add_argument('--rates', type=Path)
    args = parser.parse_args(); rates = json.loads(args.rates.read_text()) if args.rates else None
    result = usage_ledger(args.artifacts, rates)
    with args.output.open('x') as stream: json.dump(result, stream, ensure_ascii=False, indent=2); stream.write('\n')
    print(result['costStatus'], sum(row['calls'] for row in result['models'].values()), 'recorded model calls')


if __name__ == '__main__': main()
