# Luava vs Native Lua 5.4 — Benchmark Report

Generated: 2026-09-11 20:31:07


## Cold Start (ms)

First execution, no warmup. For Luava this includes JVM class-loading + AST eval pass 1. For native Lua it includes process startup.


| # | Task | Lua 5.4 (C) cold | Luava cold | Ratio (Luava/Lua) |
|---|------|:---:|:---:|:---:|
| 02 | Fibonacci fib(35) | 797.33 | 10535.67 | 🔴 13.21x |
| 05 | Closures (100k alloc) | 38.50 | 178.50 | 🟡 4.64x |
| 06 | Coroutine Yield (200k) | 43.35 | 4252.89 | 🔴 98.10x |
| 10 | Sieve 2M | 170.71 | 2289.08 | 🔴 13.41x |


## Hot Steady-State (ms) — avg of 10 runs

For Luava: post JVM C2 JIT compilation (15 warmup rounds). For Lua: 10 fresh process runs averaged.


| # | Task | Lua 5.4 (C) hot avg | Luava hot avg | Luava hot min | Luava hot max | Ratio (Luava/Lua) |
|---|------|:---:|:---:|:---:|:---:|:---:|
| 02 | Fibonacci fib(35) | 794.76 | 11350.98 | 11116.92 | 11826.94 | 🔴 14.28x |
| 05 | Closures (100k alloc) | 36.84 | 155.11 | 154.21 | 156.55 | 🟡 4.21x |
| 06 | Coroutine Yield (200k) | 43.80 | 4977.16 | 4701.28 | 5282.92 | 🔴 113.63x |
| 10 | Sieve 2M | 166.81 | 1571.88 | 1433.22 | 1732.00 | 🟡 9.42x |


## JVM JIT Effect on Luava (cold → warm → hot)

Shows how much C2 HotSpot compilation improves Luava over time.


| # | Task | Cold (ms) | Warm (ms) | Hot avg (ms) | Speedup cold→hot |
|---|------|:---:|:---:|:---:|:---:|
| 01 | Arithmetic Loop (10M iter) | 299.69 | 362.12 | 349.37 | 0.9x |
| 02 | Fibonacci fib(35) | 10535.67 | 11728.34 | 11350.98 | 0.9x |
| 03 | Table Ops (500k r/w) | 104.27 | 96.26 | 108.23 | 1.0x |
| 04 | String Concat (50k) | 117.93 | 34.20 | 29.27 | 4.0x |
| 05 | Closures (100k alloc) | 178.50 | 153.34 | 155.11 | 1.2x |
| 06 | Coroutine Yield (200k) | 4252.89 | 5041.95 | 4977.16 | 0.9x |
| 07 | Hash Table (200k r/w) | 252.00 | 130.09 | 168.95 | 1.5x |
| 08 | OOP Metatables (500k) | 2039.15 | 1721.50 | 1710.94 | 1.2x |
| 09 | String Pattern (100k gmatch) | 322.67 | 266.48 | 254.82 | 1.3x |
| 10 | Sieve 2M | 2289.08 | 1399.46 | 1571.88 | 1.5x |


## Notes

- **Cold** = single first-ever execution (Luava: includes JVM startup + class loading)

- **Warm** = first run after JIT warmup loop (C2 compiled)

- **Hot avg** = mean of 10 post-warmup runs (Luava) / mean of 10 fresh processes (Lua)

- 🟢 Ratio < 2x  |  🟡 2x–10x  |  🔴 > 10x

- Native Lua cold includes OS process fork + dynamic linker; Luava cold includes JVM startup (shared via `-server` flag)
