#!/usr/bin/env python3
"""Prints the auth v1 test vectors as Kotlin, from the Python reference.

    python3 -I app/src/test/resources/gen_auth_vectors.py linux/usbip-dialin/usbip_dialin.py   (from the repository root)

The output is pasted into AuthVectors.kt (the constants, with this command in
a comment above them). AuthVectorsTest.kt regenerates it when python3 and the
reference are present, and fails if the committed constants differ.
Every key and nonce here is a fixed, obviously fake test value.
"""
import importlib.util
import sys

# The reference is in the repository: do not leave a __pycache__ next to it
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('usbip_dialin', sys.argv[1])
ref = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ref)

# (key, nonce_b, nonce_p): all fake. 0: counting bytes. 1: constant bytes.
# 2: key with high bytes, nonces that differ only in the last byte.
VECTORS = [
    (bytes(range(32)), bytes(range(0x40, 0x60)), bytes(range(0x80, 0xA0))),
    (b'\xaa' * 32, b'\x00' * 32, b'\xff' * 32),
    (bytes(range(0xE0, 0x100)) , b'\x11' * 31 + b'\x01', b'\x11' * 31 + b'\x02'),
]

assert ref.AUTH_MAGIC == b'UDIALIN1'
for i, (k, nb, npn) in enumerate(VECTORS):
    pt = ref._tag(k, ref.AUTH_LABEL_PHONE, nb, npn)
    bt = ref._tag(k, ref.AUTH_LABEL_BOX, nb, npn)
    print(f'    Vector(')
    print(f'        key = "{k.hex()}",')
    print(f'        nonceB = "{nb.hex()}",')
    print(f'        nonceP = "{npn.hex()}",')
    print(f'        phoneTag = "{pt.hex()}",')
    print(f'        boxTag = "{bt.hex()}"')
    print(f'    ){"," if i < len(VECTORS) - 1 else ""}')
