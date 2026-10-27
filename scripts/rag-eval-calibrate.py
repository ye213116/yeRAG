#!/usr/bin/env python3
"""Prepare frozen human-review materials; score explicit human decisions separately from old reports."""
import argparse
import hashlib
import html
import importlib.util
import json
from pathlib import Path


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def lines(path):
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip() and not line.lstrip().startswith('#')]


def prepare(run, public_review, output):
    spec = importlib.util.spec_from_file_location('experiment', Path(__file__).with_name('rag-eval-experiment.py'))
    experiment = importlib.util.module_from_spec(spec); spec.loader.exec_module(experiment)
    validated = experiment.load_run(run)
    cases = {row['id']: row for row in lines(run / 'dataset/cases.jsonl')}
    results = {row['id']: row for row in lines(run / 'reviewed-report/results.jsonl')}
    entries = lines(run / 'report/ragas.jsonl')
    index = {row['caseId']: entries[row['line'] - 1] for row in lines(run / 'report/ragas-index.jsonl') if row['status'] == 'EXPORTED'}
    provenance = json.loads((run / 'reviewed-report/provenance.json').read_text())
    for filename, field in [('results.jsonl', 'resultsSha256'), ('summary.json', 'summarySha256')]:
        if hashlib.sha256((run / 'reviewed-report' / filename).read_bytes()).hexdigest() != provenance[field]:
            raise ValueError('Reviewed report input changed')
    items = []
    for row in [item for item in results.values() if item['reviewRequired']]:
        key = row['id']; sample = cases[key]; exported = index[key]
        items.append({'id': key, 'question': sample['question'], 'referenceAnswers': sample.get('referenceAnswers', [sample['referenceAnswer']]),
                      'necessaryEvidence': sample['evidenceUnits'], 'answerKeyPoints': sample.get('answerKeyPoints'),
                      'answer': exported['response'], 'actualContexts': exported['retrieved_contexts'],
                      'machineVerdict': results[key].get('effectiveVerdict'), 'datasetHash': validated['capture']['datasetHash'],
                      'pipelineRunId': row['pipelineRunId']})
    for row in json.loads(public_review.read_text())['cases']:
        items.append({'id': row['caseId'], 'question': row['question'], 'referenceAnswers': row['referenceAnswers'],
                      'necessaryEvidence': row['necessaryEvidence'], 'observations': row['observations'],
                      'technicalConclusion': row['conclusion'], 'machineVerdict': None})
    packet = {'version': 'yerag-human-calibration-v1', 'state': 'PENDING_HUMAN_REVIEW',
              'notice': 'Review labels and answer quality independently. This packet contains no human approvals and does not overwrite historical scores.',
              'items': [dict(item, itemSha256=digest(item)) for item in items]}
    packet_hash = digest(packet)
    output.mkdir(parents=True, exist_ok=False)
    (output / 'packet.json').write_text(json.dumps(packet, ensure_ascii=False, indent=2) + '\n')
    blocks = []
    for item in packet['items']:
        blocks.append(f'<article><h2>{html.escape(item["id"])}</h2><pre>{html.escape(json.dumps(item, ensure_ascii=False, indent=2))}</pre>'
                      '<label>标签/证据充分性 <select class="labels"><option value="">待复核</option><option>valid</option><option>disputed</option></select></label> '
                      '<label>原回答质量 <select class="verdict"><option value="">待复核</option><option>correct</option><option>partial</option><option>wrong</option></select></label>'
                      '<p>理由 <textarea class="reason" rows="3" cols="90"></textarea></p></article>')
    template = '''<!doctype html><html lang="zh"><meta charset="utf-8"><title>yeRAG 人工校准</title>
<style>body{max-width:1100px;margin:2rem auto;font:16px sans-serif}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#f5f5f5;padding:1rem}article{margin:3rem 0}textarea{max-width:95%}label{margin-right:1rem}</style>
<h1>11题人工校准</h1><p>核对问题、参考答案、必要证据与实际入模上下文，再独立判断回答。资料中的技术结论不代替你的决定。公开题含多次原始回答，请在理由中指出对应观察；综合答案判定用于复核线索，不修改原EM分数。</p>
<label>复核人 <input id="reviewer" required></label><button id="export">导出复核决定</button><span id="message"></span>
__BLOCKS__
<script>const packet=__PACKET__;const packetSha256=__HASH__;
document.getElementById('export').onclick=()=>{const reviewer=document.getElementById('reviewer').value.trim();
const decisions=[...document.querySelectorAll('article')].map((a,i)=>({id:packet.items[i].id,itemSha256:packet.items[i].itemSha256,labels:a.querySelector('.labels').value,verdict:a.querySelector('.verdict').value,reason:a.querySelector('.reason').value.trim()}));
if(!reviewer||decisions.some(x=>!x.labels||!x.verdict||!x.reason)){document.getElementById('message').textContent='请填写复核人及每题的两个判断和理由';return;}
const data={packetSha256,reviewer,reviewedAt:new Date().toISOString(),decisions};const link=document.createElement('a');link.href=URL.createObjectURL(new Blob([JSON.stringify(data,null,2)],{type:'application/json'}));link.download='human-decisions.json';link.click();URL.revokeObjectURL(link.href);};</script></html>'''
    (output / 'review.html').write_text(template.replace('__BLOCKS__', '\n'.join(blocks)).replace('__PACKET__', json.dumps(packet, ensure_ascii=False).replace('<', '\\u003c')).replace('__HASH__', json.dumps(packet_hash)))
    print('PENDING_HUMAN_REVIEW', len(items), 'items; packetSha256=' + packet_hash)


