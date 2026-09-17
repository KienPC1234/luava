#!/usr/bin/env bash
# =============================================================================
# Luava vs Native Lua 5.4 Comprehensive Benchmark Suite
# Measures: cold_start, warm (post-JIT), hot_avg, hot_min, hot_max
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
LUA_DIR="$SCRIPT_DIR/lua"
RUNNER_DIR="$SCRIPT_DIR/runner"
RESULTS_DIR="$SCRIPT_DIR/results"
LUAVA_JAR="$PROJECT_ROOT/target/luava-1.0.0-SNAPSHOT.jar"

# Native Lua binary
LUA_BIN="${LUA_BIN:-$(command -v lua5.4 || command -v lua || echo "lua")}"

# Measurement config
NATIVE_COLD_RUNS=1        # cold = process start, single run
NATIVE_HOT_RUNS=10        # average over 10 hot runs for native Lua

mkdir -p "$RESULTS_DIR"

TIMESTAMP=$(date '+%Y%m%d_%H%M%S')
RAW_LUAVA="$RESULTS_DIR/luava_${TIMESTAMP}.csv"
RAW_NATIVE="$RESULTS_DIR/native_${TIMESTAMP}.csv"
REPORT="$RESULTS_DIR/benchmark_report_${TIMESTAMP}.md"

# ── Helpers ───────────────────────────────────────────────────────────────────

log() { echo -e "\033[1;36m$*\033[0m"; }
err() { echo -e "\033[1;31m$*\033[0m" >&2; }

measure_native_ms() {
    # Returns elapsed ms for running a Lua file with native interpreter
    local lua_file="$1"
    local start end elapsed
    start=$(date +%s%N)
    "$LUA_BIN" "$lua_file" 2>/dev/null
    end=$(date +%s%N)
    echo "scale=3; ($end - $start) / 1000000" | bc
}

# ── Phase 0: Validate Environment ─────────────────────────────────────────────

log "================================================================"
log "  LUAVA vs Lua 5.4 (native C) Comprehensive Benchmark Suite"
log "================================================================"
echo
log "System:"
echo "  Machine  : $(uname -m), $(nproc) cores"
echo "  CPU      : $(grep 'model name' /proc/cpuinfo | head -1 | cut -d: -f2 | xargs)"
echo "  Memory   : $(free -h | awk '/^Mem:/{print $2}')"
echo "  Java     : $(java -version 2>&1 | head -1)"
echo "  Lua      : $LUA_BIN -> $("$LUA_BIN" -v 2>&1 | head -1)"
echo "  Luava JAR: $LUAVA_JAR"
echo "  Timestamp: $TIMESTAMP"
echo

# ── Phase 1: Compile BenchmarkRunner ──────────────────────────────────────────

log "[Phase 1] Compiling BenchmarkRunner.java ..."
javac -cp "$LUAVA_JAR" -d "$RUNNER_DIR" "$RUNNER_DIR/BenchmarkRunner.java"
log "  Done."
echo

# ── Phase 2: Run Native Lua benchmarks ────────────────────────────────────────

log "[Phase 2] Running Native Lua 5.4 benchmarks ..."
echo "task_id,task_name,cold_ms,warm_ms,hot_avg_ms,hot_min_ms,hot_max_ms" > "$RAW_NATIVE"

