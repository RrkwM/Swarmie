#!/usr/bin/env python3
"""Dependency-free localhost JSONL client for Swarmie Adapter development.

The transport is a development aid only: it is default-off, binds to
127.0.0.1, and has no authentication or remote/multi-user security model.
"""
from __future__ import annotations

import argparse
import json
import socket
import sys
import time
from typing import Any, Dict, List, Optional


class Client:
    def __init__(self, host: str, port: int) -> None:
        self.sock = socket.create_connection((host, port), timeout=5.0)
        self.sock.settimeout(5.0)
        self.reader = self.sock.makefile("r", encoding="utf-8", newline="\n")
        self.next_id = 1
        self.events: List[Dict[str, Any]] = []
        self.epoch: Optional[int] = None
        self.last_sequence = 0
        self.last_missed = False
        hello = self._read_message()
        if hello.get("type") != "hello":
            raise RuntimeError("expected hello, got %r" % (hello,))
        self.epoch = hello.get("epoch")
        print("HELLO protocol=%s epoch=%s" % (hello.get("protocol"), self.epoch))

    def _read_message(self) -> Dict[str, Any]:
        line = self.reader.readline()
        if not line:
            raise ConnectionError("server closed the connection")
        return json.loads(line)

    def _collect(self, message: Dict[str, Any]) -> None:
        if message.get("type") == "events":
            batch = message.get("batch") or {}
            self.epoch = batch.get("serverEpoch", self.epoch)
            self.last_sequence = max(self.last_sequence, batch.get("nextSequence", 0) - 1)
            self.last_missed = bool(batch.get("missed", False))
            self.events.extend(batch.get("events") or [])
            print(
                "EVENTS epoch=%s after=%s latest=%s next=%s missed=%s count=%s"
                % (
                    batch.get("serverEpoch"),
                    batch.get("afterSequence"),
                    batch.get("latestSequence"),
                    batch.get("nextSequence"),
                    batch.get("missed"),
                    len(batch.get("events") or []),
                )
            )
            for event in batch.get("events") or []:
                print(
                    "  #%s %s robot=%s corr=%s state=%s"
                    % (
                        event.get("sequence"),
                        event.get("type"),
                        event.get("robotId"),
                        event.get("correlationId"),
                        (event.get("commandResult") or {}).get("state"),
                    )
                )

    def request(self, payload: Dict[str, Any], expected: str) -> Dict[str, Any]:
        request_id = str(self.next_id)
        self.next_id += 1
        payload = dict(payload)
        payload["id"] = request_id
        self.sock.sendall((json.dumps(payload, separators=(",", ":")) + "\n").encode("utf-8"))
        while True:
            message = self._read_message()
            if message.get("type") == "events":
                if message.get("id") is None:
                    self._collect(message)
                    continue
                if message.get("id") == request_id:
                    self._collect(message)
                    return message
                print("IGNORING %r" % (message,))
                continue
            if message.get("id") != request_id:
                print("IGNORING %r" % (message,))
                continue
            if message.get("type") == "error":
                raise RuntimeError("server error: %s" % (message,))
            if message.get("type") != expected:
                raise RuntimeError("expected %s, got %r" % (expected, message))
            return message

    def robots(self) -> List[Dict[str, Any]]:
        response = self.request({"op": "robots"}, "robots")
        print("ROBOTS epoch=%s count=%s" % (response.get("epoch"), len(response.get("robots") or [])))
        for robot in response.get("robots") or []:
            print("  %s %s %s" % (robot.get("robotId"), robot.get("lifecycle"), robot.get("dimensionId")))
        return response.get("robots") or []

    def poll_events(self, after: int = 0, max_events: int = 64) -> Dict[str, Any]:
        response = self.request(
            {"op": "events", "after": after, "max": max_events}, "events"
        )
        self._collect({"type": "events", "batch": response.get("batch")})
        return response.get("batch") or {}

    def submit(self, robot: str, command: Dict[str, Any], correlation: str) -> Dict[str, Any]:
        response = self.request(
            {
                "op": "submit",
                "robot": robot,
                "correlation": correlation,
                "command": command,
            },
            "ack",
        )
        ack = response.get("ack") or {}
        print(
            "ACK status=%s corr=%s state=%s internal=%s error=%s message=%s"
            % (
                ack.get("status"),
                ack.get("correlationId"),
                ack.get("state"),
                ack.get("internalCommandId"),
                ack.get("error"),
                ack.get("message"),
            )
        )
        return ack

    def cancel(self, robot: str, correlation: str) -> Dict[str, Any]:
        response = self.request(
            {"op": "cancel", "robot": robot, "correlation": correlation}, "cancel_ack"
        )
        ack = response.get("cancelAck") or {}
        print(
            "CANCEL status=%s corr=%s state=%s error=%s message=%s"
            % (
                ack.get("status"),
                ack.get("correlationId"),
                ack.get("state"),
                ack.get("error"),
                ack.get("message"),
            )
        )
        return ack

    def close(self) -> None:
        try:
            self.reader.close()
        finally:
            self.sock.close()


