-- BM02: Recursive Fibonacci (exponential call tree)
local function fib(n)
    if n <= 1 then return n end
    return fib(n - 1) + fib(n - 2)
end
local result = fib(35)
assert(result == 9227465, "fib wrong: " .. tostring(result))

