#!/usr/bin/env python3
"""Small preselected official-source subsets on the real private upload/retrieval path, then native scoring."""
import argparse
import csv
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import secrets
import subprocess
import tarfile
import time
import urllib.request
import urllib.error
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def sha(data): return hashlib.sha256(data).hexdigest()


def api(path, token=None, data=None, content_type='application/json', method=None):
    request = urllib.request.Request('http://localhost:8081/api/v1/' + path, data,
                                     {'Content-Type': content_type, **({'Authorization': 'Bearer ' + token} if token else {})}, method=method)
    try:
        with urllib.request.urlopen(request, timeout=30) as response: result = json.load(response)
    except urllib.error.HTTPError as error: raise RuntimeError('Fixture API HTTP ' + str(error.code)) from None
    if result.get('code') != 200: raise RuntimeError('Fixture API rejected request')
    return result.get('data')


def write(path, value): path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def prepare(sources, output):
    documents = {}; reference_paragraphs = []; cases = []; mappings = {}; native_qrels = []
    def document(text, source_id):
        md5 = hashlib.md5(text.encode()).hexdigest(); documents[md5] = {'text': text, 'sourceId': source_id}
        return md5
    with tarfile.open(sources / 'dureader.tar.gz') as archive:
        original = json.load(archive.extractfile('dureader_robust-data/dev.json'))
    chinese = [(paragraph, qa) for paragraph in original['data'][0]['paragraphs'] for qa in paragraph['qas']]
    chinese = sorted(chinese, key=lambda row: sha(('20261028:' + str(row[1]['id'])).encode()))[:5]
    chinese_scope = sorted({document(paragraph['context'], 'dureader-' + str(qa['id'])) for paragraph, qa in chinese})
    for paragraph, qa in chinese:
        reference_paragraphs.append({'context': paragraph['context'], 'qas': [qa]})
        cases.append({'id': 'zh-' + str(qa['id']), 'kind': 'qa', 'question': qa['question'], 'topK': 5, 'corpusMd5s': chinese_scope})
    write(output / 'dureader-reference.json', {'data': [{'title': 'Local deterministic dev subset', 'paragraphs': reference_paragraphs}]})
    with zipfile.ZipFile(sources / 'scifact.zip') as archive:
        corpus = {row['_id']: row for row in map(json.loads, archive.read('scifact/corpus.jsonl').decode().splitlines())}
        queries = {row['_id']: row['text'] for row in map(json.loads, archive.read('scifact/queries.jsonl').decode().splitlines())}
        qrels = list(csv.DictReader(io.StringIO(archive.read('scifact/qrels/test.tsv').decode()), delimiter='\t'))
    selected = sorted({row['query-id'] for row in qrels}, key=lambda key: sha(('20261028:' + key).encode()))[:6]
    native_qrels = [row for row in qrels if row['query-id'] in selected]
    positives = {row['corpus-id'] for row in native_qrels}
    negatives = sorted(set(corpus) - positives, key=lambda key: sha(('20261028:' + key).encode()))[:40]
    scope = []
    for key in sorted(positives | set(negatives)):
        row = corpus[key]; md5 = document(row['title'] + '\n\n' + row['text'], key); scope.append(md5); mappings[md5] = key
    with (output / 'scifact-qrels.tsv').open('w') as stream:
        writer = csv.DictWriter(stream, fieldnames=['query-id', 'corpus-id', 'score'], delimiter='\t'); writer.writeheader(); writer.writerows(native_qrels)
    for variant in ['HYBRID', 'BM25']:
        for key in selected:
            row = {'id': key + '-' + variant, 'kind': 'retrieval', 'question': queries[key], 'topK': 10, 'corpusMd5s': scope}
            if variant == 'BM25': row['recallStrategy'] = 'BM25'
            cases.append(row)
    protocol = {'version': 'yerag-native-smoke-20261028-v1', 'seed': 20261028, 'corpusMd5s': sorted(documents), 'cases': cases,
                'setting': 'PRESELECTED_SHARED_CANDIDATE_SUBSETS_NOT_FULL_BENCHMARK',
                'sampling': {'chinese': 'sha256 seed:id first5 of dev', 'beir': 'sha256 seed:id first6 test queries; ALL their judged docs plus40 deterministic other docs'},
                'sourceHashes': {name: sha((sources / name).read_bytes()) for name in ['dureader.tar.gz', 'scifact.zip']}}
    write(output / 'protocol.json', protocol); write(output / 'scifact-mapping.json', mappings)
    return protocol, documents


