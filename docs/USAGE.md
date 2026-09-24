# Luava Usage Guide

A cookbook for embedding the Luava Lua 5.4 engine. Each section is a
self-contained scenario. API details live in the Javadoc; this guide shows how
to do common things.

## Running Lua from the command line

If you only want to run scripts or poke at Lua 5.4 syntax, the engine jar is
executable:

```bash
java -jar luava-0.2.1-beta.jar script.lua arg1 arg2   # run a file (arg[0] = script name)
java -jar luava-0.2.1-beta.jar -e "print(1 + 2)"      # evaluate a string
java -jar luava-0.2.1-beta.jar -i script.lua          # run, then enter the REPL
java -jar luava-0.2.1-beta.jar                        # interactive multi-line REPL
java -jar luava-0.2.1-beta.jar -v                     # version (e.g. "Luava 0.2.1-beta Concord (Lua 5.4)")
java -jar luava-0.2.1-beta.jar -h                     # usage
```

Flags (only the first argument is inspected, so pass flags before the script):

| Flag | Meaning |
| :--- | :--- |
| `-e stat` / `-E stat` | Execute the string `stat` and exit (`-E` is accepted as an alias). |
| `-i` / `--interactive` | Enter the REPL after running the script (or immediately if none). |
| `-v` / `--version` | Print the engine version and code name, then exit. |
| `-h` / `--help` | Print the usage text, then exit. |
| *(none)* | Start the interactive multi-line REPL. |

Running a file exposes the standard Lua `arg` table: `arg[0]` is the script
name and `arg[1..]` the arguments after it (`#arg` matches the reference CLI).

The REPL keeps reading with a `>>` continuation prompt until the chunk is
complete, and each finished expression is printed, so typing `1 + 2` shows `3`.

This is a convenience front end; embedding through `LuaState` (below) is the
primary use and the only way to control sandboxing, JIT and interop.


## Getting a state

```java
import org.luava.runtime.LuaState;

LuaState state = new LuaState();
```

One `LuaState` owns its globals, JIT settings, security policy and finalizer
ownership. Use one state per thread or per tenant; states never share mutable
state. Creating a state is cheap after the first (the stdlib is lazily filled),
but do not create one per call. Keep it and reuse it.

## Evaluating scripts

```java
// Run and ignore the result
state.eval("print('hello')");

// Run and read the result
long answer = state.eval("return 6 * 7").toLong();      // 42

// Give the chunk a name (shows up in error messages / tracebacks)
state.eval(source, "@rules.lua");
```

Compile once, call many times (avoids re-parsing and lets the JIT warm up):

```java
var chunk = state.compile("return a + b", "chunk", state.getGlobals());
state.getGlobals().rawset(LuaString.valueOf("a"), LuaInteger.valueOf(1));
```

`eval` is fine for one-shot scripts, but a server handling many requests should
`compile` the script at startup and reuse the closure (or, better, expose it as
a Lua function and `call` it).

## Passing values between Java and Lua

`set` snapshots the value into Lua; `get` converts back.

```java
state.set("port", 8080);
state.set("name", "luava");
state.set("ratio", 0.5);
state.set("enabled", true);

int    p = state.get("port", Integer.class);
String n = state.get("name", String.class);
```

Java `Map`, `List`, `Set` and arrays passed with `set` become copies (snapshots)
as Lua tables:

```java
state.set("scores", List.of(10, 20, 30));
state.eval("return scores[2]").toLong();   // 20
```

Lua tables passed to a Java method expecting `Map`/`List`/array are converted at
the call boundary. `Object` converts numbers to `Long`/`Double`, booleans to
`Boolean`, strings to `String`, tables to `Map`.

## Calling Lua from Java

```java
state.eval("function greet(name) return 'hi ' .. name end");

LuaValue r = state.call("greet", "world");
String s = r.toLuaString();          // "hi world"
```

`call` looks up a global and invokes it with the boxed arguments. For a Lua
function you already hold as a `LuaValue`, call it directly:

```java
LuaValue fn = state.get("greet");
LuaValue r = fn.call(LuaString.valueOf("world"));
```

Errors surface as `org.luava.runtime.LuaException`. Wrap calls in `pcall` in Lua,
or catch `LuaException` in Java, when the script can fail.

## Exposing Java to Lua

There are four levels, from quickest to most structured.

1. Typed host functions (no manual boxing; arguments are converted to the
   declared types and mismatches raise a Lua error):

```java
state.registerFunction("answer", () -> 42);
state.registerFunction("add", Long.class, Long.class, (a, b) -> a + b);
state.registerFunction("log", String.class, (java.util.function.Consumer<String>) log::info);
state.registerFunction("lookup", String.class, (java.util.function.Function<String, Integer>) this::lookup);
```

2. A raw invokable, when you want full control:

```java
import org.luava.runtime.LuaValue;

state.registerFunction("sum", args -> {
    long total = 0;
    for (LuaValue a : args) total += a.toLong();
    return org.luava.runtime.LuaInteger.valueOf(total);
});
```

3. A live object (see the next section):

```java
state.setLive("service", myService);
```

