-- BM07: Hash table (string keys) - write and lookup
local t = {}
for i = 1, 200000 do
    t["key_" .. i] = i
end
local acc = 0
for i = 1, 200000 do
    acc = acc + t["key_" .. i]
end
assert(acc == 20000100000, "hash_table wrong: " .. tostring(acc))