for lua_file in "$LUA_DIR"/[0-9]*.lua; do
    fname=$(basename "$lua_file")
    task_id=$(echo "$fname" | sed 's/_[^_]*\.lua$//' | sed 's/\.lua$//')
    task_name=$(echo "$fname" | sed 's/^[0-9]*_//' | sed 's/\.lua$//' | sed 's/_/ /g')

    printf "  %-12s %-22s " "[$task_id]" "$task_name"

    # Cold: single fresh process start (this IS the cold start for native Lua)
    cold_ms=$(measure_native_ms "$lua_file")

    # "Warm" for native Lua = 2nd run in-process equivalent → we do it as another process
    # (native Lua has no JIT warmup concept, so warm ≈ cold for pure C)
    warm_ms=$(measure_native_ms "$lua_file")

    # Hot average: $NATIVE_HOT_RUNS fresh processes
    hot_times=()
    for _ in $(seq 1 $NATIVE_HOT_RUNS); do
        t=$(measure_native_ms "$lua_file")
        hot_times+=("$t")
    done

    # Compute avg, min, max in bc
    all_times="${hot_times[*]}"
    hot_avg=$(echo "${hot_times[@]}" | tr ' ' '\n' | awk '{s+=$1; n++} END{printf "%.3f", s/n}')
    hot_min=$(echo "${hot_times[@]}" | tr ' ' '\n' | awk 'NR==1{m=$1} {if($1<m)m=$1} END{printf "%.3f", m}')
    hot_max=$(echo "${hot_times[@]}" | tr ' ' '\n' | awk 'NR==1{m=$1} {if($1>m)m=$1} END{printf "%.3f", m}')

    printf "cold=%6.2f  hot_avg=%6.2f  min=%6.2f  max=%6.2f ms\n" \
        "$cold_ms" "$hot_avg" "$hot_min" "$hot_max"

    echo "${task_id},${task_name},${cold_ms},${warm_ms},${hot_avg},${hot_min},${hot_max}" >> "$RAW_NATIVE"
done
echo

# ── Phase 3: Run Luava benchmarks ─────────────────────────────────────────────

log "[Phase 3] Running Luava benchmarks (cold → JIT warmup → hot) ..."
java \
    -XX:+UseG1GC \
    -Xmx1g \
    -cp "$LUAVA_JAR:$RUNNER_DIR" \
    BenchmarkRunner "$LUA_DIR" \
    2>&1 | tee /dev/stderr | grep -v '^\[' | grep -v '^\s' \
    > "$RAW_LUAVA" || true

# Re-run cleanly: stderr for progress, stdout for CSV
java \
    -XX:+UseG1GC \
    -Xmx1g \
    -cp "$LUAVA_JAR:$RUNNER_DIR" \
    BenchmarkRunner "$LUA_DIR" \
    1>"$RAW_LUAVA" \
    2>/dev/null

log "  Done. CSV saved → $RAW_LUAVA"
echo

# ── Phase 4: Generate Markdown Report ─────────────────────────────────────────

log "[Phase 4] Generating report ..."

python3 - "$RAW_NATIVE" "$RAW_LUAVA" "$REPORT" <<'PYEOF'
import sys, csv, math

native_file, luava_file, report_file = sys.argv[1], sys.argv[2], sys.argv[3]

def read_csv(path):
    rows = {}
    with open(path) as f:
        for row in csv.DictReader(f):
            rows[row['task_id']] = row
    return rows

native = read_csv(native_file)
luava  = read_csv(luava_file)

task_labels = {
    "01": ("01_arith_loop",   "Arithmetic Loop (10M iter)"),
    "02": ("02_fibonacci",    "Fibonacci fib(35)"),
    "03": ("03_table_ops",    "Table Ops (500k r/w)"),
    "04": ("04_string_concat","String Concat (50k)"),
    "05": ("05_closures",     "Closures (100k alloc)"),
    "06": ("06_coroutines",   "Coroutine Yield (200k)"),
    "07": ("07_hash_table",   "Hash Table (200k r/w)"),
    "08": ("08_oop_metatables","OOP Metatables (500k)"),
    "09": ("09_string_pattern","String Pattern (100k gmatch)"),
    "10": ("10_sieve",        "Sieve 2M"),
}

lines = []
lines.append("# Luava vs Native Lua 5.4 — Benchmark Report\n")
lines.append(f"Generated: {__import__('datetime').datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n\n")

