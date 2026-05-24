"""One-shot driver: runs tools/place_layout.py and streams every emitted command
through a single RCON connection. Mirrors what the place-structures skill expects."""
import socket, struct, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

class Rcon:
    def __init__(self, host="127.0.0.1", port=25575, pw="dev"):
        self.s = socket.socket()
        self.s.connect((host, port))
        self._pkt(1, 3, pw); self._rd()
        self.next_id = 2
    def _pkt(self, rid, t, p):
        body = struct.pack("<ii", rid, t) + p.encode() + b"\x00\x00"
        self.s.send(struct.pack("<i", len(body)) + body)
    def _exact(self, n):
        b = b""
        while len(b) < n:
            c = self.s.recv(n - len(b))
            if not c: raise RuntimeError("rcon closed")
            b += c
        return b
    def _rd(self):
        (n,) = struct.unpack("<i", self._exact(4))
        return self._exact(n)[8:-2].decode(errors="ignore")
    def cmd(self, c):
        self._pkt(self.next_id, 2, c); self.next_id += 1
        return self._rd()
    def close(self): self.s.close()

proc = subprocess.run([sys.executable, str(ROOT / "tools/place_layout.py")],
                      capture_output=True, text=True, check=True)
lines = [l for l in proc.stdout.splitlines() if l.strip() and not l.startswith("#")]
print(f"streaming {len(lines)} commands…")
r = Rcon()
errs = 0
for i, line in enumerate(lines):
    out = r.cmd(line).strip()
    if out and ("error" in out.lower() or "could not" in out.lower() or "failed" in out.lower()):
        # SAVE-block "could not set" duplicates are expected and harmless after a re-run
        if "could not set the block" not in out.lower():
            errs += 1
            print(f"[{i:3d}] {line[:80]} -> {out[:120]}")
r.close()
print(f"done; {errs} non-trivial errors")
