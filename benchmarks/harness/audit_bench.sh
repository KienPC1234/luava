#!/usr/bin/env bash
# =============================================================================
# audit_bench.sh — Standardized multi-engine interleaved benchmark harness
# Implements BENCH-01..06:
#   - Apples-to-apples exec (precompiled chunk) or e2e (source load+exec)
#   - Alternating paired trials (>=11 recommended), reporting median, IQR,
#     cold start, allocation bytes/iter, and JIT hits/deopts verification
# Usage:
#   audit_bench.sh [jar] [pairs] [warm] [iters] [core] [mode] [tasks...]
# =============================================================================
set -euo pipefail

exec 9>"${TMPDIR:-/tmp}/luava-bench.lock"
if ! flock -n 9; then
    echo "refusing to measure: another benchmark holds ${TMPDIR:-/tmp}/luava-bench.lock" >&2
    exit 2
fi

HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$HARNESS_DIR/../.." && pwd)"
LUA_DIR="$(cd "$HARNESS_DIR/../lua" && pwd)"

JAR="${1:-$(ls -1 "$ROOT_DIR"/target/luava-*.jar 2>/dev/null | grep -v sources | grep -v javadoc | head -1)}"
PAIRS="${2:-11}"
WARM="${3:-6}"
ITERS="${4:-10}"
CORE="${5:-none}"
MODE="${6:-exec}"
shift 6 || true

if [ "$#" -gt 0 ]; then
    TASKS=("$@")
else
    TASKS=(01_arith_loop 02_fibonacci 03_table_ops 04_string_concat 05_closures \
           06_coroutines 07_hash_table 08_oop_metatables 09_string_pattern 10_sieve)
fi

export LUAVA_JIT_SYNC="${LUAVA_JIT_SYNC:--Dluava.jit.sync=true}"

# Compile C & Java harnesses if needed
gcc -O2 -Wall -Wextra $(pkg-config --cflags lua5.4) "$HARNESS_DIR/lua_c_bench.c" $(pkg-config --libs lua5.4) -lm -o "$HARNESS_DIR/lua_c_bench.out"
javac -cp "$JAR" -d "$HARNESS_DIR" "$HARNESS_DIR/Bench.java"
if [ -f "$HARNESS_DIR/luaj-jse.jar" ] && [ -f "$HARNESS_DIR/LuaJBench.java" ]; then
    javac -cp "$HARNESS_DIR/luaj-jse.jar" -d "$HARNESS_DIR" "$HARNESS_DIR/LuaJBench.java"
fi

GIT_SHA="$(git -C "$ROOT_DIR" rev-parse --short HEAD 2>/dev/null || echo unknown)"
RUN_TS="$(date -u +%Y%m%dT%H%M%SZ)"
OUT_DIR="${BENCH_OUT_DIR:-$HARNESS_DIR/results/${RUN_TS}-${MODE}-${GIT_SHA}}"
mkdir -p "$OUT_DIR"
RAW_CSV="$OUT_DIR/raw_pairs.csv"
META_JSON="$OUT_DIR/metadata.json"

CPU_MODEL="$(awk -F: '/model name/{print $2; exit}' /proc/cpuinfo 2>/dev/null | xargs || echo unknown)"
JDK_VER="$(java -version 2>&1 | head -n 1 | xargs)"
LUAC_VER="$(pkg-config --modversion lua5.4 2>/dev/null || echo unknown)"
JVM_FLAGS="${LUAVA_JIT_FLAGS:-} ${LUAVA_JIT_SYNC:-} -XX:+UseG1GC -Xmx1g"

cat > "$META_JSON" <<EOF
{
  "timestampUtc": "$RUN_TS",
  "gitSha": "$GIT_SHA",
  "jar": "$JAR",
  "mode": "$MODE",
  "pairs": $PAIRS,
  "warmup": $WARM,
  "iterations": $ITERS,
  "corePin": "$CORE",
  "jvmFlags": "$(echo "$JVM_FLAGS" | xargs)",
  "jdkVersion": "${JDK_VER//\"/\\\"}",
  "luaCVersion": "$LUAC_VER",
  "cpuModel": "${CPU_MODEL//\"/\\\"}"
}
EOF

printf "task,pair,order,luava_best_ms,luava_median_ms,luava_iqr_ms,luava_cold_ms,luava_alloc_kb,luava_jit_hits,luava_jit_compiled,luava_deopts,luac_best_ms,luac_median_ms,luac_iqr_ms\n" > "$RAW_CSV"

