#!/bin/bash
# Test all 4 languages against the executor agent
TOKEN="57b4f75e92c1f14732b8d80bea36f12a1525af9a2b20444ce65c64e85b4be93b"
URL="http://localhost:8081/v1/execute"
HEADER="Authorization: Bearer $TOKEN"

echo "============================================"
echo "  Testing Codex Executor - All Languages"
echo "============================================"

# 1. PYTHON
echo ""
echo ">>> [1/4] PYTHON - print(2+3)=5"
RESULT=$(curl -s -X POST "$URL" \
  -H "Content-Type: application/json" \
  -H "$HEADER" \
  -d '{
    "submissionId":"00000000-0000-0000-0000-000000000001",
    "language":"PYTHON",
    "sourceCode":"a=int(input())\nb=int(input())\nprint(a+b)",
    "fileExtension":".py",
    "dockerImage":"codex-python:latest",
    "compileCommand":null,
    "executeCommand":"python3 /workspace/solution.py",
    "compileTimeoutMs":10000,
    "runTimeoutMs":5000,
    "memoryLimitMb":256,
    "testCases":[{"id":"tc1","stdin":"2\n3","expectedStdout":"5"}]
  }')
echo "$RESULT" | python3 -m json.tool 2>/dev/null || echo "$RESULT"

# 2. JAVASCRIPT
echo ""
echo ">>> [2/4] JAVASCRIPT - console.log(2+3)=5"
RESULT=$(curl -s -X POST "$URL" \
  -H "Content-Type: application/json" \
  -H "$HEADER" \
  -d '{
    "submissionId":"00000000-0000-0000-0000-000000000002",
    "language":"JAVASCRIPT",
    "sourceCode":"const readline=require(\"readline\");const rl=readline.createInterface({input:process.stdin});const lines=[];rl.on(\"line\",(l)=>lines.push(l));rl.on(\"close\",()=>{console.log(parseInt(lines[0])+parseInt(lines[1]));});",
    "fileExtension":".js",
    "dockerImage":"codex-javascript:latest",
    "compileCommand":null,
    "executeCommand":"node /workspace/solution.js",
    "compileTimeoutMs":10000,
    "runTimeoutMs":5000,
    "memoryLimitMb":256,
    "testCases":[{"id":"tc1","stdin":"2\n3","expectedStdout":"5"}]
  }')
echo "$RESULT" | python3 -m json.tool 2>/dev/null || echo "$RESULT"

# 3. JAVA
echo ""
echo ">>> [3/4] JAVA - System.out.println(2+3)=5"
RESULT=$(curl -s -X POST "$URL" \
  -H "Content-Type: application/json" \
  -H "$HEADER" \
  -d '{
    "submissionId":"00000000-0000-0000-0000-000000000003",
    "language":"JAVA",
    "sourceCode":"import java.util.Scanner;\npublic class solution {\n  public static void main(String[] args) {\n    Scanner sc = new Scanner(System.in);\n    int a = sc.nextInt();\n    int b = sc.nextInt();\n    System.out.println(a + b);\n  }\n}",
    "fileExtension":".java",
    "dockerImage":"codex-java:latest",
    "compileCommand":"javac /workspace/solution.java",
    "executeCommand":"java -cp /workspace solution",
    "compileTimeoutMs":15000,
    "runTimeoutMs":5000,
    "memoryLimitMb":256,
    "testCases":[{"id":"tc1","stdin":"2\n3","expectedStdout":"5"}]
  }')
echo "$RESULT" | python3 -m json.tool 2>/dev/null || echo "$RESULT"

# 4. CPP
echo ""
echo ">>> [4/4] C++ - cout<<(2+3)=5"
RESULT=$(curl -s -X POST "$URL" \
  -H "Content-Type: application/json" \
  -H "$HEADER" \
  -d '{
    "submissionId":"00000000-0000-0000-0000-000000000004",
    "language":"CPP",
    "sourceCode":"#include<bits/stdc++.h>\nusing namespace std;\nint main(){int a,b;cin>>a>>b;cout<<a+b<<endl;return 0;}",
    "fileExtension":".cpp",
    "dockerImage":"codex-cpp:latest",
    "compileCommand":"g++ -std=c++17 -O2 -o /workspace/solution /workspace/solution.cpp",
    "executeCommand":"/workspace/solution",
    "compileTimeoutMs":15000,
    "runTimeoutMs":5000,
    "memoryLimitMb":256,
    "testCases":[{"id":"tc1","stdin":"2\n3","expectedStdout":"5"}]
  }')
echo "$RESULT" | python3 -m json.tool 2>/dev/null || echo "$RESULT"

echo ""
echo "============================================"
echo "  All tests complete!"
echo "============================================"
