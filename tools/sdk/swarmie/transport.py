"""Low-level JSONL socket transport.

One JSON object per line, UTF-8. Sending and receiving are split so the client
can wait with an explicit deadline and still process pushed event batches.
"""
from __future__ import annotations

import json
import select
import socket
import time
from typing import Any, Dict, Optional

from .errors import ConnectionClosed, ConnectionFailure, ProtocolError, RequestTimeout

MAX_LINE_BYTES = 1_048_576


class JsonlTransport:
    """A single blocking TCP connection speaking the JSONL protocol."""

    def __init__(self, host: str, port: int, connect_timeout: float = 5.0) -> None:
        try:
            self._sock: Optional[socket.socket] = socket.create_connection(
                (host, int(port)), timeout=connect_timeout
            )
        except OSError as exc:
            raise ConnectionFailure(f"could not connect to {host}:{port}: {exc}") from exc
        self._sock.settimeout(None)
        try:
            self._sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        except OSError:
            pass
        self._buffer = bytearray()
        self._closed = False

    @property
    def connected(self) -> bool:
        return not self._closed

    def send(self, payload: Dict[str, Any]) -> None:
        if self._closed or self._sock is None:
            raise ConnectionClosed("transport is closed")
        data = json.dumps(payload, separators=(",", ":"), ensure_ascii=False)
        encoded = (data + "\n").encode("utf-8")
        if len(encoded) > MAX_LINE_BYTES:
            raise ProtocolError("outgoing message exceeds the line limit")
        try:
            self._sock.sendall(encoded)
        except OSError as exc:
            self.close()
            raise ConnectionClosed(f"connection lost while sending: {exc}") from exc

    def receive(self, timeout: float) -> Dict[str, Any]:
        if self._closed or self._sock is None:
            raise ConnectionClosed("transport is closed")
        deadline = time.monotonic() + max(0.0, float(timeout))
        while True:
            line = self._pop_line()
            if line is not None:
                return self._decode(line)

            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise RequestTimeout("timed out waiting for a server message")
            try:
                readable, _, _ = select.select([self._sock], [], [], remaining)
            except OSError as exc:
                self.close()
                raise ConnectionClosed(f"connection lost while waiting: {exc}") from exc
            if not readable:
                raise RequestTimeout("timed out waiting for a server message")
            try:
                chunk = self._sock.recv(65536)
            except OSError as exc:
                self.close()
                raise ConnectionClosed(f"connection lost while reading: {exc}") from exc
            if not chunk:
                self.close()
                raise ConnectionClosed("server closed the connection")
            self._buffer.extend(chunk)
            if len(self._buffer) > MAX_LINE_BYTES and b"\n" not in self._buffer:
                self.close()
                raise ProtocolError("server sent a line larger than the limit")

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        sock, self._sock = self._sock, None
        if sock is not None:
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                sock.close()
            except OSError:
                pass

    def _pop_line(self) -> Optional[bytes]:
        while True:
            index = self._buffer.find(b"\n")
            if index < 0:
                return None
            raw = bytes(self._buffer[:index])
            del self._buffer[: index + 1]
            if raw.endswith(b"\r"):
                raw = raw[:-1]
            if raw.strip():
                return raw

    @staticmethod
    def _decode(line: bytes) -> Dict[str, Any]:
        try:
            text = line.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise ProtocolError(f"message is not valid UTF-8: {exc}") from exc
        try:
            value = json.loads(text)
        except json.JSONDecodeError as exc:
            raise ProtocolError(f"message is not valid JSON: {exc}") from exc
        if not isinstance(value, dict):
            raise ProtocolError("message must be a JSON object")
        return value