def upload(md5, document, user):
    fields = {'fileMd5': md5, 'chunkIndex': '0', 'totalSize': str(len(document['text'].encode())), 'fileName': md5 + '.txt',
              'totalChunks': '1', 'isPublic': 'false', 'orgTag': 'PRIVATE_' + user['username']}
    boundary = 'yerag-' + uuid.uuid4().hex
    parts = [f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode() for key, value in fields.items()]
    parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{md5}.txt"\r\nContent-Type: text/plain\r\n\r\n'.encode() + document['text'].encode() + b'\r\n')
    parts.append(f'--{boundary}--\r\n'.encode())
    api('upload/chunk', user['token'], b''.join(parts), 'multipart/form-data; boundary=' + boundary)
    api('upload/merge', user['token'], json.dumps({'fileMd5': md5, 'fileName': md5 + '.txt'}).encode())


def wait_indexed(documents, user_id, sql):
    env = json.loads(subprocess.check_output(['docker', 'inspect', 'es']))[0]['Config']['Env']
    password = next(value.split('=', 1)[1] for value in env if value.startswith('ELASTIC_PASSWORD='))
    import base64
    end = time.monotonic() + 180
    selected = ','.join("'" + md5 + "'" for md5 in documents)
    while time.monotonic() < end:
        expected = {line.split('\t')[0]: int(line.split('\t')[1]) for line in sql(
            f'SELECT file_md5,COUNT(*) FROM document_vectors WHERE user_id={int(user_id)} AND file_md5 IN ({selected}) GROUP BY file_md5').splitlines()}
        query = {'size': 10000, '_source': ['fileMd5', 'chunkId', 'textContent', 'userId', 'orgTag', 'isPublic'],
                 'query': {'bool': {'filter': [{'terms': {'fileMd5': list(documents)}}, {'term': {'userId': str(user_id)}}]}},
                 'aggs': {'files': {'terms': {'field': 'fileMd5', 'size': 1000}}}}
        req = urllib.request.Request('http://localhost:9200/knowledge_base/_search', json.dumps(query).encode(),
                                     {'Content-Type': 'application/json', 'Authorization': 'Basic ' + base64.b64encode(('elastic:' + password).encode()).decode()})
        with urllib.request.urlopen(req, timeout=10) as response: result = json.load(response)
        indexed = {row['key']: row['doc_count'] for row in result['aggregations']['files']['buckets']}
        if indexed.keys() == documents.keys() and indexed == expected:
            chunks = sorted([{'id': hit['_id'], 'source': hit['_source']} for hit in result['hits']['hits']], key=lambda row: row['id'])
            if len(chunks) != sum(expected.values()): raise RuntimeError('Incomplete corpus snapshot')
            return {'files': len(indexed), 'chunks': chunks, 'expectedChunkCounts': expected}
        time.sleep(0.5)
    raise RuntimeError('Uploaded corpus indexing incomplete')


