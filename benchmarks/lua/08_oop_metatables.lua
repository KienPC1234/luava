-- BM08: OOP via metatables - method dispatch overhead
local Vec = {}
Vec.__index = Vec

function Vec.new(x, y)
    return setmetatable({x = x, y = y}, Vec)
end

function Vec:dot(other)
    return self.x * other.x + self.y * other.y
end

function Vec:length()
    return math.sqrt(self.x * self.x + self.y * self.y)
end

function Vec:add(other)
    return Vec.new(self.x + other.x, self.y + other.y)
end

local acc = 0.0
local a = Vec.new(1.0, 2.0)
local b = Vec.new(3.0, 4.0)
for i = 1, 500000 do
    local c = a:add(b)
    acc = acc + c:dot(a) + c:length()
end
assert(acc > 0, "oop_metatables wrong")

