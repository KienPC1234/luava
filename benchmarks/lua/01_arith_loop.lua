-- BM01: Pure arithmetic loop
local sum = 0
for i = 1, 10000000 do
    sum = sum + i
end
assert(sum == 50000005000000, "arith_loop wrong: " .. tostring(sum))

