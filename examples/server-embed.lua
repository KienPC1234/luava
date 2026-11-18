-- server-embed.lua — production embedding sample (W4).
--
-- The Lua side of a request handler. Runs cleanly under `luava --sandbox`:
-- only sandbox-safe stdlib (string/table/math) is used; no os/io/package.
--
-- Java host wiring (see docs/USAGE.md "Production embedding checklist"):
--
--   try (LuaState state = new LuaState()
--           .sandbox()
--           .instructionLimit(1_000_000)
--           .maxAllocationBytes(16 * 1024 * 1024)) {
--       state.setLive("db", readOnlyDb);
--       state.eval(<this file>);
--       LuaValue out = state.call("handle", path);
--       System.out.println(state.stats());
--   }
--
-- Smoke-test exactly that config from the CLI:
--   luava --sandbox --profile examples/server-embed.lua /hello
--   luava --dump-bytecode examples/server-embed.lua

-- Pure request handler: no globals, no I/O, bounded work.
local function handle(path)
  assert(type(path) == "string", "path must be a string")
  -- Cap the work per request so a hostile path cannot spin the host
  -- even before the host-side instructionLimit trips.
  if #path > 256 then
    return 414, "path too long"
  end
  local name = path:match("^/([%w%-]+)$")
  if name == nil then
    return 404, "not found"
  end
  return 200, "hello, " .. name
end

-- pcall dispatch: a handler bug becomes a 500, never a host crash.
local function serve(path)
  local ok, a, b = pcall(handle, path)
  if not ok then
    return 500, "internal error"
  end
  return a, b
end

-- Self-test when run directly (arg[0] is this file under the CLI).
if arg ~= nil and arg[0] ~= nil and arg[0]:find("server%-embed%.lua$") then
  local cases = { "/world", "/a-b9", "/", "/x/y", string.rep("z", 300) }
  for _, c in ipairs(cases) do
    local code, body = serve(c)
    print(code, body)
  end
  -- Lua-visible memory pressure (host sees the same via state.stats()).
  print("lua_kb", math.floor(collectgarbage("count")))
end

-- Export for `state.call("handle", ...)` / `state.call("serve", ...)` hosts.
_G.handle = handle
_G.serve = serve
