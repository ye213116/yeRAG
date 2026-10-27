#!/usr/bin/env python3
"""Frozen development/holdout data and evidence-only RRF selection; no evaluation framework."""
import argparse
import hashlib
import importlib.util
import json
import datetime
import math
import os
from pathlib import Path
import re
import subprocess
import unicodedata


def module(name):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), Path(__file__).with_name(name + '.py'))
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value)
    return value


def checksum(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def question_key(text):
    return ''.join(c for c in unicodedata.normalize('NFKC', text).casefold() if c.isalnum())


def select(rows, excluded_ids, excluded_questions, development, holdout, seed):
    if any(n < 2 or n > 100 or n % 2 for n in [development, holdout]):
        raise ValueError('Each split requires an even count in 2..100 (bounded candidate corpus)')
    if len({r['_id'] for r in rows}) != len(rows):
        raise ValueError('Duplicate source IDs')
    eligible = sorted(rows, key=lambda r: hashlib.sha256(f"{seed}:{r['_id']}".encode()).hexdigest())
    used = set(excluded_questions); groups = {'bridge': [], 'comparison': []}
    for row in eligible:
        key = question_key(row['question'])
        if row['_id'] in excluded_ids or key in used or not key or row.get('type') not in groups:
            continue
        used.add(key); groups[row['type']].append(row)
    result = {'development': [], 'holdout': []}
    for kind, records in groups.items():
        if len(records) < (development + holdout) // 2:
            raise ValueError('Insufficient disjoint source questions: ' + kind)
        result['development'].extend(records[:development // 2])
        result['holdout'].extend(records[development // 2:(development + holdout) // 2])
    return result


def files(root):
    return {str(p.relative_to(root)): checksum(p) for p in sorted(root.rglob('*')) if p.is_file()}


def verify(root):
    root = Path(root).resolve(); protocol = json.loads((root / 'study.json').read_text())
    if checksum(root / 'study.json') != (root / 'study.sha256').read_text().strip():
        raise ValueError('Changed preregistered study protocol')
    for role in ['development', 'holdout']:
        directory = root / role
        for name, value in protocol['splits'][role]['files'].items():
            path = (directory / name).resolve()
            if not path.is_relative_to(directory) or checksum(path) != value:
                raise ValueError('Changed split input: ' + role)
    return protocol


def prepare(args):
    importer = module('rag-eval-import')
    rows = [importer.native_hotpot(r) for r in importer.read_records(args.input)]
    excluded_ids, excluded_questions, exclusions = set(), set(), []
    for root in args.exclude_dataset:
        cases = [json.loads(l) for l in (root / 'cases.jsonl').read_text().splitlines() if l.strip() and not l.startswith('#')]
        excluded_ids.update(r['sourceId'] for r in cases)
        excluded_questions.update(question_key(r['question']) for r in cases)
        exclusions.append({'dataset': str(root), 'casesSha256': checksum(root / 'cases.jsonl'), 'count': len(cases)})
    selected = select(rows, excluded_ids, excluded_questions, args.development_count, args.holdout_count, args.seed)
    args.output.mkdir(parents=True, exist_ok=False)
    protocol = {'version': 'yerag-development-holdout-v1', 'seed': args.seed,
                'algorithm': 'sha256-seed-id; globally unique NFKC/casefold/alphanumeric questions; stratified bridge/comparison; development then holdout within stratum',
                'sourceInputSha256': checksum(args.input), 'sourceRevision': args.revision, 'excluded': exclusions,
                'notice': 'Separate fixed shared-candidate corpora per split. Gold labels are not human-approved. Holdout is for one locked candidate, not parameter selection.',
                'grid': [{'name': 'bm25-' + str(w), 'constant': 60, 'bm25Weight': w, 'vectorWeight': 1.0} for w in [.25, .5, 1.0, 2.0]],
                'selectionRule': 'lexicographic CompleteHit@5, Coverage@5, Hit@5; ties prefer baseline, then closest log-weight to baseline, then name',
                'gatePolicy': {'notice': 'Exploratory development gate, not production acceptance', 'maxDrops': {'completeHitAt5': 0, 'em': 0}},
                'splits': {}}
    for role, records in selected.items():
        sub = argparse.Namespace(format='hotpotqa', input=args.input, output=args.output / role, corpus=None,
                                 count=len(records), seed=args.seed, split='validation', dataset_name='hotpotqa', language='en',
                                 source_url=args.source_url, revision=args.revision, selected_ids=[r['_id'] for r in records])
        importer.import_dataset(sub)
        path = sub.output / 'dataset.json'; manifest = json.loads(path.read_text())
        manifest['datasetVersion'] = f'hotpotqa-{role}-{len(records)}-seed{args.seed}-v1'
        manifest['source']['sampling']['algorithm'] = 'explicit-source-ids-from-frozen-study-v1'
        manifest['study'] = {'role': role, 'version': protocol['version'], 'seed': args.seed}
        path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
        protocol['splits'][role] = {'count': len(records), 'sourceIds': [r['_id'] for r in records], 'files': files(sub.output)}
    (args.output / 'study.json').write_text(json.dumps(protocol, ensure_ascii=False, indent=2) + '\n')
    (args.output / 'study.sha256').write_text(checksum(args.output / 'study.json') + '\n')
    verify(args.output)
    print('FROZEN development/holdout, no repeated question identities')


def bound_run(study, role, run):
    protocol = verify(study)
    expected = protocol['splits'][role]['files']
    for name, value in expected.items():
        if checksum(run['root'] / 'dataset' / name) != value:
            raise ValueError('Run does not match frozen ' + role + ' inputs')
    return protocol


def choose(configurations, baseline):
    def key(row):
        c = row['configuration']
        return (-row['completeHitAt5'], -row['coverageAt5'], -row['hitAt5'],
                c != baseline, abs(math.log(c['bm25Weight'] / baseline['bm25Weight'])), c['name'])
    return min(configurations, key=key)


def instant(value):
    # Java Instant emits nanoseconds; Python 3.10 accepts at most microseconds.
    value = re.sub(r'(\.\d{6})\d+(?=Z|[+-])', r'\1', value)
    result = datetime.datetime.fromisoformat(value.replace('Z', '+00:00'))
    if result.tzinfo is None: raise ValueError('Timezone required')
    return result


def tune(args):
    if (args.study / 'holdout-opened.json').exists():
        raise ValueError('Holdout has already been opened; a new candidate needs a new held-out question set')
    experiment = module('rag-eval-experiment'); run = experiment.load_run(args.run)
    protocol = bound_run(args.study, 'development', run)
    signature = json.loads(run['signature']); options = signature['options']; retrieval = signature['retrieval']
    baseline = next((c for c in protocol['grid'] if c['constant'] == options['rrfK'] and c['bm25Weight'] == retrieval['bm25Weight'] and c['vectorWeight'] == retrieval['knnWeight']), None)
    if baseline is None:
        raise ValueError('Grid must contain the actual development baseline')
    args.output.mkdir(parents=True, exist_ok=False)
    grid = args.output / 'grid.json'; grid.write_text(json.dumps(protocol['grid'], indent=2) + '\n')
    subprocess.run(['scripts/rag-eval.sh', 'replay', str(run['root']), str(grid), str(args.output / 'replay')], check=True)
    replay = json.loads((args.output / 'replay/replay.json').read_text())
    selected = choose(replay['configurations'], baseline)
    selection = {'version': 'yerag-locked-evidence-selection-v1', 'createdAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                 'studySha256': checksum(args.study / 'study.json'), 'developmentRun': str(run['root']),
                 'developmentCaptureSha256': checksum(run['root'] / 'capture.json'), 'codeVersion': run['capture']['codeVersion'],
                 'gridSha256': checksum(grid), 'replaySha256': checksum(args.output / 'replay/replay.json'),
                 'selectionRule': protocol['selectionRule'], 'baseline': baseline, 'selected': selected,
                 'notice': 'Selected on development evidence only. Candidate generation quality is unmeasured until the locked holdout run.'}
    path = args.output / 'selection.json'; path.write_text(json.dumps(selection, ensure_ascii=False, indent=2) + '\n')
    (args.output / 'selection.sha256').write_text(checksum(path) + '\n')
    print('LOCKED', selected['configuration']['name'], 'development CompleteHit', selected['completeHitAt5'])


def validate(args):
    experiment = module('rag-eval-experiment'); protocol = verify(args.study)
    selection = json.loads(args.selection.read_text())
    if checksum(args.selection) != args.selection.with_suffix('.sha256').read_text().strip() or checksum(args.study / 'study.json') != selection['studySha256']:
        raise ValueError('Changed study/selection lock')
    dev = experiment.load_run(Path(selection['developmentRun'])); bound_run(args.study, 'development', dev)
    if checksum(dev['root'] / 'capture.json') != selection['developmentCaptureSha256']:
        raise ValueError('Changed development input')
    if not os.environ.get('AI_RAG_EVAL_IDENTITY'):
        raise ValueError('AI_RAG_EVAL_IDENTITY required')
    # The first holdout observation consumes this selection. Re-tuning and silently
    # reusing the same holdout as a development set is not an accepted workflow.
    ledger = args.study / 'holdout-opened.json'
    resume = getattr(args, 'resume', False)
    if ledger.exists():
        opened = json.loads(ledger.read_text())
        if not resume or opened['selectionSha256'] != checksum(args.selection) or opened['output'] != str(args.output.resolve()):
            raise ValueError('Holdout is already open; resume requires the identical selection and output directory')
    else:
        if resume: raise ValueError('No opened holdout to resume')
        args.output.mkdir(parents=True, exist_ok=False, mode=0o700)
        with ledger.open('x') as file:
            json.dump({'selectionSha256': checksum(args.selection), 'output': str(args.output.resolve()),
                       'openedAt': datetime.datetime.now(datetime.timezone.utc).isoformat()}, file, indent=2)
    runs = {}
    for name, config in [('baseline', selection['baseline']), ('candidate', selection['selected']['configuration'])]:
        destination = args.output / name
        command = ['scripts/rag-eval.sh', 'run', str(args.study / 'holdout'), str(destination), *args.spring_arg,
                   '--ai.rag.eval.baseline=true', '--ai.rag.eval.recall-strategy=HYBRID', '--ai.rag.eval.support-enabled=false',
                   f"--ai.retrieval.bm25-weight={config['bm25Weight']}", f"--ai.retrieval.knn-weight={config['vectorWeight']}",
                   f"--ai.retrieval.rrf-rank-constant={config['constant']}"]
        if not destination.exists():
            with (args.output / (name + '.log')).open('x') as log:
                subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)
        elif not resume:
            raise ValueError('Existing holdout run cannot be overwritten')
        run = experiment.load_run(destination); bound_run(args.study, 'holdout', run)
        if run['capture']['codeVersion'] != selection['codeVersion'] or run['generation'] != dev['generation'] or run['judges'] != dev['judges']:
            raise ValueError('Code or models changed after development selection')
        if instant(run['capture']['createdAt']) <= instant(selection['createdAt']):
            raise ValueError('Holdout run predates candidate lock')
        actual = json.loads(run['signature'])
        if actual['retrieval']['bm25Weight'] != config['bm25Weight'] or actual['retrieval']['knnWeight'] != config['vectorWeight'] or actual['options']['rrfK'] != config['constant']:
            raise ValueError('Actual configuration differs from locked selection')
        runs[name] = run; print('COMPLETED holdout', name, flush=True)
    report = experiment.compare(runs['baseline'], runs['candidate'], args.output / 'comparison', protocol['gatePolicy'],
                                allowed_config_changes=['retrieval.bm25Weight', 'retrieval.knnWeight', 'options.rrfK'])
    result = {'status': 'COMPLETED', 'gateStatus': report['gateStatus'], 'selectionSha256': checksum(args.selection),
              'studySha256': checksum(args.study / 'study.json'), 'interpretation': 'One frozen holdout comparison. No automatic production change or general superiority claim.'}
    (args.output / 'validation.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print('Holdout gate:', report['gateStatus'])


def main():
    parser = argparse.ArgumentParser(description=__doc__); sub = parser.add_subparsers(dest='action', required=True)
    p = sub.add_parser('prepare'); p.add_argument('input', type=Path); p.add_argument('output', type=Path)
    p.add_argument('--exclude-dataset', type=Path, action='append', default=[])
    p.add_argument('--development-count', type=int, default=100); p.add_argument('--holdout-count', type=int, default=100)
    p.add_argument('--seed', type=int, default=20261027); p.add_argument('--source-url', required=True); p.add_argument('--revision', required=True)
    p = sub.add_parser('tune'); p.add_argument('study', type=Path); p.add_argument('run', type=Path); p.add_argument('output', type=Path)
    p = sub.add_parser('validate'); p.add_argument('study', type=Path); p.add_argument('selection', type=Path); p.add_argument('output', type=Path)
    p.add_argument('--spring-arg', action='append', default=[])
    p.add_argument('--resume', action='store_true', help='Reuse only complete hash-verified runs for the same locked selection and output; never overwrite partial answers')
    args = parser.parse_args(); {'prepare': prepare, 'tune': tune, 'validate': validate}[args.action](args)


if __name__ == '__main__':
    main()
