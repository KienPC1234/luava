-- BM10: Sieve of Eratosthenes (heavy memory + compute)
local N = 2000000
local sieve = {}
for i = 2, N do sieve[i] = true end
for i = 2, math.floor(math.sqrt(N)) do
    if sieve[i] then
        for j = i * i, N, i do
            sieve[j] = false
        end
    end
end
local count = 0
for i = 2, N do
    if sieve[i] then count = count + 1 end
end
assert(count == 148933, "sieve wrong: " .. tostring(count))

