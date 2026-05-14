"""Tiny client for the dev-only Java JSON-RPC bridge (port 25580).

Protocol: one JSON object per line. Reconnects on broken pipe.
"""

from __future__ import annotations

import json
import socket
import threading
from typing import Any


class BridgeError(RuntimeError):
    pass


class BridgeClient:
    def __init__(self, host: str = "127.0.0.1", port: int = 25580, timeout: float = 6.0):
        self.host = host
        self.port = port
        self.timeout = timeout
        self._sock: socket.socket | None = None
        self._buf = b""
        self._next_id = 1
        self._lock = threading.Lock()

    def _connect(self) -> None:
        s = socket.create_connection((self.host, self.port), timeout=self.timeout)
        s.settimeout(self.timeout)
        self._sock = s
        self._buf = b""

    def _recv_line(self) -> str:
        assert self._sock is not None
        while b"\n" not in self._buf:
            chunk = self._sock.recv(4096)
            if not chunk:
                raise BridgeError("bridge closed connection")
            self._buf += chunk
        line, self._buf = self._buf.split(b"\n", 1)
        return line.decode("utf-8")

    def call(self, method: str, params: dict[str, Any] | None = None) -> Any:
        with self._lock:
            for attempt in (1, 2):
                try:
                    if self._sock is None:
                        self._connect()
                    pid = self._next_id
                    self._next_id += 1
                    req = json.dumps({"id": pid, "method": method, "params": params or {}})
                    self._sock.sendall(req.encode("utf-8") + b"\n")
                    resp = json.loads(self._recv_line())
                    if "error" in resp:
                        raise BridgeError(resp["error"].get("message", str(resp["error"])))
                    return resp.get("result")
                except (OSError, BridgeError) as e:
                    if self._sock is not None:
                        try: self._sock.close()
                        finally: self._sock = None
                    if attempt == 2 or isinstance(e, BridgeError):
                        raise
        raise BridgeError("unreachable")
