#!/usr/bin/env bash
# Run the chain-overhead harnesses (F-TRUST-02), building prerequisites as needed.
#
#   benchmarks/chain-overhead/run.sh python|js|java   one SDK -> out/<sdk>.json
#   benchmarks/chain-overhead/run.sh all              all three, then combine + validate
#                                                     -> benchmarks/latest.json
#
# Works from any directory. Environment overrides:
#   PYTHON=python3.11        interpreter for bench.py / combine.py (needs Python 3.10+)
#   VALIDATE_ARGS=--require-schema   extra flags for validate_latest.py (CI sets this)
#
# Every harness runs even if an earlier one breaches its CI threshold, so you
# always get the full picture; the exit status is non-zero if any harness failed.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
OUT="$HERE/out"
PYTHON="${PYTHON:-python3}"
TARGET="${1:-all}"

JAVA_MODULES=(gavio-core gavio-interceptor-audit gavio-interceptor-pii gavio-interceptor-governance gavio-interceptor-guardrails)

run_python() {
  "$PYTHON" "$HERE/bench.py" --out "$OUT/python.json"
}

run_js() {
  if [ ! -f "$REPO/packages/gavio-js/dist/esm/index.js" ]; then
    (cd "$REPO/packages/gavio-js" && npm ci && npm run build) || return 1
  fi
  node "$HERE/bench.mjs" --out "$OUT/js.json"
}

run_java() {
  local pl classpath=""
  pl="$(IFS=,; echo "${JAVA_MODULES[*]}")"
  mvn -B -q -f "$REPO/packages/gavio-java/pom.xml" -DskipTests -pl "$pl" -am compile || return 1
  for module in "${JAVA_MODULES[@]}"; do
    classpath="${classpath:+$classpath:}$REPO/packages/gavio-java/$module/target/classes"
  done
  # The spec path is repo-relative, so launch from the repo root.
  (cd "$REPO" && java -cp "$classpath" benchmarks/chain-overhead/java/ChainOverheadBench.java --out "$OUT/java.json")
}

case "$TARGET" in
  python | js | java)
    "run_$TARGET"
    exit $?
    ;;
  all)
    status=0
    # Never combine stale results: a harness that crashes must show up as a missing SDK.
    rm -f "$OUT/python.json" "$OUT/js.json" "$OUT/java.json"
    for sdk in python js java; do
      echo ">>> $sdk" >&2
      "run_$sdk" > /dev/null || { echo "!!! $sdk harness failed or breached a threshold" >&2; status=1; }
    done
    "$PYTHON" "$HERE/combine.py" --markdown | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}" || status=1
    # shellcheck disable=SC2086  # VALIDATE_ARGS is intentionally word-split
    "$PYTHON" "$REPO/benchmarks/validate_latest.py" ${VALIDATE_ARGS:-} || status=1
    exit $status
    ;;
  *)
    echo "usage: $0 python|js|java|all" >&2
    exit 2
    ;;
esac