def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('sources', type=Path); parser.add_argument('output', type=Path)
    parser.add_argument('--python', required=True, help='Isolated Python with requirements-benchmarks.txt installed')
    args = parser.parse_args(); args.output.mkdir(parents=True, exist_ok=False, mode=0o700)
    protocol, documents = prepare(args.sources, args.output)
    # Refuse existing content so cleanup cannot remove another run's document.
    spec = importlib.util.spec_from_file_location('local_e2e', ROOT / 'scripts/local-e2e.py'); cleanup = importlib.util.module_from_spec(spec); spec.loader.exec_module(cleanup)
    selected = ','.join("'" + md5 + "'" for md5 in documents)
    if int(cleanup.sql(f'SELECT COUNT(*) FROM file_upload WHERE file_md5 IN ({selected})')): raise RuntimeError('Corpus already uploaded; refusing ambiguous fixture ownership')
    user = {'username': 'e2e' + secrets.token_hex(5), 'password': secrets.token_hex(8)}
    fixtures = {'users': [], 'documents': []}; fixture_file = args.output / 'fixtures.json'
    try:
        api('users/register', data=json.dumps(user).encode()); fixtures['users'].append(dict(user)); write(fixture_file, fixtures); fixture_file.chmod(0o600)
        user['token'] = api('users/login', data=json.dumps(user).encode())['token']; user['id'] = api('users/me', user['token'])['id']
        fixtures['users'][0]['id'] = user['id']; write(fixture_file, fixtures)
        for md5, doc in documents.items():
            fixtures['documents'].append({'fileMd5': md5}); write(fixture_file, fixtures); upload(md5, doc, user)
        print('Uploaded', len(documents), 'private fixtures; waiting for indexing', flush=True)
        before = wait_indexed(documents, user['id'], cleanup.sql)
        write(args.output / 'index-before.json', before)
        classpath = 'target/classes:' + (ROOT / 'target/rag-eval-classpath.txt').read_text().strip()
        code = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
        source_hash = hashlib.sha256()
        for path in sorted(Path('src/main/java').rglob('*.java')) + [Path('pom.xml')]:
            source_hash.update(str(path).encode()); source_hash.update(path.read_bytes())
        code += '+' + source_hash.hexdigest()
        with (args.output / 'live.log').open('w') as log:
            process = subprocess.run(['java', '-cp', classpath, 'com.yizhaoqi.smartpai.rag.eval.EvaluationScenarioApplication', str(args.output / 'protocol.json'), str(args.output / 'run'),
                            '--ai.rag.rewrite-enabled=false', '--ai.rag.evidence-directory=' + str(args.output / 'evidence'), '--ai.rag.code-version=' + code,
                            '--ai.generation.temperature=0'], env={**os.environ, 'AI_RAG_EVAL_IDENTITY': 'id:' + str(user['id'])}, stdout=log, stderr=subprocess.STDOUT, check=False)
        after = wait_indexed(documents, user['id'], cleanup.sql)
        write(args.output / 'index-after.json', after)
        if before != after: raise RuntimeError('Corpus changed during native benchmark')
        rows = [json.loads(line) for line in (args.output / 'run/results.jsonl').read_text().splitlines()]
        predictions = {row['id'][3:]: row.get('answer', '') for row in rows if row['id'].startswith('zh-')}; write(args.output / 'dureader-predictions.json', predictions)
        subprocess.run([args.python, 'scripts/rag-eval-benchmarks.py', 'dureader', str(args.output / 'dureader-reference.json'), str(args.output / 'dureader-predictions.json'), str(args.output / 'dureader-score.json'), '--scorer', str(args.sources / 'dureader-evaluate.py'), '--setting', 'private-shared-5-context-dev-subset'], check=True)
        mapping = json.loads((args.output / 'scifact-mapping.json').read_text())
        for variant in ['HYBRID', 'BM25']:
            ranked = {}
            for row in rows:
                if not row['id'].endswith('-' + variant): continue
                ranked[row['id'].rsplit('-', 1)[0]] = {}
                for rank, chunk in enumerate(row.get('topChunks', [])):
                    doc = mapping[chunk['fileMd5']]
                    ranked[row['id'].rsplit('-', 1)[0]].setdefault(doc, float(10000 - rank))
            run_file = args.output / (variant + '-run.json'); write(run_file, ranked)
            subprocess.run([args.python, 'scripts/rag-eval-benchmarks.py', 'beir', str(args.output / 'scifact-qrels.tsv'), str(run_file), str(args.output / (variant + '-score.json')), '--k', '1,3,5,10', '--setting', 'private-scifact-6queries-all-positive-plus40-other-docs-subset'], check=True)
        write(args.output / 'verification.json', {'sourceVersion': code, 'protocolSha256': sha((args.output / 'protocol.json').read_bytes()),
            'corpusBeforeAfterIdentical': True, 'documents': len(documents), 'chunks': len(before['chunks']),
            'indexSnapshotSha256': sha((args.output / 'index-before.json').read_bytes()), 'setting': protocol['setting'],
            'ranking': 'Top10 chunks deduplicated to source documents with original rank order; no answer generation for BEIR',
            'answerSource': 'Original full generated answer; no extraction or post-hoc editing',
            'executionExitCode': process.returncode, 'executionStatus': json.loads((args.output / 'run/summary.json').read_text())['executionStatus']})
        print('Native smoke completed; retained corpus snapshots and all original outputs; no tuning')
        if process.returncode: raise RuntimeError('Native scores retained, but one or more executions/assertions failed')
    finally:
        if user.get('token'):
            for doc in fixtures['documents']: api('documents/' + doc['fileMd5'], user['token'], method='DELETE')
        cleanup.cleanup(args.output)


if __name__ == '__main__': main()
