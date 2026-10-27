#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
usage() {
  cat >&2 <<'HELP'
Usage:
  scripts/rag-eval.sh validate DATASET
  scripts/rag-eval.sh check DATASET NEW_OUTPUT [Spring arguments...]
  scripts/rag-eval.sh run DATASET NEW_OUTPUT [Spring arguments...]
  scripts/rag-eval.sh report DATASET PREFLIGHT_JSON CAPTURE_JSON NEW_OUTPUT [REVIEWS_JSONL]
  scripts/rag-eval.sh upload DATASET BASE_URL
  scripts/rag-eval.sh convert LEGACY_JSON_OR_JSONL OUTPUT_JSONL
  scripts/rag-eval.sh import hotpotqa|ultrarag INPUT NEW_DATASET --source-url URL --revision REV [options]
  scripts/rag-eval.sh study prepare|tune|validate [arguments...]
  scripts/rag-eval.sh replay RUN GRID_JSON NEW_OUTPUT
  scripts/rag-eval.sh review RUN... --output NEW_OUTPUT
  scripts/rag-eval.sh matrix DATASET NEW_OUTPUT [--repeats N] [--support] [--policy FILE] [--resume] [--spring-arg=ARG]
  scripts/rag-eval.sh rerank DATASET NEW_OUTPUT [--repeats N] [--support] [--policy FILE] [--resume] [--spring-arg=ARG]
  scripts/rag-eval.sh compare BASELINE_RUN CANDIDATE_RUN NEW_OUTPUT [--policy FILE] [--allow-code-change]
Live check/run require AI_RAG_EVAL_IDENTITY and deployment configuration.
ACL preflight also needs AI_RAG_EVAL_SETUP_IDENTITY (authorized restricted-document uploader).
Upload reads YERAG_EVAL_TOKEN and YERAG_EVAL_SETUP_TOKEN from environment; never pass tokens in arguments.
HELP
  exit 2
}
[[ $# -ge 1 ]] || usage
rag_action="$1"
shift
case "$rag_action" in
  upload) [[ $# -eq 2 ]] || usage; exec python3 scripts/rag-eval-upload.py "$@" ;;
  convert) [[ $# -eq 2 ]] || usage; exec python3 scripts/rag-eval-convert.py "$@" ;;
  import) exec python3 scripts/rag-eval-import.py "$@" ;;
  study) exec python3 scripts/rag-eval-study.py "$@" ;;
  review) exec python3 scripts/rag-eval-review.py "$@" ;;
  matrix|rerank|compare) exec python3 scripts/rag-eval-experiment.py "$rag_action" "$@" ;;
  validate) [[ $# -eq 1 ]] || usage ;;
  report) [[ $# -ge 4 && $# -le 5 ]] || usage ;;
  replay) [[ $# -eq 3 ]] || usage ;;
  check|run) [[ $# -ge 2 ]] || usage ;;
  *) usage ;;
esac
if [[ "$rag_action" == check || "$rag_action" == run ]]; then
  rag_has_identity="${AI_RAG_EVAL_IDENTITY:-}"
  for rag_argument in "$@"; do
    if [[ "$rag_argument" == --ai.rag.eval.identity=* ]]; then rag_has_identity="${rag_argument#*=}"; fi
  done
  if [[ -z "$rag_has_identity" ]]; then
    echo 'Set AI_RAG_EVAL_IDENTITY or --ai.rag.eval.identity=id:.../name:...; no services contacted.' >&2
    exit 2
  fi
fi
if [[ "${RAG_EVAL_SKIP_BUILD:-0}" != 1 ]]; then
  mvn -q -DskipTests compile dependency:build-classpath -Dmdep.outputFile=target/rag-eval-classpath.txt
fi
[[ -f target/rag-eval-classpath.txt ]] || { echo 'Build the evaluation classpath before offline replay.' >&2; exit 2; }
rag_classpath="target/classes:$(cat target/rag-eval-classpath.txt)"
if [[ "$rag_action" == validate ]]; then
  exec java -cp "$rag_classpath" com.yizhaoqi.smartpai.rag.eval.EvaluationDatasetCheck "$@"
elif [[ "$rag_action" == report ]]; then
  exec java -cp "$rag_classpath" com.yizhaoqi.smartpai.rag.eval.EvaluationRunner "$@"
elif [[ "$rag_action" == replay ]]; then
  exec java -cp "$rag_classpath" com.yizhaoqi.smartpai.rag.eval.EvaluationRankReplay "$@"
fi
rag_dataset="$1"
rag_output="$2"
shift 2
rag_corpus_version="$(python3 -c 'import json,sys,pathlib; print(json.loads((pathlib.Path(sys.argv[1])/"dataset.json").read_text())["corpusVersion"])' "$rag_dataset")"
rag_code_version="$(git rev-parse HEAD)"
rag_source_hash="$(python3 -c 'import hashlib,pathlib; h=hashlib.sha256(); paths=sorted(pathlib.Path("src/main/java").rglob("*.java"))+[pathlib.Path("pom.xml")]; [(h.update(str(p).encode()),h.update(p.read_bytes())) for p in paths]; print(h.hexdigest())')"
exec java -cp "$rag_classpath" com.yizhaoqi.smartpai.rag.eval.EvaluationApplication \
  --ai.rag.eval.enabled=true --ai.rag.eval.action="$rag_action" \
  --ai.rag.eval.dataset="$rag_dataset" --ai.rag.eval.output="$rag_output" \
  --ai.rag.corpus-version="$rag_corpus_version" --ai.rag.code-version="$rag_code_version+$rag_source_hash" \
  --ai.generation.temperature=0 "$@"