run_luava() {
    local task="$1"
    local file="$LUA_DIR/$task.lua"
    local pre=()
    case "$task" in
        *coroutine*|*Coroutine*) pre=() ;;
        *) [ "$CORE" != "none" ] && pre=(taskset -c "$CORE") ;;
    esac
    "${pre[@]}" java ${LUAVA_JIT_FLAGS:-} ${LUAVA_JIT_SYNC:-} -XX:+UseG1GC -Xmx1g -cp "$JAR:$HARNESS_DIR" Bench "$file" "$WARM" "$ITERS" "$MODE" 2>/dev/null
}

run_luac() {
    local task="$1"
    local file="$LUA_DIR/$task.lua"
    local pre=()
    case "$task" in
        *coroutine*|*Coroutine*) pre=() ;;
        *) [ "$CORE" != "none" ] && pre=(taskset -c "$CORE") ;;
    esac
    "${pre[@]}" "$HARNESS_DIR/lua_c_bench.out" "$file" "$WARM" "$ITERS" "$MODE" 2>/dev/null
}

extract() {
    local key="$1"
    grep -oE "${key}=[ ]*-?[0-9]+(\.[0-9]+)?" | head -1 | cut -d= -f2 | xargs
}

stats_median_iqr() {
    tr ' ' '\n' | grep -v '^$' | sort -n | awk '
    { a[NR] = $1 }
    END {
        if (NR == 0) { printf "0.00 0.00"; exit }
        if (NR % 2 == 1) med = a[(NR + 1) / 2];
        else med = (a[NR / 2] + a[NR / 2 + 1]) / 2.0;
        p25 = a[int((NR - 1) / 4) + 1];
        p75 = a[int(((NR - 1) * 3) / 4) + 1];
        printf "%.2f %.2f", med, (p75 - p25);
    }'
}

printf "jar=%s\npairs=%s warm=%s iters=%s core=%s mode=%s\nresults=%s\n\n" "$JAR" "$PAIRS" "$WARM" "$ITERS" "$CORE" "$MODE" "$OUT_DIR"
printf "%-20s | %8s %6s | %8s %6s | %7s | %8s | %9s | %s\n" \
    "task" "LuavaMed" "IQR" "LuaCMed" "IQR" "LuaC/Lv" "Cold(ms)" "Alloc(KB)" "JIT(hits/comp/deopt)"
printf "%-20s-+-%8s-%6s-+-%8s-%6s-+-%7s-+-%8s-+-%9s-+-%s\n" \
    "--------------------" "--------" "------" "--------" "------" "-------" "--------" "---------" "--------------------"

for task in "${TASKS[@]}"; do
    [ -f "$LUA_DIR/$task.lua" ] || { echo "skip missing $task"; continue; }
    lv_meds=""
    lc_meds=""
    last_cold=""
    last_alloc=""
    last_jit=""
    for ((p=0; p<PAIRS; p++)); do
        if (( p % 2 == 0 )); then
            order="luava_first"
            lv_line=$(run_luava "$task")
            lc_line=$(run_luac "$task")
        else
            order="luac_first"
            lc_line=$(run_luac "$task")
            lv_line=$(run_luava "$task")
        fi
        lv_best=$(echo "$lv_line" | extract best)
        lv_m=$(echo "$lv_line" | extract median)
        lv_i=$(echo "$lv_line" | extract iqr)
        lc_best=$(echo "$lc_line" | extract best)
        lc_m=$(echo "$lc_line" | extract median)
        lc_i=$(echo "$lc_line" | extract iqr)
        last_cold=$(echo "$lv_line" | extract cold)
        last_alloc=$(echo "$lv_line" | extract allocKB)
        jh=$(echo "$lv_line" | extract jitHits)
        jc=$(echo "$lv_line" | extract jitCompiled)
        jd=$(echo "$lv_line" | extract deopts)
        lv_meds="$lv_meds $lv_m"
        lc_meds="$lc_meds $lc_m"
        last_jit="${jh}/${jc}/${jd}"
        printf "%s,%d,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n" \
            "$task" "$p" "$order" "${lv_best:-0}" "${lv_m:-0}" "${lv_i:-0}" "${last_cold:-0}" "${last_alloc:-0}" "${jh:-0}" "${jc:-0}" "${jd:-0}" "${lc_best:-0}" "${lc_m:-0}" "${lc_i:-0}" >> "$RAW_CSV"
    done
    read -r lv_med lv_iqr <<< "$(echo "$lv_meds" | stats_median_iqr)"
    read -r lc_med lc_iqr <<< "$(echo "$lc_meds" | stats_median_iqr)"
    ratio=$(awk -v lc="$lc_med" -v lv="$lv_med" 'BEGIN { if (lv > 0) printf "%.2fx", lc/lv; else print "inf" }')
    printf "%-20s | %8s %6s | %8s %6s | %7s | %8s | %9s | %s\n" \
        "$task" "$lv_med" "$lv_iqr" "$lc_med" "$lc_iqr" "$ratio" "$last_cold" "$last_alloc" "$last_jit"
done