def score(packet_path, decisions_path, output):
    packet = json.loads(packet_path.read_text()); reviewed = json.loads(decisions_path.read_text())
    if reviewed.get('packetSha256') != digest(packet):
        raise ValueError('Human decisions are bound to another packet')
    if not reviewed.get('reviewer', '').strip() or not reviewed.get('reviewedAt', '').strip():
        raise ValueError('Named reviewer and actual review timestamp required')
    items = {item['id']: item for item in packet['items']}
    decisions = reviewed['decisions']
    if len({row['id'] for row in decisions}) != len(decisions) or {row['id'] for row in decisions} != items.keys():
        raise ValueError('Each packet item requires exactly one decision')
    matrix = {}; disputes = []; compared = agreed = 0
    for row in decisions:
        item = items[row['id']]
        if row.get('itemSha256') != item['itemSha256'] or row.get('labels') not in {'valid', 'disputed'} or row.get('verdict') not in {'correct', 'partial', 'wrong'} or not row.get('reason', '').strip():
            raise ValueError('Invalid or unbound human decision: ' + row['id'])
        if row['labels'] == 'disputed': disputes.append(row['id'])
        machine = item.get('machineVerdict')
        if machine in {'correct', 'partial', 'wrong'}:
            key = machine + '→' + row['verdict']; matrix[key] = matrix.get(key, 0) + 1
            compared += 1; agreed += machine == row['verdict']
    result = {'state': 'HUMAN_DECISIONS_RECORDED', 'packetSha256': digest(packet), 'reviewer': reviewed['reviewer'],
              'reviewedAt': reviewed['reviewedAt'], 'items': len(decisions), 'judgeCompared': compared,
              'judgeAgreement': agreed / compared if compared else None, 'confusionMatrix': matrix, 'disputedLabels': disputes,
              'historicalLabelsAndScoresChanged': False, 'notice': 'Selected review queue; agreement is not an unbiased estimate over the entire dataset.'}
    with output.open('x') as stream: json.dump(result, stream, ensure_ascii=False, indent=2); stream.write('\n')
    print('RECORDED', len(decisions), 'explicit human decisions')


def main():
    parser = argparse.ArgumentParser(description=__doc__); sub = parser.add_subparsers(dest='command', required=True)
    p = sub.add_parser('prepare'); p.add_argument('enterprise_run', type=Path); p.add_argument('public_review', type=Path); p.add_argument('output', type=Path)
    p = sub.add_parser('score'); p.add_argument('packet', type=Path); p.add_argument('decisions', type=Path); p.add_argument('output', type=Path)
    args = parser.parse_args()
    if args.command == 'prepare': prepare(args.enterprise_run, args.public_review, args.output)
    else: score(args.packet, args.decisions, args.output)


if __name__ == '__main__': main()
