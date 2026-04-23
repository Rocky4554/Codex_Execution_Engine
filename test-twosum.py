# Read input
nums = list(map(int, input().split()))
target = int(input())

# Dictionary to store number and its index
seen = {}

for i, num in enumerate(nums):
    complement = target - num
    
    if complement in seen:
        print(f"{seen[complement]} {i}", end='')
        break
    
    seen[num] = i
