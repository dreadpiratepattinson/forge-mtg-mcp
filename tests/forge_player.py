"""One Forge player for an agent: a daemon that holds an MCP server, and a client that calls its tools.

    python3 tests/forge_player.py serve <socket> <log>      # start the daemon (run in background)
    python3 tests/forge_player.py call <socket> <tool> [json-args]
"""
import json
import os
import socket
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def serve(path, log):
    env = dict(os.environ, MCP_LOG=log)
    p = subprocess.Popen([f"{ROOT}/scripts/run-mcp.sh"], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                         text=True, env=env)
    n = 0

    def rpc(method, params):
        nonlocal n
        n += 1
        p.stdin.write(json.dumps({"jsonrpc": "2.0", "id": n, "method": method, "params": params}) + "\n")
        p.stdin.flush()
        while True:
            msg = json.loads(p.stdout.readline())
            if msg.get("id") == n:
                return msg

    rpc("initialize", {"protocolVersion": "2025-06-18"})
    if os.path.exists(path):
        os.unlink(path)
    srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    srv.bind(path)
    srv.listen(1)
    while True:
        conn, _ = srv.accept()
        with conn:
            try:
                req = json.loads(conn.makefile().readline())
            except (OSError, ValueError):
                continue
            r = rpc("tools/call", {"name": req["tool"], "arguments": req.get("args", {})})
            if "error" in r:
                out = "ERROR: " + json.dumps(r["error"])
            else:
                out = "\n".join(c.get("text", "") for c in r["result"]["content"])
            try:
                conn.sendall(out.encode())
            except OSError:
                pass  # the caller gave up; the call itself still happened


def call(path, tool, args):
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.connect(path)
    s.sendall((json.dumps({"tool": tool, "args": args}) + "\n").encode())
    s.shutdown(socket.SHUT_WR)
    chunks = []
    while True:
        b = s.recv(65536)
        if not b:
            break
        chunks.append(b)
    print(b"".join(chunks).decode())


if __name__ == "__main__":
    if sys.argv[1] == "serve":
        serve(sys.argv[2], sys.argv[3])
    else:
        call(sys.argv[2], sys.argv[3], json.loads(sys.argv[4]) if len(sys.argv) > 4 else {})
