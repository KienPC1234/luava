-- ============================================================================
-- interop.lua — Lua calling into Java, run on the Luava engine.
--
-- Run from the project root:
--     ./luava examples/interop.lua
--
-- Luava exposes a `java` bridge to scripts. Use java.import to load a class,
-- Class.new(...) to construct, then read fields and call methods. Access is
-- filtered by a security policy: a fresh state allows ordinary application
-- and collection classes, and blocks process execution, reflection, the
-- filesystem and the network.
-- ============================================================================

local function title(s) print("\n== " .. s .. " ==") end

-- ---------------------------------------------------------------------------
title("java.lang.Math")
local Math = java.import("java.lang.Math")
print("Math.max(3, 7)  =", Math.max(3, 7))
print("Math.floor(3.9) =", Math.floor(3.9))
print("Math.PI         =", Math.PI)

-- ---------------------------------------------------------------------------
title("collections: ArrayList and HashMap")
local ArrayList = java.import("java.util.ArrayList")
local list = ArrayList.new()
list.add("alpha")
list.add("beta")
list.add("gamma")
print("size:", list.size())
for i = 1, list.size() do io.write(list.get(i - 1), " ") end
print()
list.set(0, "ALPHA")
print("after set:", list.get(0))

local HashMap = java.import("java.util.HashMap")
local map = HashMap.new()
map.put("one", 1)
map.put("two", 2)
print("map.get('two') =", map.get("two"), " size:", map.size())

-- ---------------------------------------------------------------------------
title("StringBuilder")
local StringBuilder = java.import("java.lang.StringBuilder")
local sb = StringBuilder.new()
for _, w in ipairs({ "Luava", " + ", "Lua 5.4" }) do
    sb.append(w)
end
print("built:", sb.toString())

-- ---------------------------------------------------------------------------
title("security policy in action")
-- The default policy blocks dangerous classes. These should fail with a Lua
-- error (caught here with pcall), not crash the host.
local function blocked(desc, fn)
    local ok, err = pcall(fn)
    print(string.format("%-28s %s", desc, ok and "ALLOWED" or "blocked"))
end
blocked("Runtime.getRuntime()", function() java.import("java.lang.Runtime") end)
blocked("java.io.File",        function() java.import("java.io.File") end)
blocked("java.net.Socket",     function() java.import("java.net.Socket") end)

print("\ninterop examples ran OK")
