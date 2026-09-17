-- BM04: String concatenation via table.concat (Lua-idiomatic)
local parts = {}
for i = 1, 50000 do
    parts[i] = tostring(i)
end
local result = table.concat(parts, ",")
assert(#result > 100000, "string_concat wrong length")

