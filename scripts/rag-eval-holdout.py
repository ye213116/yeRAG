#!/usr/bin/env python3
"""Predeclare one fresh RRF/rerank comparison; no tuning on held-out results."""
import argparse
import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile


def module(name):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), Path(__file__).with_name(name + '.py'))
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value


def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()


def java_hash():
    h = hashlib.sha256()
    for p in sorted(Path('src/main/java').rglob('*.java')) + [Path('pom.xml')]: h.update(str(p).encode()); h.update(p.read_bytes())
    return h.hexdigest()


def code_version():
    return subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip() + '+' + java_hash()


def write(path, value): path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def select(rows, excluded_ids, excluded_questions, count, seed):
    if count < 2 or count > 100 or count % 2: raise ValueError('Even count in 2..100 required')
    normalize = module('rag-eval-study').question_key
    if len({r['_id'] for r in rows}) != len(rows): raise ValueError('Duplicate source IDs')
    used = set(excluded_questions); groups = {'bridge': [], 'comparison': []}
    for row in sorted(rows, key=lambda r: hashlib.sha256(f"{seed}:{r['_id']}".encode()).hexdigest()):
        key = normalize(row['question'])
        if row['_id'] in excluded_ids or key in used or not key or row.get('type') not in groups: continue
        used.add(key); groups[row['type']].append(row)
    if any(len(group) < count // 2 for group in groups.values()): raise ValueError('Insufficient fresh questions')
    return [r['_id'] for group in groups.values() for r in group[:count // 2]]


def prepare(args):
    importer = module('rag-eval-import'); experiment = module('rag-eval-experiment'); study = module('rag-eval-study')
    reference = experiment.load_run(args.reference_run)
    if reference['capture']['codeVersion'] != code_version(): raise ValueError('Reference code differs from current Java build source')
    signature = json.loads(reference['signature'])
    if signature['options']['rewrite'] or signature['options']['rerank'] or signature['retrieval']['rerank']['model'] != 'gte-rerank-v2':
        raise ValueError('Reference must be the fixed RRF control with the preselected v2 service')
    ids, questions, exclusions = set(), set(), []
    for root in args.exclude_dataset:
        cases = [json.loads(l) for l in (root / 'cases.jsonl').read_text().splitlines() if l.strip() and not l.startswith('#')]
        ids.update(c['sourceId'] for c in cases); questions.update(study.question_key(c['question']) for c in cases)
        exclusions.append({'dataset': str(root), 'casesSha256': sha(root / 'cases.jsonl'), 'count': len(cases)})
    rows = [importer.native_hotpot(r) for r in importer.read_records(args.input)]
    selected = select(rows, ids, questions, args.count, args.seed)
    if sha(args.input) != args.source_sha256: raise ValueError('Source input SHA mismatch')
    args.output.mkdir(parents=True, exist_ok=False)
    imported = argparse.Namespace(format='hotpotqa', input=args.input, output=args.output / 'dataset', corpus=None,
                                  count=args.count, seed=args.seed, split='validation', dataset_name='hotpotqa', language='en',
                                  source_url=args.source_url, revision=args.revision, selected_ids=selected)
    manifest = importer.import_dataset(imported)
    if len(manifest['documents']) > 1000: raise ValueError('Corpus exceeds snapshot limit; no distractors may be removed')
    signature.pop('corpusMd5s', None)
    runtime = [p.resolve() for p in args.runtime_config]
    private_resources = [p.resolve() for p in Path('src/main/resources').glob('application*') if p.is_file()]
    options = signature['options']; retrieval = signature['retrieval']; rerank = retrieval['rerank']
    spring_args = ['--spring.config.additional-location=' + ','.join('file:' + str(p) for p in runtime),
                   '--ai.rag.eval.identity=' + reference['capture']['owner'], '--ai.retrieval.bm25-weight=' + str(retrieval['bm25Weight']),
                   '--ai.retrieval.knn-weight=' + str(retrieval['knnWeight']), '--ai.retrieval.rrf-rank-constant=' + str(options['rrfK']),
                   '--ai.retrieval.route-timeout=' + str(retrieval['routeTimeoutMs']) + 'ms',
                   '--ai.rag.candidate-k=' + str(options['candidateK']), '--ai.rag.evidence-tokens=' + str(options['evidenceTokens']),
                   '--ai.rag.context-tokens=' + str(options['contextTokens']), '--ai.rag.safety-tokens=' + str(options['safetyTokens']),
                   '--ai.rag.merge-enabled=' + str(options['merge']).lower(), '--ai.rag.legacy-clipping=' + str(options['legacyClipping']).lower(),
                   '--ai.rag.rerank-threshold=' + str(options['rerankThreshold']), '--ai.retrieval.rerank.model=' + rerank['model'],
                   '--ai.retrieval.rerank.top-n=' + str(rerank['topN']), '--ai.retrieval.rerank.max-doc-chars=' + str(rerank['maxDocChars']),
                   '--ai.retrieval.rerank.timeout=' + str(rerank['timeoutMs']) + 'ms']
    protocol = {'version': 'yerag-predeclared-rerank-holdout-v1', 'createdAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                'selection': 'NO_TUNING; candidate fixed from prior diagnostic, not selected on this holdout',
                'count': args.count, 'seed': args.seed, 'sourceInputSha256': args.source_sha256, 'sourceRevision': args.revision,
                'excluded': exclusions, 'excludedSourceIdCount': len(ids), 'selectedSourceIds': selected,
                'datasetFiles': study.files(imported.output), 'referenceCaptureSha256': sha(reference['root'] / 'capture.json'),
                'codeVersion': code_version(), 'expectedSignature': signature, 'generation': reference['generation'],
                'generationParameters': reference['capture']['generationParameters'], 'judges': reference['judges'],
                'owner': reference['capture']['owner'], 'repeats': 2, 'order': ['rrf-01', 'rrf-rerank-01', 'rrf-rerank-02', 'rrf-02'],
                'policy': json.loads(Path('evaluation/policies/no-quality-drop.json').read_text()),
                'springArgs': spring_args, 'runtimeConfigSha256': {str(p): sha(p) for p in runtime + [Path('.env').resolve()] + private_resources},
                'scriptSha256': {str(p): sha(p) for p in map(Path, ['scripts/rag-eval-holdout.py', 'scripts/rag-eval-experiment.py', 'scripts/rag-eval.sh'])},
                'notice': 'New relative to prior evaluation IDs/normalized questions; not a claim of no LLM pretraining exposure. Separate shared-candidate corpus, not official fullwiki.'}
    write(args.output / 'protocol.json', protocol); (args.output / 'protocol.sha256').write_text(sha(args.output / 'protocol.json') + '\n')
    print('LOCKED', args.count, 'questions;', len(manifest['documents']), 'documents; excluded', len(ids), 'source IDs')


def verify(root, check_runtime=True):
    protocol = json.loads((root / 'protocol.json').read_text())
    if sha(root / 'protocol.json') != (root / 'protocol.sha256').read_text().strip(): raise ValueError('Changed predeclared protocol')
    base = (root / 'dataset').resolve()
    for name, checksum in protocol['datasetFiles'].items():
        p = (base / name).resolve()
        if not p.is_relative_to(base) or sha(p) != checksum: raise ValueError('Changed holdout dataset')
    if check_runtime:
        if code_version() != protocol['codeVersion']: raise ValueError('Java code changed after freeze')
        for field in ['runtimeConfigSha256', 'scriptSha256']:
            for name, checksum in protocol[field].items():
                if sha(Path(name)) != checksum: raise ValueError('Execution configuration/code changed after freeze')
    return protocol


def validate_run(protocol, dataset, run, variant):
    for name, checksum in protocol['datasetFiles'].items():
        if sha(run['root'] / 'dataset' / name) != checksum: raise ValueError('Run not bound to frozen holdout')
    if run['capture']['codeVersion'] != protocol['codeVersion'] or run['capture']['owner'] != protocol['owner']:
        raise ValueError('Actual code/owner differs from lock')
    if run['generation'] != protocol['generation'] or run['capture']['generationParameters'] != protocol['generationParameters'] or json.dumps(run['judges'], sort_keys=True) != json.dumps(protocol['judges'], sort_keys=True):
        raise ValueError('Actual generation/judge differs from lock')
    actual = json.loads(run['signature']); actual.pop('corpusMd5s', None)
    expected = json.loads(json.dumps(protocol['expectedSignature'])); expected['options']['rerank'] = variant.startswith('rrf-rerank')
    if actual != expected: raise ValueError('Actual retrieval/assembly configuration differs from lock')


def run(args):
    experiment = module('rag-eval-experiment'); protocol = verify(args.study)
    if os.environ.get('AI_RAG_EVAL_IDENTITY') != protocol['owner']: raise ValueError('Trusted identity must match lock')
    ledger = args.study / 'opened.json'
    if ledger.exists():
        value = json.loads(ledger.read_text())
        if not args.resume or value['output'] != str(args.output.resolve()) or value['protocolSha256'] != sha(args.study / 'protocol.json'):
            raise ValueError('Holdout already opened; only identical resume is permitted')
    else:
        if args.resume: raise ValueError('No opened holdout to resume')
        write(ledger, {'openedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'output': str(args.output.resolve()),
                       'protocolSha256': sha(args.study / 'protocol.json')})
    # Existing runner retains all incomplete/failed observations and balanced order.
    settings = argparse.Namespace(action='rerank', dataset=args.study / 'dataset', output=args.output, repeats=2, support=False,
                                  resume=args.resume, policy=args.study / 'gate-policy.json', spring_arg=protocol['springArgs'])
    policy_file = args.study / 'gate-policy.json'
    if policy_file.exists():
        if json.loads(policy_file.read_text()) != protocol['policy']: raise ValueError('Changed gate policy')
    else: write(policy_file, protocol['policy'])
    completed = (args.output / 'matrix.json').exists() and json.loads((args.output / 'matrix.json').read_text()).get('status') == 'COMPLETED'
    if not (args.resume and completed): experiment.matrix(settings)
    for variant in protocol['order']:
        observed = experiment.load_run(args.output / variant); validate_run(protocol, args.study / 'dataset', observed, variant)
        if module('rag-eval-study').instant(observed['capture']['createdAt']) <= module('rag-eval-study').instant(protocol['createdAt']):
            raise ValueError('Run predates protocol freeze')
    reports = [json.loads((args.output / f'rrf-vs-rerank-{repeat:02}/comparison.json').read_text()) for repeat in [1, 2]]
    result = {'status': 'COMPLETED', 'protocolSha256': sha(args.study / 'protocol.json'),
              'gateStatus': 'FAIL' if any(r['gateStatus'] == 'FAIL' for r in reports) else 'PASS' if all(r['gateStatus'] == 'PASS' for r in reports) else 'INCONCLUSIVE',
              'notice': 'One preregistered candidate, two repeated paired runs on the same questions; no retuning or production acceptance claim.'}
    write(args.output / 'holdout-validation.json', result); print('FROZEN HOLDOUT', result['gateStatus'])


def finalize(args):
    """Audit a fully recorded run after a declared validator-only correction; never calls models."""
    experiment = module('rag-eval-experiment'); protocol = verify(args.study, check_runtime=False)
    amendment = json.loads(args.amendment.read_text())
    original = args.study / 'tooling-at-freeze/rag-eval-holdout.py'
    if amendment.get('reason') != 'POST_RUN_JSON_CONTAINER_COMPATIBILITY' or amendment.get('protocolSha256') != sha(args.study / 'protocol.json'):
        raise ValueError('Validator amendment is not bound to this protocol')
    if sha(original) != protocol['scriptSha256']['scripts/rag-eval-holdout.py'] or amendment.get('originalScriptSha256') != sha(original):
        raise ValueError('Frozen producer source changed')
    if amendment.get('correctedValidatorSha256') != sha(Path(__file__)) or code_version() != protocol['codeVersion']:
        raise ValueError('Undeclared validator/Java code change')
    for name, checksum in protocol['runtimeConfigSha256'].items():
        if sha(Path(name)) != checksum: raise ValueError('Runtime changed after freeze')
    for name, checksum in protocol['scriptSha256'].items():
        if name != 'scripts/rag-eval-holdout.py' and sha(Path(name)) != checksum: raise ValueError('Producer code changed')
    opened = json.loads((args.study / 'opened.json').read_text()); state = json.loads((args.output / 'matrix.json').read_text())
    if opened['output'] != str(args.output.resolve()) or opened['protocolSha256'] != sha(args.study / 'protocol.json'):
        raise ValueError('Opened ledger differs')
    if state['status'] != 'COMPLETED' or state['kind'] != 'rerank' or state['repeats'] != 2 or state['policy'] != protocol['policy']:
        raise ValueError('Only a complete unchanged experiment can be finalized')
    runs = {}
    for variant in protocol['order']:
        observed = experiment.load_run(args.output / variant); validate_run(protocol, args.study / 'dataset', observed, variant)
        if module('rag-eval-study').instant(observed['capture']['createdAt']) <= module('rag-eval-study').instant(opened['openedAt']):
            raise ValueError('Observation predates opened ledger')
        runs[variant] = observed
    reports = []
    with tempfile.TemporaryDirectory(prefix='yerag-closed-holdout-') as directory:
        for repeat in [1, 2]:
            destination = Path(directory) / str(repeat)
            result = experiment.compare(runs[f'rrf-{repeat:02}'], runs[f'rrf-rerank-{repeat:02}'], destination,
                                        protocol['policy'], allowed_config_changes=['options.rerank'])
            for name in ['comparison.json', 'comparison.md']:
                if (destination / name).read_bytes() != (args.output / f'rrf-vs-rerank-{repeat:02}' / name).read_bytes():
                    raise ValueError('Recorded comparison changed')
            reports.append(result)
    result = {'status': 'COMPLETED', 'protocolSha256': sha(args.study / 'protocol.json'),
              'gateStatus': 'FAIL' if any(r['gateStatus'] == 'FAIL' for r in reports) else 'PASS' if all(r['gateStatus'] == 'PASS' for r in reports) else 'INCONCLUSIVE',
              'validatorAmendmentSha256': sha(args.amendment), 'modelCallsDuringFinalization': 0,
              'notice': 'Producer source/protocol unchanged. Python tuple/list identity normalized in post-run validation; no parameters selected or rerun.'}
    write(args.output / 'holdout-validation.json', result); print('CLOSED HOLDOUT', result['gateStatus'], 'no model reruns')


def main():
    p = argparse.ArgumentParser(description=__doc__); sub = p.add_subparsers(dest='action', required=True)
    q = sub.add_parser('prepare'); q.add_argument('input', type=Path); q.add_argument('output', type=Path)
    q.add_argument('--exclude-dataset', type=Path, action='append', required=True); q.add_argument('--runtime-config', type=Path, action='append', required=True)
    q.add_argument('--reference-run', type=Path, required=True); q.add_argument('--count', type=int, default=100); q.add_argument('--seed', type=int, default=2026102702)
    q.add_argument('--source-url', required=True); q.add_argument('--revision', required=True); q.add_argument('--source-sha256', required=True)
    q = sub.add_parser('run'); q.add_argument('study', type=Path); q.add_argument('output', type=Path); q.add_argument('--resume', action='store_true')
    q = sub.add_parser('verify'); q.add_argument('study', type=Path)
    q = sub.add_parser('finalize'); q.add_argument('study', type=Path); q.add_argument('output', type=Path); q.add_argument('--amendment', type=Path, required=True)
    args = p.parse_args()
    if args.action == 'prepare': prepare(args)
    elif args.action == 'run': run(args)
    elif args.action == 'finalize': finalize(args)
    else: verify(args.study); print('FROZEN PROTOCOL VERIFIED')


if __name__ == '__main__': main()
