-- BM06: Coroutine yield/resume throughput
local function producer(n)
    for i = 1, n do
        coroutine.yield(i)
    end
end

local total = 0
local co = coroutine.create(function() producer(200000) end)
while true do
    local ok, v = coroutine.resume(co)
    if not ok or v == nil then break end
    total = total + v
end
assert(total == 20000100000, "coroutines wrong: " .. tostring(total))

