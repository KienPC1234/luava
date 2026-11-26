#!/usr/bin/env bash
# Sequential fixed-commit Stage 0 A/B runner with full SHA and artifact checksum provenance.
# Usage: ./run_ab.sh [baseline_commit] [candidate_commit] [mode]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
BASE_REF="${1:-0f0a935}"
CAND_REF="${2:-HEAD}"
MODE="${3:-ab}"

BASE_SHA="$(git -C "$ROOT_DIR" rev-parse "$BASE_REF")"
BASE_SHORT="$(git -C "$ROOT_DIR" rev-parse --short "$BASE_SHA")"
CAND_SHA="$(git -C "$ROOT_DIR" rev-parse "$CAND_REF")"

BASE_OUT="$SCRIPT_DIR/results/baseline-${BASE_SHORT}"
CAND_OUT="$SCRIPT_DIR/results/head"

TMP_ROOT="$(mktemp -d /tmp/luava-stage0-ab.XXXXXX)"
cleanup() {
    git -C "$ROOT_DIR" worktree remove --force "$TMP_ROOT/base" >/dev/null 2>&1 || true
    git -C "$ROOT_DIR" worktree remove --force "$TMP_ROOT/cand" >/dev/null 2>&1 || true
    rm -rf "$TMP_ROOT"
}
trap cleanup EXIT

run_fixed_commit() {
    local wt_dir="$1"
    local sha="$2"
    local out_dir="$3"

    git -C "$ROOT_DIR" worktree add --detach "$wt_dir" "$sha"
    local ver
    ver="$(mvn -B -ntp -f "$wt_dir/pom.xml" help:evaluate -Dexpression=project.version -q -DforceStdout)"
    mvn -B -ntp -f "$wt_dir/pom.xml" clean install -DskipTests
    local engine_jar="$wt_dir/target/luava-${ver}.jar"

    # Package current standalone JMH harness against the exact installed engine artifact
    mvn -B -ntp -f "$SCRIPT_DIR/pom.xml" clean package -Dluava.version="$ver"
    local jmh_jar="$SCRIPT_DIR/target/jmh-benchmarks.jar"

    mkdir -p "$out_dir"
    echo "=== Running Stage 0 ($MODE) sequentially for fixed commit $sha ==="
    java -Xms512m -Xmx2g -XX:+UseG1GC \
        -Dluava.bench.treeClean=true \
        -Dluava.bench.engineJar="$engine_jar" \
        -Dluava.bench.repoRoot="$wt_dir" \
        -cp "$jmh_jar" \
        org.luava.bench.Stage0Runner "$out_dir" "$sha" "$MODE"
}

run_fixed_commit "$TMP_ROOT/base" "$BASE_SHA" "$BASE_OUT"
run_fixed_commit "$TMP_ROOT/cand" "$CAND_SHA" "$CAND_OUT"

# Restore local Maven repository artifact from current workspace root
ROOT_VER="$(mvn -B -ntp -f "$ROOT_DIR/pom.xml" help:evaluate -Dexpression=project.version -q -DforceStdout)"
mvn -B -ntp -f "$ROOT_DIR/pom.xml" install -DskipTests
mvn -B -ntp -f "$SCRIPT_DIR/pom.xml" package -Dluava.version="$ROOT_VER"

echo "Sequential A/B complete:"
echo "  Baseline ($BASE_SHA) -> $BASE_OUT"
echo "  Candidate ($CAND_SHA) -> $CAND_OUT"
