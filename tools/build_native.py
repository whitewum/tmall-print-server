# SPDX-License-Identifier: GPL-2.0-or-later
"""Cross-compile the vendored P1007 encoder for Android 5 / 32-bit ARM."""
from pathlib import Path
import os
import subprocess

root = Path(__file__).resolve().parent.parent
ndk = Path(os.environ.get('ANDROID_NDK_HOME', str(root / '.tools/ndk/android-ndk-r26d')))
clang = ndk / 'toolchains/llvm/prebuilt/darwin-x86_64/bin/clang'
if not clang.is_file():
    raise SystemExit('Install Android NDK r26d under .tools/ndk or set ANDROID_NDK_HOME')
upstream = root / 'native/upstream'
output = root / 'assets/foo2xqx-armeabi-v7a'
output.parent.mkdir(exist_ok=True)
subprocess.run([str(clang), '--target=armv7a-linux-androideabi21', '-O2', '-fPIE', '-pie',
                '-Wno-deprecated-non-prototype', str(upstream / 'foo2xqx.c'),
                str(upstream / 'jbig.c'), str(upstream / 'jbig_ar.c'), '-o', str(output)], check=True)
print(output)
