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
javac -cp "$HARNESS_DIR/luaj-jse.jar" -d "$HARNESS_DIR" "$HARNESS_DIR/LuaJBench.java"

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

printf "jar=%s\npairs=%s warm=%s iters=%s core=%s mode=%s\n\n" "$JAR" "$PAIRS" "$WARM" "$ITERS" "$CORE" "$MODE"
printf "%-20s | %8s %6s | %8s %6s | %7s | %8s | %9s | %s\n" \
    "task" "LuavaMed" "IQR" "LuaCMed" "IQR" "LuaC/Lv" "Cold(ms)" "Alloc(KB)" "JIT(hits/deopt)"
printf "%-20s-+-%8s-%6s-+-%8s-%6s-+-%7s-+-%8s-+-%9s-+-%s\n" \
    "--------------------" "--------" "------" "--------" "------" "-------" "--------" "---------" "---------------"

for task in "${TASKS[@]}"; do
    [ -f "$LUA_DIR/$task.lua" ] || { echo "skip missing $task"; continue; }
    lv_meds=""
    lc_meds=""
    last_cold=""
    last_alloc=""
    last_jit=""
    for ((p=0; p<PAIRS; p++)); do
        if (( p % 2 == 0 )); then
            lv_line=$(run_luava "$task")
            lc_line=$(run_luac "$task")
        else
            lc_line=$(run_luac "$task")
            lv_line=$(run_luava "$task")
        fi
        lv_meds="$lv_meds $(echo "$lv_line" | extract median)"
        lc_meds="$lc_meds $(echo "$lc_line" | extract median)"
        last_cold=$(echo "$lv_line" | extract cold)
        last_alloc=$(echo "$lv_line" | extract allocKB)
        jh=$(echo "$lv_line" | extract jitHits)
        jd=$(echo "$lv_line" | extract deopts)
        last_jit="${jh}/${jd}"
    done
    read -r lv_med lv_iqr <<< "$(echo "$lv_meds" | stats_median_iqr)"
    read -r lc_med lc_iqr <<< "$(echo "$lc_meds" | stats_median_iqr)"
    ratio=$(awk -v lc="$lc_med" -v lv="$lv_med" 'BEGIN { if (lv > 0) printf "%.2fx", lc/lv; else print "inf" }')
    printf "%-20s | %8s %6s | %8s %6s | %7s | %8s | %9s | %s\n" \
        "$task" "$lv_med" "$lv_iqr" "$lc_med" "$lc_iqr" "$ratio" "$last_cold" "$last_alloc" "$last_jit"
done
