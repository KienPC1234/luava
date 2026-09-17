-- BM03: Table insert, iterate, and random access
local t = {}
for i = 1, 500000 do
    t[i] = i * 3
end
local acc = 0
for i = 1, 500000 do
    acc = acc + t[i]
end
assert(acc == 375000750000, "table_ops wrong: " .. tostring(acc))

