-- ============================================================================
-- hello.lua — a tour of Lua 5.4 running on the Luava engine.
--
-- Run it (from the project root, after `mvn package`):
--     java -jar target/luava-0.1.0-alpha.jar examples/hello.lua
--     java -jar target/luava-0.1.0-alpha.jar examples/hello.lua Alice Bob
--
-- It exercises the features that make Luava interesting: integers vs floats,
-- bitwise ops, metatables/OOP, closures/upvalues, generic-for, string
-- methods and patterns, coroutines, error handling and <close>.
-- ============================================================================

local function title(s)
    print("\n== " .. s .. " ==")
end

-- ---------------------------------------------------------------------------
title("numbers: integer and float subtypes")
-- 10 / 4 is float division; 10 // 4 is integer floor division.
print("10 / 4  =", 10 / 4)          --> 2.5
print("10 // 4 =", 10 // 4)         --> 2
print("10 % 3  =", 10 % 3)          --> 1
print("math.type(1)   =", math.type(1))     --> integer
print("math.type(1.0) =", math.type(1.0))   --> float
print("2^53    =", 2^53)
print("0x1F | 0x20 =", 0x1F | 0x20) --> bitwise, 63
print("1 << 10 =", 1 << 10)         --> 1024

-- ---------------------------------------------------------------------------
title("strings and patterns")
local who = arg[1] or "world"
print("hello, " .. who .. "!")      --> concatenation
local text = "the quick brown fox 123"
print(text:upper())
print("length:", #text)
print("words:", select(2, text:gsub("%a+", function(w) return w:sub(1, 1) end)))
for word in text:gmatch("%a+") do io.write(word .. " ") end
print()
print(string.format("pi ~= %.3f, hex=%#x", math.pi, 255))

-- ---------------------------------------------------------------------------
title("tables and generic-for")
local scores = { alice = 90, bob = 75, carol = 82 }
local names = {}
for name, score in pairs(scores) do
    names[#names + 1] = string.format("%s=%d", name, score)
end
table.sort(names)
print(table.concat(names, ", "))

local list = { 5, 3, 8, 1 }
table.sort(list)
print("sorted:", table.concat(list, " "))
local total = 0
for _, v in ipairs(list) do total = total + v end
print("sum:", total)

-- ---------------------------------------------------------------------------
title("closures and upvalues")
local function make_counter(start)
    local n = start
    return function()
        n = n + 1
        return n
    end
end
local next_id = make_counter(100)
print("counter:", next_id(), next_id(), next_id())  --> 101 102 103

-- ---------------------------------------------------------------------------
title("OOP with metatables")
local Vec = {}
Vec.__index = Vec

function Vec.new(x, y)
    return setmetatable({ x = x, y = y }, Vec)
end

function Vec:dot(other)
    return self.x * other.x + self.y * other.y
end

function Vec:length()
    return math.sqrt(self.x * self.x + self.y * self.y)
end

function Vec:__tostring()
    return string.format("(%g, %g)", self.x, self.y)
end

local a, b = Vec.new(3, 4), Vec.new(1, 2)
print("a =", tostring(a), " b =", tostring(b))
print("a:dot(b) =", a:dot(b))       --> 11
print("a:length() =", a:length())   --> 5.0

-- ---------------------------------------------------------------------------
title("errors and protected calls")
local function risky(n)
    if n < 0 then error("negative not allowed: " .. n) end
    return n * 2
end
print("pcall(risky, 5)  =", pcall(risky, 5))    --> true 10
local ok, err = pcall(risky, -1)
print("pcall(risky, -1) =", ok, err)

-- ---------------------------------------------------------------------------
title("coroutines")
local co = coroutine.create(function()
    for i = 1, 3 do coroutine.yield("step " .. i) end
    return "done"
end)
while true do
    local good, value = coroutine.resume(co)
    if not good or coroutine.status(co) == "dead" then
        print("coroutine:", value)
        break
    end
    print("coroutine:", value)
end

-- ---------------------------------------------------------------------------
title("to-be-closed variables")
do
    local _ <close> = setmetatable({}, {
        __close = function() print("resource closed") end
    })
    print("inside the block")
end

-- ---------------------------------------------------------------------------
print("\nall examples ran OK on " .. _VERSION)
