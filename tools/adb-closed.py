#!/usr/bin/env python3
"""Check that the TV no longer accepts ADB over the network: tools/adb-closed.py <tv ip> [port]"""
import errno, socket, sys

if len(sys.argv) < 2:
    sys.exit("usage: tools/adb-closed.py <tv ip> [port]")
host = sys.argv[1]
port = int(sys.argv[2]) if len(sys.argv) > 2 else 5555
s = socket.socket()
s.settimeout(3)
try:
    s.connect((host, port))
    print(f"OPEN: {host}:{port} still accepts connections - ADB debugging is still on")
    sys.exit(1)
except ConnectionRefusedError:
    print(f"CLOSED: {host}:{port} refused the connection - network ADB is off")
except OSError as e:
    # "No route to host" or a timeout proves nothing: the TV may be off, or macOS may be blocking us.
    print(f"UNKNOWN: {e} - is the TV on and reachable? Only 'refused' proves the port is closed")
    sys.exit(2)
finally:
    s.close()
