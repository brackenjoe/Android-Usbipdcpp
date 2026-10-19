#!/usr/bin/env python3
"""The real box side of auth v1, for the JVM end-to-end test.

    python3 -I box_auth_server.py REFERENCE_PY KEY_HEX

Imports usbip_dialin.py by path, listens on 127.0.0.1:<free port>, prints
"PORT <n>", then for every connection runs the reference's box_auth (timeout
5 s). Success: prints "RESULT ok" and sends the 5 bytes BOXOK, standing in for
the USB/IP request that follows. Failure: prints "RESULT refuse <ExceptionClass>"
and closes, sending nothing more. Exits on its own after 60 s. KEY_HEX is a fake
test key.
"""
import importlib.util
import socket
import sys
import time

# The reference is in the repository: do not leave a __pycache__ next to it
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('usbip_dialin', sys.argv[1])
ref = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ref)
key = bytes.fromhex(sys.argv[2])

srv = socket.socket()
srv.bind(('127.0.0.1', 0))
srv.listen(8)
srv.settimeout(1.0)
print('PORT', srv.getsockname()[1], flush=True)
end = time.monotonic() + 60
while time.monotonic() < end:
    try:
        conn, _ = srv.accept()
    except socket.timeout:
        continue
    try:
        ref.box_auth(conn, key, 5.0)
        print('RESULT ok', flush=True)
        conn.sendall(b'BOXOK')
        time.sleep(0.3)
    except Exception as e:  # noqa: BLE001 - the test reads the class name
        print('RESULT refuse', type(e).__name__, flush=True)
    finally:
        conn.close()
