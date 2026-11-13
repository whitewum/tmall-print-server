# SPDX-License-Identifier: GPL-2.0-or-later
from pathlib import Path
import os, subprocess
root = Path(__file__).resolve().parent
project = next(p for p in root.parents if (p / 'AndroidManifest.xml').is_file())
java = Path(os.environ.get('JAVA_HOME') or subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip()) / 'bin'
classes = root / 'classes'
classes.mkdir(exist_ok=True)
subprocess.run([str(java/'javac'), '-source', '8', '-target', '8', '-encoding', 'UTF-8', '-d', str(classes), str(project/'src/net/local/p1007bridge/PageSelection.java'), str(root/'PageSelectionTest.java')], check=True)
subprocess.run([str(java/'java'), '-cp', str(classes), 'net.local.p1007bridge.PageSelectionTest'], check=True)
