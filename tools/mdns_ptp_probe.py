"""Query local PTP/IP DNS-SD advertisements and print non-sensitive TXT flags."""

import re
import socket
import struct


QUESTION = b"\x04_ptp\x04_tcp\x05local\x00" + struct.pack("!HH", 12, 0x8001)
QUERY = struct.pack("!HHHHHH", 0x1234, 0, 1, 0, 0, 0) + QUESTION

with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp:
    udp.settimeout(2)
    udp.sendto(QUERY, ("224.0.0.251", 5353))
    seen = 0
    try:
        while True:
            response, _ = udp.recvfrom(8192)
            seen += 1
            txt = [item.decode("ascii", "replace") for item in re.findall(rb"(?:apps|ver|vid|pid|seq)=[\x20-\x7e]{1,64}", response)]
            # Only expose the low-sensitivity state flag, never camera GUID or address.
            apps = [item.split("\x00", 1)[0] for item in txt if item.startswith("apps=")]
            print(f"PTP mDNS reply bytes={len(response)} apps={apps or 'absent'}")
    except socket.timeout:
        if seen == 0:
            print("No PTP mDNS reply reached this PC")
