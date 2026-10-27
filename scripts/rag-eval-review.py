#!/usr/bin/env python3
"""Prioritize frozen score disagreements for a human. Never emits human approval records."""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import subprocess

spec = importlib.util.spec_from_file_location('experiment', Path(__file__).with_name('rag-eval-experiment.py'))
experiment = importlib.util.module_from_spec(spec); spec.loader.exec_module(experiment)


def flags(row):
    result = set(); d = row.get('deterministic', {}); native = row.get('nativeAnswer', {})
    verdict = row.get('semantic', {}).get('assessment', {}).get('verdict')
    support = row.get('answerSupport', {}).get('metrics', {}).get('judgeClaimSupportRate')
    if not d.get('generationComplete'): result.add('GENERATION_INCOMPLETE')
    if native.get('em') == 0 and verdict == 'correct': result.add('NATIVE_SEMANTIC_DISAGREEMENT')
    if verdict == 'wrong' and support == 1: result.add('REFERENCE_SUPPORT_DISAGREEMENT')
    if native.get('em') == 1 and support == 0: result.add('CORRECT_ANSWER_WITHOUT_JUDGED_SUPPORT')
    if row.get('semantic', {}).get('assessment', {}).get('reviewRequired'): result.add('JUDGE_REQUESTED_REVIEW')
    if d.get('aclExactLeakDetected'): result.add('ACL_EXACT_LEAK')
    if d.get('wire', {}).get('state') in {'PARTIAL', 'NONE'}: result.add('INCOMPLETE_WIRE_EVIDENCE')
    return result


def variation_flags(observation, previous):
    if observation['actualContexts'] != previous['actualContexts']: return set()
    if observation['answer'] != previous['answer']:
        result = {'ANSWER_VARIATION_SAME_CONTEXT'}
        a, b = observation.get('nativeAnswer') or {}, previous.get('nativeAnswer') or {}
        if (a.get('em'), a.get('f1')) != (b.get('em'), b.get('f1')): result.add('NATIVE_SCORE_VARIATION_SAME_CONTEXT')
        return result
    a = (observation['answerSupport'] or {}).get('metrics', {}).get('judgeClaimSupportRate')
    b = (previous['answerSupport'] or {}).get('metrics', {}).get('judgeClaimSupportRate')
    return {'SUPPORT_JUDGE_VARIATION_SAME_ANSWER_CONTEXT'} if a != b else set()


