"""Minimal Source RCON client. Spec: https://wiki.vg/RCON

Why hand-rolled instead of `mcrcon`: one fewer dep, ~60 lines, and the protocol
is trivial. Re-uses a single TCP connection across calls; auto-reconnects if
the server drops it (e.g. between dev sessions).
"""

from __future__ import annotations

import socket
import struct
import threading

_SERVERDATA_AUTH = 3
_SERVERDATA_EXECCOMMAND = 2
_SERVERDATA_AUTH_RESPONSE = 2
_SERVERDATA_RESPONSE_VALUE = 0


class RconError(RuntimeError):
    pass


class RconClient:
    def __init__(self, host: str, port: int, password: str, timeout: float = 5.0):
        self.host = host
        self.port = port
        self.password = password
        self.timeout = timeout
        self._sock: socket.socket | None = None
        self._next_id = 1
        self._lock = threading.Lock()

    def _connect(self) -> None:
        s = socket.create_connection((self.host, self.port), timeout=self.timeout)
        s.settimeout(self.timeout)
        self._sock = s
        # AUTH
        auth_id = self._send(_SERVERDATA_AUTH, self.password)
        resp_id, resp_type, _ = self._recv()
        if resp_type != _SERVERDATA_AUTH_RESPONSE or resp_id == -1 or resp_id != auth_id:
            self._sock.close()
            self._sock = None
            raise RconError(f"RCON auth failed (resp_id={resp_id} expected={auth_id})")

    def _send(self, packet_type: int, payload: str) -> int:
        assert self._sock is not None
        pid = self._next_id
        self._next_id += 1
        body = payload.encode("utf-8") + b"\x00\x00"
        packet = struct.pack("<ii", pid, packet_type) + body
        self._sock.sendall(struct.pack("<i", len(packet)) + packet)
        return pid

    def _recv_exact(self, n: int) -> bytes:
        assert self._sock is not None
        buf = b""
        while len(buf) < n:
            chunk = self._sock.recv(n - len(buf))
            if not chunk:
                raise RconError("RCON connection closed mid-packet")
            buf += chunk
        return buf

    def _recv(self) -> tuple[int, int, str]:
        size = struct.unpack("<i", self._recv_exact(4))[0]
        body = self._recv_exact(size)
        rid, rtype = struct.unpack("<ii", body[:8])
        # body[-2:] is the two trailing nulls
        payload = body[8:-2].decode("utf-8", errors="replace")
        return rid, rtype, payload

    def cmd(self, command: str) -> str:
        """Run a server command. Reconnects automatically on broken pipe."""
        with self._lock:
            for attempt in (1, 2):
                try:
                    if self._sock is None:
                        self._connect()
                    sent_id = self._send(_SERVERDATA_EXECCOMMAND, command)
                    rid, rtype, payload = self._recv()
                    if rid != sent_id:
                        raise RconError(f"RCON id mismatch ({rid} vs {sent_id})")
                    return payload
                except (OSError, RconError):
                    if self._sock is not None:
                        try:
                            self._sock.close()
                        finally:
                            self._sock = None
                    if attempt == 2:
                        raise
        raise RconError("unreachable")

    def close(self) -> None:
        with self._lock:
            if self._sock is not None:
                try:
                    self._sock.close()
                finally:
                    self._sock = None
