"""Play one game through the MCP server with a fixed, simple policy. Tests the plumbing, not the play."""
import json
import re
import subprocess
import sys

ROOT = __file__.rsplit("/tests/", 1)[0]


class Mcp:
    def __init__(self):
        self.p = subprocess.Popen([f"{ROOT}/scripts/run-mcp.sh"], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  stderr=open(f"{ROOT}/build/mcp-stderr.log", "w"), text=True)
        self.n = 0

    def rpc(self, method, params=None):
        self.n += 1
        self.p.stdin.write(json.dumps({"jsonrpc": "2.0", "id": self.n, "method": method, "params": params or {}}) + "\n")
        self.p.stdin.flush()
        while True:
            msg = json.loads(self.p.stdout.readline())
            if msg.get("id") == self.n:
                return msg

    def tool(self, name, **args):
        r = self.rpc("tools/call", {"name": name, "arguments": args})
        if "error" in r:
            raise RuntimeError(r["error"])
        return r["result"]["content"][0]["text"]


tried = set()


def my_hand(state):
    """Card lines in your own hand, from forge_state."""
    you = state.split(" (you) ", 1)[1].split("\nPLAYER ", 1)[0]
    if "  hand:\n" not in you:
        return []
    block = you.split("  hand:\n", 1)[1]
    return re.findall(r"^    \[(\d+)\] (.*)$", block.split("\n  graveyard", 1)[0].split("\n  exile", 1)[0], re.M)


def main_phase_play(m, decision):
    """On your main phase, play a land, then try each spell once per turn."""
    head = re.search(r"\[Turn (\d+), (.*?), phase (\w+)", decision)
    if not head or "(you)" not in head.group(2) or head.group(3) not in ("MAIN1", "MAIN2"):
        return None
    turn = head.group(1)
    state = m.tool("forge_state")
    for cid, line in my_hand(state):
        if (turn, cid) in tried:
            continue
        is_land = "— Land" in line or "Basic Land" in line
        if is_land and "lands played 0/" not in state.split(" (you) ", 1)[1].split("\n", 1)[0]:
            continue
        tried.add((turn, cid))
        return f"card {cid}"
    return None


def policy(decision, m):
    kind = re.search(r"DECISION #\d+ \((\w+)\)", decision).group(1)
    answer = decision.split("Answer: ", 1)[1]
    options = re.findall(r"^  (\d+): (.*)$", decision, re.M)
    if kind == "prompt":
        if "Priority:" in decision:
            play = main_phase_play(m, decision)
            if play:
                return play
        if "'ok'" in answer:
            return "ok"
        if options:
            return "card " + re.match(r"\[(\d+)\]", options[0][1]).group(1)
        return "cancel"
    if kind == "confirm":
        return "yes"
    if kind in ("order",):
        return "keep"
    if kind == "damage":
        return "auto"
    if kind == "distribute":
        return "0=" + re.search(r"Distribute (\d+)", decision).group(1)
    if kind == "input" and not options:
        return "0"
    m = re.search(r"between (\d+) and", answer)
    k = int(m.group(1)) if m else 1
    return ",".join(str(i) for i in range(k)) if k else "none"


def main():
    port, deck = int(sys.argv[1]), sys.argv[2]
    max_steps = int(sys.argv[3]) if len(sys.argv) > 3 else 400
    m = Mcp()
    print(m.rpc("initialize", {"protocolVersion": "2025-06-18"})["result"]["serverInfo"])
    print([t["name"] for t in m.rpc("tools/list")["result"]["tools"]])
    print(m.tool("forge_connect", deck=deck, port=port))
    for step in range(max_steps):
        out = m.tool("forge_wait", timeout_seconds=30, with_state=(step % 25 == 0))
        print(f"----- step {step}\n{out}")
        if "The game is over" in out:
            break
        d = re.search(r"DECISION #(\d+)", out)
        if d:
            ans = policy(out[out.index("DECISION #"):], m)
            print(">>> act", d.group(1), ans, "->", m.tool("forge_act", decision=int(d.group(1)), answer=ans))
    print(m.tool("forge_state"))


main()
