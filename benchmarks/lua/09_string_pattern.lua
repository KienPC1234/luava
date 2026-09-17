-- BM09: String pattern matching
local count = 0
local template = "hello world foo bar 123 baz 456"
for _ = 1, 100000 do
    for w in template:gmatch("%a+") do
        count = count + 1
    end
end
assert(count == 500000, "string_pattern wrong: " .. tostring(count))

