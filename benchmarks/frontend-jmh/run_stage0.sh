#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
MODE="${1:-quick}"
TAG="${2:-baseline}"

cd "$ROOT_DIR"
LUAVA_VERSION="$(mvn -B -ntp help:evaluate -Dexpression=project.version -q -DforceStdout)"
mvn -B -ntp install -DskipTests
mvn -B -ntp -f "$SCRIPT_DIR/pom.xml" package -Dluava.version="$LUAVA_VERSION"

JMH_JAR="$SCRIPT_DIR/target/jmh-benchmarks.jar"
if [ ! -f "$JMH_JAR" ]; then
    echo "Missing built JMH jar: $JMH_JAR" >&2
    exit 1
fi
GIT_SHA="$(git -C "$ROOT_DIR" rev-parse --short HEAD 2>/dev/null || echo unknown)"
RUN_TS="$(date -u +%Y%m%dT%H%M%SZ)"
OUT_DIR="$ROOT_DIR/benchmarks/harness/results/frontend-${RUN_TS}-${TAG}-${GIT_SHA}"
mkdir -p "$OUT_DIR"

echo "Running Stage 0 JMH suite ($MODE, version $LUAVA_VERSION) -> $OUT_DIR"
java -Xms512m -Xmx2g -XX:+UseG1GC -cp "$JMH_JAR" \
    org.luava.bench.Stage0Runner "$OUT_DIR" "$GIT_SHA" "$MODE"

for WL in large_600kb many_locals escaped_strings; do
    echo "Running isolated JFR profile on frontendCompileOnly ($WL)..."
    JFR_FILE="$OUT_DIR/jfr_${WL}.jfr"
    java -Xms512m -Xmx2g -XX:+UseG1GC -Dluava.jit=true -Dluava.jit.sync=true \
        -XX:StartFlightRecording=filename="$JFR_FILE",settings=profile,dumponexit=true \
        -jar "$JMH_JAR" -f 0 -wi 2 -i 3 -w 500ms -r 500ms \
        "org.luava.bench.FrontendPipelineBenchmark\.frontendCompileOnly" \
        -p workload="$WL" >/dev/null

    jfr summary "$JFR_FILE" > "$OUT_DIR/jfr_${WL}_summary.txt" || true
    jfr print --events jdk.ObjectAllocationInNewTLAB,jdk.ObjectAllocationOutsideTLAB "$JFR_FILE" 2>/dev/null \
        | grep -E "objectClass =|allocationSize =" | head -n 80 > "$OUT_DIR/jfr_${WL}_alloc_sample.txt" || true
done

echo "Done. Results in $OUT_DIR"