def write(runs, output):
    if len({r['capture']['datasetHash'] for r in runs}) != 1:
        raise ValueError('Review pack requires the same dataset labels')
    cases = {}; signatures = {}
    for run in runs:
        inputs = list(map(json.loads, (run['root'] / 'report/ragas.jsonl').read_text().splitlines()))
        exported = {r['caseId']: inputs[r['line'] - 1] for r in map(json.loads, (run['root'] / 'report/ragas-index.jsonl').read_text().splitlines()) if r['status'] == 'EXPORTED'}
        samples = {r['id']: r for r in map(json.loads, (run['root'] / 'dataset/cases.jsonl').read_text().splitlines())}
        for case_id, row in run['rows'].items():
            sample = samples[case_id]
            item = cases.setdefault(case_id, {'caseId': case_id, 'datasetHash': run['capture']['datasetHash'], 'state': 'PENDING_HUMAN_REVIEW',
                'question': sample['question'], 'referenceAnswers': sample.get('referenceAnswers', [sample['referenceAnswer']]),
                'necessaryEvidence': sample['evidenceUnits'], 'reasons': set(), 'observations': []})
            item['reasons'].update(flags(row)); entry = exported.get(case_id, {})
            observation = {'run': str(run['root']), 'pipelineRunId': row['pipelineRunId'], 'answer': entry.get('response'),
                'actualContexts': entry.get('retrieved_contexts', []), 'nativeAnswer': row.get('nativeAnswer'),
                'top5': row.get('deterministic', {}).get('top5'), 'wire': row.get('deterministic', {}).get('wire'),
                'semantic': row.get('semantic'), 'answerSupport': row.get('answerSupport')}
            group = json.dumps([run['capture']['codeVersion'], run['capture'].get('experiment'), run['signature'], run['generation'], run['judges']], sort_keys=True)
            for previous, previous_group in signatures.get(case_id, []):
                if group != previous_group or observation['actualContexts'] != previous['actualContexts']: continue
                item['reasons'].update(variation_flags(observation, previous))
            signatures.setdefault(case_id, []).append((observation, group)); item['observations'].append(observation)
    urgent = {'ACL_EXACT_LEAK', 'GENERATION_INCOMPLETE', 'REFERENCE_SUPPORT_DISAGREEMENT', 'NATIVE_SCORE_VARIATION_SAME_CONTEXT'}
    rows = sorted(cases.values(), key=lambda r: (not bool(r['reasons'] & urgent), not bool(r['reasons']), -len(r['reasons']), r['caseId']))
    for row in rows: row['reasons'] = sorted(row['reasons']) or ['LABELS_REQUIRE_HUMAN_CONFIRMATION']
    output.mkdir(parents=True, exist_ok=False, mode=0o700)
    (output / 'review-items.jsonl').write_text(''.join(json.dumps(row, ensure_ascii=False) + '\n' for row in rows))
    summary = {'cases': len(rows), 'observations': sum(len(r['observations']) for r in rows), 'state': 'PENDING_HUMAN_REVIEW',
               'notice': 'Machine-prioritized review leads, not factual adjudication. Official labels and scores remain unchanged.',
               'inputs': [{'run': str(r['root']), 'provenanceSha256': experiment.sha(r['root'] / 'report/provenance.json')} for r in runs]}
    (output / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
    lines = ['# 评分分歧人工复核清单', '', '全部条目仍为待人审。此文件不是 EvaluationReviews 的批准输入，不改写官方标签或分数。JSONL 每行包含完整回答、真实上下文、支持句与两类 Judge 原判断。', '', '| 行号 | Case ID | 复核原因 |', '|---|---|---|']
    for i, row in enumerate(rows, 1): lines.append(f"| {i} | {row['caseId']} | {', '.join(row['reasons'])} |")
    (output / 'review.md').write_text('\n'.join(lines) + '\n')
    return summary


def label_worksheet(dataset, output):
    validated = subprocess.run(['scripts/rag-eval.sh', 'validate', str(dataset)], capture_output=True, text=True, check=True)
    dataset_hash = re.search(r'datasetHash=([a-f0-9]{64})', validated.stdout).group(1)
    manifest = json.loads((dataset / 'dataset.json').read_text())
    cases = [json.loads(l) for l in (dataset / 'cases.jsonl').read_text().splitlines() if l.strip() and not l.startswith('#')]
    rows = [{'worksheetType': 'LABEL_REVIEW_WORKSHEET', 'datasetHash': dataset_hash, 'state': 'PENDING_HUMAN_REVIEW',
             'case': case, 'reviewer': None, 'decision': None, 'reason': None} for case in cases]
    output.mkdir(parents=True, exist_ok=False, mode=0o700)
    (output / 'label-worksheet.jsonl').write_text(''.join(json.dumps(r, ensure_ascii=False) + '\n' for r in rows))
    lines = ['# 事实标注人工复核工作表', '', f'题集哈希：{dataset_hash}', '',
             '全部为待人审；本工作表不属于可直接提交的批准记录。请核对参考答案、必答要点、必要证据与等价位置；负例另核对固定语料中的不可回答性及身份可见性。完成后按 evaluation/README.md 追加真实复核记录，保留复核人、理由和版本。', '',
             '| Case ID | 类型 | 问题 | 必要单元数 |', '|---|---|---|---:|']
    for case in cases: lines.append(f"| {case['id']} | {case['type']} | {case['question'].replace('|', '/')} | {len(case['evidenceUnits'])} |")
    lines += ['', '原始资料：', '']
    for doc in manifest['documents']: lines.append(f"- [{doc['path']}]({(dataset / doc['path']).resolve()})")
    (output / 'review.md').write_text('\n'.join(lines) + '\n')
    return {'cases': len(cases), 'datasetHash': dataset_hash}


def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('runs', nargs='*', type=Path); parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--dataset', type=Path, help='Prepare a label-only worksheet, with no human decisions filled in')
    args = parser.parse_args()
    if bool(args.runs) == bool(args.dataset): parser.error('Provide run directories OR --dataset')
    summary = label_worksheet(args.dataset, args.output) if args.dataset else write([experiment.load_run(p) for p in args.runs], args.output)
    print('PENDING HUMAN REVIEW', summary['cases'], 'cases')


if __name__ == '__main__': main()
