#!/usr/bin/env bash
# =============================================================================
# interleave.sh — paired interleaved A/B benchmark harness (Luava perf discipline)
#
# Usage:
#   interleave.sh <jarA> <jarB> [pairs] [warm] [iters] [core] [tasks...]
#
# Runs A,B,A,B,... (order flipped each pair) over the task list, taskset-pinned,
# and prints the MEDIAN best-of-iters ratio B/A per task. Accept only >=3% delta.
#
# Bench class (benchmarks/harness/Bench.java) must be compiled against the jar.
# Coroutine task must NOT be pinned (virtual-thread pool); this script skips
# pinning when the task name contains "coroutine" or CORE is "none".
# =============================================================================
set -euo pipefail

HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LUA_DIR="$(cd "$HARNESS_DIR/../lua" && pwd)"
JAR_A="${1:?jarA required}"
JAR_B="${2:?jarB required}"
PAIRS="${3:-7}"
WARM="${4:-5}"
ITERS="${5:-8}"
CORE="${6:-none}"
shift 6 || true

if [ "$#" -gt 0 ]; then
    TASKS=("$@")
else
    TASKS=(01_arith_loop 02_fibonacci 03_table_ops 04_string_concat 05_closures \
           06_coroutines 07_hash_table 08_oop_metatables 09_string_pattern 10_sieve)
fi

CP="$HARNESS_DIR"
parse() { grep -oE 'best=[ ]*[0-9]+\.[0-9]+' | grep -oE '[0-9]+\.[0-9]+'; }

run_one() { # jar task -> best ms
    local jar="$1" task="$2"
    local file="$LUA_DIR/$task.lua"
    local pre=() main=Bench
    case "$jar" in *luaj*) main=LuaJBench ;; esac
    case "$task" in
        *coroutine*|*Coroutine*) pre=() ;;
        *) [ "$CORE" != "none" ] && pre=(taskset -c "$CORE") ;;
    esac
    "${pre[@]}" java ${LUAVA_JIT_FLAGS:-} ${LUAVA_JIT_SYNC:-} -XX:+UseG1GC -Xmx1g -cp "$jar:$CP" "$main" "$file" "$WARM" "$ITERS" 2>/dev/null | parse
}

median() { tr ' ' '\n' | sort -n | awk '{a[NR]=$1} END{ if(NR%2) print a[(NR+1)/2]; else printf "%.3f", (a[NR/2]+a[NR/2+1])/2 }'; }

printf "jarA=%s\njarB=%s\npairs=%s warm=%s iters=%s core=%s\n\n" "$JAR_A" "$JAR_B" "$PAIRS" "$WARM" "$ITERS" "$CORE"
printf "%-20s %10s %10s %8s\n" "task" "A(ms)" "B(ms)" "B/A"

for task in "${TASKS[@]}"; do
    [ -f "$LUA_DIR/$task.lua" ] || { echo "skip missing $task"; continue; }
    as=""; bs=""
    for ((p=0;p<PAIRS;p++)); do
        if (( p % 2 == 0 )); then
            a=$(run_one "$JAR_A" "$task"); b=$(run_one "$JAR_B" "$task")
        else
            b=$(run_one "$JAR_B" "$task"); a=$(run_one "$JAR_A" "$task")
        fi
        as="$as $a"; bs="$bs $b"
    done
    am=$(echo "$as" | median); bm=$(echo "$bs" | median)
    ratio=$(echo "$bm $am" | awk '{ if ($2>0) printf "%.3f", $1/$2; else print "inf" }')
    printf "%-20s %10s %10s %8s\n" "$task" "$am" "$bm" "$ratio"
done