def wait_terminal(client: Client, correlation: str, timeout: float = 10.0) -> Optional[Dict[str, Any]]:
    def find() -> Optional[Dict[str, Any]]:
        for event in client.events:
            result = event.get("commandResult") or {}
            if event.get("correlationId") == correlation and result.get("state") in {
                "SUCCEEDED",
                "FAILED",
                "CANCELLED",
                "INTERRUPTED",
            }:
                return event
        return None

    existing = find()
    if existing is not None:
        return existing
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            message = client._read_message()
        except socket.timeout:
            continue
        if message.get("type") == "events":
            client._collect(message)
            found = find()
            if found is not None:
                return found
    return None


def scenario(args: argparse.Namespace) -> int:
    client = Client(args.host, args.port)
    run = str(int(time.time()))
    fly_corr = "py-fly-" + run
    stop_corr = "py-stop-" + run
    mine_corr = "py-mine-" + run
    cancel_corr = "py-cancel-" + run
    try:
        robots = client.robots()
        if not robots:
            print("FAIL no robots available")
            return 1
        loaded = [r for r in robots if r.get("lifecycle") == "LOADED"]
        if not loaded:
            print("FAIL no loaded robots")
            return 1
        robot = loaded[0]["robotId"]
        print("TARGET %s" % robot)

        client.submit(
            robot,
            {"type": "MINE_BLOCK", "position": {"x": args.mine_x, "y": args.mine_y, "z": args.mine_z}},
            mine_corr,
        )
        print("MINE terminal=%r" % (wait_terminal(client, mine_corr, timeout=5),))

        client.submit(
            robot,
            {"type": "TIMED_FLIGHT", "velocity": {"x": 0.1, "y": 0.0, "z": 0.0},
             "durationTicks": args.duration},
            fly_corr,
        )
        event = wait_terminal(client, fly_corr, timeout=15)
        print("FLY terminal=%r" % (event,))

        client.submit(robot, {"type": "STOP"}, stop_corr)
        print("STOP terminal=%r" % (wait_terminal(client, stop_corr),))

        # Duplicate must not execute twice.
        client.submit(robot, {"type": "STOP"}, stop_corr)

        # Long flight then cancel, to observe CANCELLED.
        client.submit(
            robot,
            {"type": "TIMED_FLIGHT", "velocity": {"x": 0.0, "y": 0.1, "z": 0.0},
             "durationTicks": 6000},
            cancel_corr,
        )
        time.sleep(0.5)
        print("CANCEL ack=%r" % (client.cancel(robot, cancel_corr),))
        print("CANCEL terminal=%r" % (wait_terminal(client, cancel_corr, timeout=5),))
        client.close()

        # A real reconnect uses a fresh connection and an explicit cursor.
        # The event store exposes missed=true when the cursor is older than the
        # retained ring; epoch changes identify a new server session.
        client = Client(args.host, args.port)
        batch = client.poll_events(0, 1)
        print(
            "RECONNECT epoch=%s latest=%s missed=%s"
            % (client.epoch, batch.get("latestSequence"), batch.get("missed"))
        )
        return 0
    finally:
        client.close()


def main() -> int:
    parser = argparse.ArgumentParser(description="Swarmie localhost JSONL dev client")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25580)
    parser.add_argument("--duration", type=int, default=20)
    parser.add_argument("--mine-x", type=int, default=500)
    parser.add_argument("--mine-y", type=int, default=99)
    parser.add_argument("--mine-z", type=int, default=500)
    return scenario(parser.parse_args())


if __name__ == "__main__":
    sys.exit(main())

