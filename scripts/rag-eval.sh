#!/usr/bin/env bash
set -euo pipefail
if [[ $# -ne 3 ]]; then
  echo 'Usage: scripts/rag-eval.sh cases.jsonl artifact-directory output-directory' >&2
  exit 2
fi
cd "$(dirname "${BASH_SOURCE[0]}")/.."
mvn -q -DskipTests compile dependency:build-classpath -Dmdep.outputFile=target/rag-eval-classpath.txt
rag_classpath="$(cat target/rag-eval-classpath.txt)"
exec java -cp "target/classes:${rag_classpath}" com.yizhaoqi.smartpai.rag.eval.EvaluationRunner "$@"
