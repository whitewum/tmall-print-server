#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
"""Export tracked source without private files or Git history; never publishes."""
import argparse
import hashlib
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, default=ROOT / 'build/tmall-print-server-source.zip')
args = parser.parse_args()
paths = subprocess.check_output(['git', '-C', str(ROOT), 'ls-files', '-z']).decode().split('\0')
paths = [p for p in paths if p]
for required in ['LICENSE', 'THIRD_PARTY_NOTICES.md', 'BUILD.md', 'native/UPSTREAM.md']:
    if required not in paths:
        raise SystemExit('Required file is not tracked: ' + required)
for name in paths:
    p = Path(name)
    if any(part in {'.git', '.local', '.tools', 'build', 'private', 'backups', '__pycache__'} or part.startswith('._') for part in p.parts):
        raise SystemExit('Refusing private/generated path: ' + name)
    if p.suffix.lower() in {'.apk', '.idsig', '.jks', '.keystore', '.p12', '.pem', '.key', '.dl', '.acl', '.img', '.wav', '.mp3', '.log'}:
        raise SystemExit('Refusing private/generated file: ' + name)
    data = (ROOT / p).read_bytes()
    # Build the patterns in pieces so this checker does not match its own source.
    if re.search(rb'/(?:Users|home)/[A-Za-z0-9_.-]+/', data):
        raise SystemExit('Review machine-specific home path: ' + name)
    if re.search(rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----', data):
        raise SystemExit('Private key marker found: ' + name)
output = args.output.expanduser().resolve()
output.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
    for name in sorted(paths):
        archive.write(ROOT / name, 'tmall-print-server/' + name)
with zipfile.ZipFile(output) as archive:
    if archive.testzip() is not None:
        raise SystemExit('ZIP integrity check failed')
digest = hashlib.sha256(output.read_bytes()).hexdigest()
output.with_name(output.name + '.sha256').write_text(digest + '  ' + output.name + '\n')
print(f'{output}\n{len(paths)} source files; SHA-256 {digest}')