# -- Cold Start Table --
lines.append("## Cold Start (ms)\n")
lines.append("First execution, no warmup. For Luava this includes JVM class-loading + AST eval pass 1. For native Lua it includes process startup.\n\n")
lines.append("| # | Task | Lua 5.4 (C) cold | Luava cold | Ratio (Luava/Lua) |")
lines.append("|---|------|:---:|:---:|:---:|")

for tid, (fid, label) in task_labels.items():
    n_row = native.get(fid) or native.get(tid)
    l_row = luava.get(fid) or luava.get(tid)
    if not n_row or not l_row:
        continue
    nc = float(n_row['cold_ms'])
    lc = float(l_row['cold_ms'])
    ratio = lc / nc if nc > 0 else float('inf')
    flag = "🔴" if ratio > 5 else ("🟡" if ratio > 2 else "🟢")
    lines.append(f"| {tid} | {label} | {nc:.2f} | {lc:.2f} | {flag} {ratio:.2f}x |")

# -- Hot Steady-State Table --
lines.append("\n\n## Hot Steady-State (ms) — avg of 10 runs\n")
lines.append("For Luava: post JVM C2 JIT compilation (15 warmup rounds). For Lua: 10 fresh process runs averaged.\n\n")
lines.append("| # | Task | Lua 5.4 (C) hot avg | Luava hot avg | Luava hot min | Luava hot max | Ratio (Luava/Lua) |")
lines.append("|---|------|:---:|:---:|:---:|:---:|:---:|")

for tid, (fid, label) in task_labels.items():
    n_row = native.get(fid) or native.get(tid)
    l_row = luava.get(fid) or luava.get(tid)
    if not n_row or not l_row:
        continue
    nh = float(n_row['hot_avg_ms'])
    lh = float(l_row['hot_avg_ms'])
    lmin = float(l_row['hot_min_ms'])
    lmax = float(l_row['hot_max_ms'])
    ratio = lh / nh if nh > 0 else float('inf')
    flag = "🔴" if ratio > 10 else ("🟡" if ratio > 3 else "🟢")
    lines.append(f"| {tid} | {label} | {nh:.2f} | {lh:.2f} | {lmin:.2f} | {lmax:.2f} | {flag} {ratio:.2f}x |")

# -- JVM Warmup Effect Table --
lines.append("\n\n## JVM JIT Effect on Luava (cold → warm → hot)\n")
lines.append("Shows how much C2 HotSpot compilation improves Luava over time.\n\n")
lines.append("| # | Task | Cold (ms) | Warm (ms) | Hot avg (ms) | Speedup cold→hot |")
lines.append("|---|------|:---:|:---:|:---:|:---:|")

for tid, (fid, label) in task_labels.items():
    l_row = luava.get(fid) or luava.get(tid)
    if not l_row:
        continue
    lc  = float(l_row['cold_ms'])
    lw  = float(l_row['warm_ms'])
    lh  = float(l_row['hot_avg_ms'])
    speedup = lc / lh if lh > 0 else float('inf')
    lines.append(f"| {tid} | {label} | {lc:.2f} | {lw:.2f} | {lh:.2f} | {speedup:.1f}x |")

lines.append("\n\n## Notes\n")
lines.append("- **Cold** = single first-ever execution (Luava: includes JVM startup + class loading)\n")
lines.append("- **Warm** = first run after JIT warmup loop (C2 compiled)\n")
lines.append("- **Hot avg** = mean of 10 post-warmup runs (Luava) / mean of 10 fresh processes (Lua)\n")
lines.append("- 🟢 Ratio < 2x  |  🟡 2x–10x  |  🔴 > 10x\n")
lines.append("- Native Lua cold includes OS process fork + dynamic linker; Luava cold includes JVM startup (shared via `-server` flag)\n")

with open(report_file, 'w') as f:
    f.write('\n'.join(lines))

print(f"Report written to: {report_file}")
PYEOF

log "================================================================"
log "  DONE — Report: $REPORT"
log "================================================================"
echo
cat "$REPORT"

