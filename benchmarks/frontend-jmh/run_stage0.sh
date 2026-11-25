#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
MODE="${1:-quick}"
TAG="${2:-baseline}"

cd "$ROOT_DIR"
mvn -B -ntp package -DskipTests
mvn -B -ntp -f "$SCRIPT_DIR/pom.xml" package

PROJECT_VER="$(sed -n 's|.*<version>\([^<]*\)</version>.*|\1|p' "$ROOT_DIR/pom.xml" | head -n 1)"
LUAVA_JAR="$ROOT_DIR/target/luava-${PROJECT_VER}.jar"
if [ ! -f "$LUAVA_JAR" ]; then
    echo "Missing built jar: $LUAVA_JAR" >&2
    exit 1
fi
JMH_JAR="$SCRIPT_DIR/target/jmh-benchmarks.jar"
GIT_SHA="$(git -C "$ROOT_DIR" rev-parse --short HEAD 2>/dev/null || echo unknown)"
RUN_TS="$(date -u +%Y%m%dT%H%M%SZ)"
OUT_DIR="$ROOT_DIR/benchmarks/harness/results/frontend-${RUN_TS}-${TAG}-${GIT_SHA}"
mkdir -p "$OUT_DIR"

echo "Running Stage 0 JMH suite ($MODE) -> $OUT_DIR"
java -Xms512m -Xmx2g -XX:+UseG1GC -cp "$LUAVA_JAR:$JMH_JAR" \
    org.luava.bench.Stage0Runner "$OUT_DIR" "$GIT_SHA" "$MODE"

echo "Running JFR allocation & CPU profile on compileCold..."
JFR_FILE="$OUT_DIR/compile_cold.jfr"
java -Xms512m -Xmx2g -XX:+UseG1GC -Dluava.jit=true -Dluava.jit.sync=true \
    -XX:StartFlightRecording=filename="$JFR_FILE",settings=profile,dumponexit=true \
    -cp "$LUAVA_JAR:$JMH_JAR" \
    org.openjdk.jmh.Main -f 0 -wi 2 -i 3 -w 500ms -r 500ms \
    "org.luava.bench.FrontendPipelineBenchmark\.compileCold" \
    -p workload=large_600kb,many_locals,escaped_strings >/dev/null

jfr summary "$JFR_FILE" > "$OUT_DIR/jfr_summary.txt" || true
jfr print --events jdk.ObjectAllocationInNewTLAB,jdk.ObjectAllocationOutsideTLAB "$JFR_FILE" 2>/dev/null \
    | grep -E "objectClass =|allocationSize =" | head -n 80 > "$OUT_DIR/jfr_alloc_sample.txt" || true

echo "Done. Results in $OUT_DIR"
