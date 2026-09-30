"""Send exactly one PTP/IP InitCommandRequest to a camera and print its response.

Diagnostic only: no PTP session is opened and no camera settings are written.
"""

import argparse
import socket
import struct
import uuid


def read_exact(connection: socket.socket, size: int) -> bytes:
    result = bytearray()
    while len(result) < size:
        chunk = connection.recv(size - len(result))
        if not chunk:
            raise EOFError("camera closed the connection")
        result.extend(chunk)
    return bytes(result)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("host")
    parser.add_argument("--port", type=int, default=15740)
    args = parser.parse_args()

    identity = uuid.uuid4().int
    guid = struct.pack("<QQ", identity >> 64, identity & ((1 << 64) - 1))
    name = "Zf Transfer Probe".encode("utf-16le") + b"\0\0"
    payload = guid + name + struct.pack("<I", 0x00010000)
    request = struct.pack("<II", len(payload) + 8, 1) + payload

    with socket.create_connection((args.host, args.port), timeout=3) as connection:
        connection.settimeout(3)
        connection.sendall(request)
        length, kind = struct.unpack("<II", read_exact(connection, 8))
        if length < 8 or length > 4096:
            raise ValueError(f"invalid response length: {length}")
        response = read_exact(connection, length - 8)
    if kind == 5 and len(response) >= 4:
        reason = struct.unpack_from("<I", response)[0]
        print(f"InitFail reason=0x{reason:X}")
    elif kind == 2:
        print("InitCommandAck")
    else:
        print(f"response type={kind} payload_bytes={len(response)}")


if __name__ == "__main__":
    main()
