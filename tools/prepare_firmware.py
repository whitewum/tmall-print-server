# SPDX-License-Identifier: GPL-2.0-or-later
"""Prepare RAM-only printer firmware from the HP driver already installed on this Mac.

Framing follows OpenPrinting/foo2zjs arm2hpdl.c. The proprietary firmware is
kept local and excluded from Git. This script never contacts a printer.
"""
from pathlib import Path
import argparse
import struct

parser = argparse.ArgumentParser()
parser.add_argument('source', nargs='?', default='/Library/Printers/hp/laserjet/hplaserjetzjs.bundle/Contents/Resources/HP1005.acl')
args = parser.parse_args()
body = Path(args.source).read_bytes()
if len(body) < 100 or not body.startswith(b'20') or body[8:12] != b'\x7fELF' or b'HP LaserJet P1005' not in body[-100:]:
    raise SystemExit('Unexpected HP firmware format; no output written')
payload = (b'\x1b%-12345X@PJL ENTER LANGUAGE=ACL\r\n' + b'\x00\xac\xc0\xde'
           + struct.pack('>I', len(body) - 8) + body + b'\x1b%-12345X')
output = Path(__file__).resolve().parent.parent / 'assets/sihpP1005.dl'
output.parent.mkdir(exist_ok=True)
output.write_bytes(payload)
print(f'{output}: {len(payload)} bytes')