4. A bound module (see the annotations section).

You can also let a script import a class directly:

```java
state.registerClass(java.util.ArrayList.class);   // exposed as "ArrayList"
state.eval("local l = ArrayList.new() l.add('x') return l.size()");
```

Reflection through `java.import(...)` is filtered by the access policy (see
[untrusted scripts](#running-untrusted-scripts-safely)).

## Live objects (mutations flow both ways)

`setLive` exposes a Java object without copying. Collections and arrays support
indexing and mutation from Lua, and the changes are visible in Java:

```java
List<String> names = new ArrayList<>(List.of("first"));
state.setLive("names", names);

state.eval("names.add('second')");     // List.add
state.eval("names[1] = 'changed'");    // list[1] maps to set(0, ...)
// names == ["changed", "second"]

Map<String, Object> cfg = new HashMap<>();
state.setLive("cfg", cfg);
state.eval("cfg.mode = 'fast'");       // put (creates the key)
// cfg.get("mode") == "fast"

int[] nums = {1, 2, 3};
state.setLive("nums", nums);
state.eval("nums[1] = 99");            // nums[0] == 99
long len = state.eval("return #nums").toLong();   // 3
```

Index assignment on a live `List` uses `List.set`, so it replaces an existing
slot and does not append: `list[1] = x` on an empty list is an out-of-bounds
error. Use `list.add(x)` to grow it. `Map` assignment is a `put`, so it creates
or replaces a key.

For a plain object, scripts read and write public fields and call public methods:

```java
state.setLive("player", player);
state.eval("player.name = 'neo'; player.sendMessage('hi')");
```

Use `setLive` when identity matters; use `set` when you want a snapshot.

## Annotations

For a stable, documented bridge, annotate a class:

```java
import org.luava.binding.annotation.*;

@LuaModule(name = "Vec", description = "2D vector")
public class Vec {
    @LuaField(name = "x", description = "X coordinate")
    public double x;

    @LuaField(name = "y")
    public double y;

    @LuaField(name = "VERSION", description = "build")     // on a getter:
    public String version() { return "1.0"; }              // a computed field

    @LuaMethod(description = "Dot product")
    @LuaReturn(type = "number", description = "self . other")
    public double dot(@LuaParam(name = "other", type = "Vec") Vec o) {
        return x * o.x + y * o.y;
    }

    @LuaMethod(isMethod = true, description = "Length")
    public double length() { return Math.sqrt(x * x + y * y); }
}

state.registerModule(new Vec());
```

```lua
local v = Vec          -- the module is a global
print(v.VERSION)       -- computed field
-- colon call for isMethod=true (self is passed)
print(Vec:length())
```

`@LuaField` on a zero-argument method is a read-only computed field. Binding a
bare `Class` (rather than an instance) can read only static fields; an instance
`@LuaField` then raises a clear error.

## Functional interfaces (SAM)

A Lua function passed where Java expects a single-method interface is adapted
automatically, and a Java SAM object called from Lua forwards to it:

```java
public class Bus {
    public void onEvent(Runnable r) { r.run(); }
    public String map(java.util.function.Function<String, String> f) { return f.apply("x"); }
}

state.setLive("bus", new Bus());
state.eval("bus.onEvent(function() print('clicked') end)");
state.eval("return bus.map(function(s) return s .. '!' end)");   // "x!"

// Java lambda -> Lua callable
state.set("onClick", (Runnable) () -> System.out.println("clicked"));
state.eval("onClick()");
```

Works with `Runnable`, `Consumer<T>`, `Function<T,R>`, `BiFunction<T,U,R>`,
`Predicate<T>`, `Comparator<T>`, and any single-abstract-method interface.
Overload selection scores exact matches above widening above SAM conversion.

## Loading scripts from a JAR or custom source

`require` searches the filesystem by default. Add a virtual loader to resolve
modules packaged in a JAR, a classpath, or any custom store:

```java
import org.luava.runtime.standard.LuaResourceLoader;

LuaState state = new LuaState()
        .resourceLoader(LuaResourceLoader.classpath())
        .resourcePath("scripts/?.lua;scripts/?/init.lua");

state.eval("local rules = require('scripts.rules')");
```

Any functional loader works:

```java
LuaState mem = new LuaState()
        .resourceLoader(path -> path.equals("rules.lua")
                ? "return { answer = 42 }" : null);
```

The loader is consulted after the standard filesystem searchers and is a no-op
when none is registered, so `package.path` semantics are unchanged.

## Running untrusted scripts safely

Combine three one-line guards:

```java
LuaState sandbox = new LuaState()
        .sandbox()                            // drop os/io/package/java, strict policy
        .instructionLimit(10_000_000)         // or .timeout(Duration.ofSeconds(1))
        .maxAllocationBytes(64 * 1024 * 1024);
sandbox.setLive("api", readOnlyService);      // set/setLive/register* return void

try {
    sandbox.eval(userScript);
} catch (org.luava.runtime.LuaException e) {
    // runaway script or a script error; the host survives
}
```

- `sandbox()` removes `os`, `io`, `package`, `require`, `dofile`, `loadfile`,
  `java`/`luajava` and `debug`, and switches the Java bridge to a strict
  allowlist.
- `deny("os", "io")` / `allow("os")` give finer control.
- The Java bridge is separately filtered by `JavaAccessPolicy`; tune with
  `javaPolicy(...)`, `javaAllow("java.util.*")`, `javaDeny("com.acme.*")`.

`JavaAccessPolicy.DEFAULT` filters the Java bridge only. A fresh state still
exposes the Lua `os`/`io`/`package` libraries. For untrusted input, always use
`sandbox()` and a guard.

## Limiting time and instructions

```java
new LuaState().instructionLimit(10_000_000);                 // instruction budget
new LuaState().timeout(Duration.ofSeconds(1));               // wall-clock budget
state.evalWithTimeout(src, Duration.ofMillis(500));          // one-shot, throws TimeoutException
state.clearGuard();                                          // remove the guard
```

A tripped guard raises a catchable Lua error, so `pcall` in Lua and
`LuaException` in Java both work. While a guard or a debug hook is active, the
JIT is bypassed (correct, just not accelerated).

## Coroutines and concurrency

Lua coroutines are supported and run on Java virtual threads:

```lua
local co = coroutine.create(function()
    for i = 1, 3 do coroutine.yield(i) end
end)
print(coroutine.resume(co))   -- true 1
```

For parallel host workloads, use one `LuaState` per thread:

```java
try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
    for (int t = 0; t < 8; t++) {
        pool.submit(() -> {
            LuaState local = new LuaState();
            local.eval(script);
        });
    }
}
```

States do not share globals or policy, so this is safe by construction.

## Performance tuning

The tiered JIT is on by default and needs no configuration for typical use.
The interpreter is always the fallback and the deopt target; hot Lua functions
whose shape passes a static subset analysis tier up to one JVM method per proto
(unboxed `long` flow, direct self-recursion, type guards with resume-at-pc
deopt). Coroutines, debug hooks and execution timeouts never enter JIT code.

Per-state control:

```java
state.jitEnabled(false);            // disable JIT for this state (untrusted)
state.jitEnabled(true);             // force on
state.jitEnabled(null);             // follow the process-wide default
```

Process-wide and build-time switches (JVM `-D` flags, read once at startup):

| Property | Effect |
| :--- | :--- |
| `-Dluava.jit=false` | Disable the JIT process-wide (same as `jitEnabled(false)` for every state). |
| `-Dluava.jit.sync=true` | Compile on the calling thread instead of a background thread (deterministic measurement). |
| `-Dluava.jit.hotThreshold=N` | Function-call count before a proto is compiled (default 50). |
| `-Dluava.jit.loopThreshold=N` | Loop trip count that requests tier-up from `FORPREP` (default 8192). |
| `-Dluava.jit.debug=true` | Log tier-up decisions to stderr (eligible / not eligible, per proto). |

Notes:

- Compiled classes are bounded by an LRU cache (512), so Metaspace cannot leak.
- Lower `hotThreshold`/`loopThreshold` to force compilation sooner in tests
  (`-Dluava.jit.hotThreshold=1 -Dluava.jit.loopThreshold=1` compiles almost
  everything); raise them to reduce compile overhead on short-lived workloads.
- While an `instructionLimit`, `timeout` or a debug hook is active, the JIT is
  bypassed for correctness — the script still runs, just interpreted.

To remove first-request latency in a server, pre-warm after loading scripts:

```java
import org.luava.runtime.jit.JitCompiler;

JitCompiler.prewarm((org.luava.runtime.bytecode.LuaClosure) chunk);
// or walk a whole closure tree: JitCompiler.prewarm(function)
```

Repeated `eval` of the same chunk name and source reuses the compiled proto
tree, so a per-request `eval` of an unchanged script still accumulates JIT
hotness.

## EmmyLua stubs for IDEs

Generate completion stubs for annotated modules:

```java
state.registerModule(new GameApi());

String stubs = state.generateEmmyDocs();              // all modules, as text
state.exportEmmyDocs(Path.of("luava-stubs"));         // one .lua file per module
```

Only annotation-bound modules are documented; a module built by hand with
`registerModule(name, table -> ...)` has an arbitrary shape and is excluded.

## Common pitfalls

- One `LuaState` per thread. Sharing a single state across threads is not
  supported; create one per thread or tenant.
- `set` copies, `setLive` references. Use `setLive` when you need mutations to
  reach the original object.
- Numeric conversion. Lua 5.4 has integer and float subtypes. Reading a float
  with `toLong()` truncates; use `toDouble()` or `math.type` in Lua when the
  distinction matters.
- Errors are `LuaException`. Unchecked Java exceptions must not reach the host,
  so the interop layer wraps them; catch `LuaException` at the boundary.
- `os` and `io` are not removed by `JavaAccessPolicy.DEFAULT`. They are Lua
  libraries; only `sandbox()` (or `deny`) removes them.
- A `Class` binding reads static fields only. Pass an instance for instance
  state.
- `require` needs a searcher. Filesystem by default; register a
  `resourceLoader` for JAR, classpath or virtual modules.
