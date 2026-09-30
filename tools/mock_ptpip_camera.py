"""Small PTP/IP camera simulator for Android emulator smoke tests.

Run on the Windows host, then set the app's camera address to 10.0.2.2.
This exercises generic PTP/IP only. It does not emulate Nikon Zf pairing.
"""

from __future__ import annotations

import argparse
import socket
import struct
import threading
from pathlib import Path


INIT_COMMAND_REQUEST = 1
INIT_COMMAND_ACK = 2
INIT_EVENT_REQUEST = 3
INIT_EVENT_ACK = 4
INIT_FAIL = 5
OPERATION_REQUEST = 6
OPERATION_RESPONSE = 7
START_DATA = 9
END_DATA = 12
RESPONSE_OK = 0x2001
STORAGE_ID = 0x00010001
HANDLE = 42


def packet(kind: int, payload: bytes = b"") -> bytes:
    return struct.pack("<II", len(payload) + 8, kind) + payload


def read_exact(sock: socket.socket, count: int) -> bytes:
    chunks = bytearray()
    while len(chunks) < count:
        chunk = sock.recv(count - len(chunks))
        if not chunk:
            raise EOFError("peer closed connection")
        chunks.extend(chunk)
    return bytes(chunks)


def read_packet(sock: socket.socket) -> tuple[int, bytes]:
    length, kind = struct.unpack("<II", read_exact(sock, 8))
    if length < 8 or length > 1024 * 1024:
        raise ValueError(f"invalid packet length {length}")
    return kind, read_exact(sock, length - 8)


def ptp_string(value: str) -> bytes:
    if not value:
        return b"\0"
    return bytes([len(value) + 1]) + value.encode("utf-16le") + b"\0\0"


def u16_array(values: list[int]) -> bytes:
    return struct.pack("<I", len(values)) + b"".join(struct.pack("<H", x) for x in values)


def device_info(filename: str) -> bytes:
    operations = [0x1001, 0x1002, 0x1003, 0x1004, 0x1007, 0x1008, 0x1009]
    return (
        struct.pack("<HIH", 100, 6, 100)
        + ptp_string("Mock PTP/IP")
        + struct.pack("<H", 0)
        + u16_array(operations)
        + u16_array([])  # events
        + u16_array([])  # properties
        + u16_array([0x3801])  # capture formats
        + u16_array([0x3801, 0xB103])  # JPEG and Nikon RAW
        + ptp_string("Nikon")
        + ptp_string("Zf Mock")
        + ptp_string("test-only")
        + ptp_string(f"mock-{Path(filename).suffix.lower().lstrip('.')}")
    )


def object_info(filename: str, size: int) -> bytes:
    image_format = 0xB103 if filename.lower().endswith(".nef") else 0x3801
    return (
        struct.pack("<IHHIHI", STORAGE_ID, image_format, 0, size, 0, 0)
        + struct.pack("<IIIIII", 0, 0, 16, 16, 24, 0)
        + struct.pack("<HII", 0, 0, 0)
        + ptp_string(filename)
        + ptp_string("20260925T120000")
        + ptp_string("20260925T120000")
        + ptp_string("")
    )


def send_data(sock: socket.socket, txid: int, data: bytes) -> None:
    sock.sendall(packet(START_DATA, struct.pack("<IQ", txid, len(data))))
    sock.sendall(packet(END_DATA, struct.pack("<I", txid) + data))


def serve_command(sock: socket.socket, fixture: bytes, filename: str) -> None:
    sock.sendall(packet(INIT_COMMAND_ACK, struct.pack("<I", 1) + bytes(16)))
    print("command channel accepted", flush=True)
    while True:
        kind, payload = read_packet(sock)
        if kind != OPERATION_REQUEST:
            raise ValueError(f"unexpected command packet {kind}")
        _, opcode, txid = struct.unpack_from("<IHI", payload)
        args = [x[0] for x in struct.iter_unpack("<I", payload[10:])]
        print(f"operation 0x{opcode:04X} tx={txid} args={args}", flush=True)
        data: bytes | None = None
        if opcode == 0x1001:
            data = device_info(filename)
        elif opcode == 0x1004:
            data = struct.pack("<II", 1, STORAGE_ID)
        elif opcode == 0x1007:
            data = struct.pack("<II", 1, HANDLE)
        elif opcode == 0x1008:
            data = object_info(filename, len(fixture))
        elif opcode == 0x1009:
            data = fixture
        elif opcode not in (0x1002, 0x1003):
            raise ValueError(f"unsupported operation 0x{opcode:04X}")
        if data is not None:
            send_data(sock, txid, data)
        sock.sendall(packet(OPERATION_RESPONSE, struct.pack("<HI", RESPONSE_OK, txid)))
        if opcode == 0x1003:
            return


def serve_event(sock: socket.socket) -> None:
    sock.sendall(packet(INIT_EVENT_ACK))
    print("event channel accepted", flush=True)
    try:
        while sock.recv(4096):
            pass
    except OSError:
        pass


def serve_client(sock: socket.socket, fixture: bytes, filename: str, reject_init: int | None) -> None:
    with sock:
        try:
            kind, _ = read_packet(sock)
            if kind == INIT_COMMAND_REQUEST:
                if reject_init is None:
                    serve_command(sock, fixture, filename)
                else:
                    sock.sendall(packet(INIT_FAIL, struct.pack("<I", reject_init)))
                    print(f"rejected init with reason 0x{reject_init:08X}", flush=True)
            elif kind == INIT_EVENT_REQUEST:
                serve_event(sock)
            else:
                raise ValueError(f"unexpected init packet {kind}")
        except (EOFError, OSError) as error:
            print(f"connection ended: {error}", flush=True)
        except Exception as error:
            print(f"protocol error: {error}", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("fixture", type=Path)
    parser.add_argument("--port", type=int, default=15740)
    parser.add_argument("--reject-init", type=lambda value: int(value, 0))
    args = parser.parse_args()
    fixture = args.fixture.read_bytes()
    if not fixture:
        raise ValueError("fixture must contain bytes")
    print(f"serving {args.fixture.name}: {len(fixture)} bytes", flush=True)
    with socket.socket() as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind(("127.0.0.1", args.port))
        server.listen()
        print(f"listening on 127.0.0.1:{args.port}", flush=True)
        while True:
            client, _ = server.accept()
            threading.Thread(
                target=serve_client,
                args=(client, fixture, args.fixture.name, args.reject_init),
                daemon=True,
            ).start()


if __name__ == "__main__":
    main()
