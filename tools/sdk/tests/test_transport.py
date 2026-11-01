from __future__ import annotations

import sys
import unittest
from pathlib import Path

SDK_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SDK_ROOT))
sys.path.insert(0, str(SDK_ROOT / "tests"))

from swarmie import ConnectionClosed, ProtocolError, RequestTimeout  # noqa: E402
from swarmie.transport import JsonlTransport  # noqa: E402
from mock_server import MockSwarmieServer  # noqa: E402


class TransportTest(unittest.TestCase):
    def setUp(self):
        self.server = MockSwarmieServer().start()
        self.transport = JsonlTransport("127.0.0.1", self.server.port, connect_timeout=2.0)
        self.hello = self.transport.receive(1.0)
        self.assertEqual(self.hello["type"], "hello")

    def tearDown(self):
        self.transport.close()
        self.server.stop()

    def test_hello_and_pong(self):
        self.assertEqual(self.hello["protocol"], "swarmie-jsonl/1")
        self.transport.send({"op": "ping"})
        self.assertEqual(self.transport.receive(1.0)["type"], "pong")

    def test_receive_timeout(self):
        with self.assertRaises(RequestTimeout):
            self.transport.receive(0.1)

    def test_invalid_json_is_a_protocol_error(self):
        self.server.send_raw("this is not json")
        with self.assertRaises(ProtocolError):
            self.transport.receive(1.0)

    def test_non_object_message_is_a_protocol_error(self):
        self.server.send_raw("[]")
        with self.assertRaises(ProtocolError):
            self.transport.receive(1.0)

    def test_server_close_is_connection_closed(self):
        self.server.disconnect()
        with self.assertRaises(ConnectionClosed):
            self.transport.receive(1.0)

    def test_send_after_close_is_connection_closed(self):
        self.transport.close()
        with self.assertRaises(ConnectionClosed):
            self.transport.send({"op": "ping"})


if __name__ == "__main__":
    unittest.main()
