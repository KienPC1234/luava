-- BM05: Closure creation and upvalue capture
local function make_counter(start)
    local n = start
    return function()
        n = n + 1
        return n
    end
end

local sum = 0
for i = 1, 100000 do
    local c = make_counter(i)
    sum = sum + c() + c() + c()
end
assert(sum > 0, "closures wrong")

