# SPDX-License-Identifier: GPL-2.0-or-later
from pathlib import Path
import subprocess, zipfile

import os, shutil
project = Path(__file__).resolve().parent.parent
base = project
sdk = Path(os.environ.get('ANDROID_HOME', str(project / '.tools/sdk')))
firmware = project / 'assets/sihpP1005.dl'
if not firmware.is_file():
    raise SystemExit('HP firmware is not included in public source. Run python3 tools/prepare_firmware.py /path/to/HP1005.acl first; see BUILD.md')
build = project / 'build'
build.mkdir(exist_ok=True)
classes = build / 'classes'
if classes.exists():
    shutil.rmtree(classes)
classes.mkdir(exist_ok=True)
tools = sdk / 'build-tools/34.0.0'
android = sdk / 'platforms/android-28/android.jar'
java_home = os.environ.get('JAVA_HOME')
if not java_home:
    java_home = subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip()
java = Path(java_home) / 'bin'
def run(args):
    subprocess.run([str(a) for a in args], check=True)
encoder = project / 'assets/foo2xqx-armeabi-v7a'
if (project / 'native/upstream').exists() and not encoder.is_file():
    raise SystemExit('Run python3 tools/build_native.py first')
sources = sorted(project.glob('src/**/*.java'))
run([java/'javac', '-source', '8', '-target', '8', '-encoding', 'UTF-8', '-cp', android, '-d', classes, *sources])
run([tools/'aapt', 'package', '-f', '-M', project/'AndroidManifest.xml', '-S', project/'res', '-A', project/'assets', '-I', android, '-F', build/'unsigned.apk'])
run([java/'java', '-cp', tools/'lib/d8.jar', 'com.android.tools.r8.D8', '--min-api', '21', '--lib', android, '--output', build, *sorted(classes.glob('**/*.class'))])
with zipfile.ZipFile(build/'unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as archive:
    archive.write(build/'classes.dex', 'classes.dex')
run([tools/'zipalign', '-f', '4', build/'unsigned.apk', build/'aligned.apk'])
key = project/'.local/bridge-test.jks'
key.parent.mkdir(exist_ok=True)
if not key.exists():
    run([java/'keytool', '-genkeypair', '-keystore', key, '-storepass', 'android', '-keypass', 'android', '-alias', 'bridge', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '3650', '-dname', 'CN=P1007 Local Bridge'])
run([java/'java', '-jar', tools/'lib/apksigner.jar', 'sign', '--ks', key, '--ks-key-alias', 'bridge', '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--min-sdk-version', '21', '--out', build/'P1007Bridge.apk', build/'aligned.apk'])
run([java/'java', '-jar', tools/'lib/apksigner.jar', 'verify', '--verbose', build/'P1007Bridge.apk'])
print(build/'P1007Bridge.apk')
