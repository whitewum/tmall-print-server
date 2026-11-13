# Build and verify

This Android 5.0+ application provides standalone browser printing on TCP 8080
and a RAW printer bridge on TCP 9100. It uses Java plus a native ARM P1007
encoder; no root or Docker is required. See README.md and docs/web-printing.md
for operation, limits, and supported page selections.

## Local prerequisites

- Java 17 and Python 3 on macOS.
- Android SDK platform android-28 and build-tools 34.0.0 under `.tools/sdk`,
  or set `ANDROID_HOME`.
- Android NDK r26d under `.tools/ndk/android-ndk-r26d`, or set
  `ANDROID_NDK_HOME`.
- The locally obtained P1005/P1007 RAM firmware in `assets/sihpP1005.dl`.
  `python3 tools/prepare_firmware.py [path/to/HP1005.acl]` prepares it from
  the installed HP driver. It never contacts a printer or flashes Android.

## Firmware and signing

The public source archive intentionally omits HP firmware. Obtain the driver for
your own printer from its legitimate source, then prepare the local RAM firmware.
The default extractor path is the installed macOS HP driver; pass an explicit
HP1005.acl path if needed. The build refuses to proceed without the prepared file.
No firmware download or redistribution permission is implied.

The generated key uses a fixed local test password; it is a development convenience,
not a production signing setup. Never distribute the key. A maintainer's public
APK release requires separate signing management and a firmware distribution plan.

## Build

```sh
python3 tools/build_native.py
python3 tools/build.py
```

Output: `build/P1007Bridge.apk`. The native encoder is built from the vendored
GPL sources in `native/upstream`; provenance and license are in
`native/UPSTREAM.md` and `native/upstream/COPYING`.

Preserve `.local/bridge-test.jks` for upgrades. The build creates this local
signing key only if absent; replacing it prevents installation over the existing
app. SDK/NDK, keys, firmware, generated native binary and APKs are Git ignored.

## Automated checks

```sh
python3 tests/selection/run.py
python3 tests/http/run_tests.py
```

The HTTP checks use a stub renderer and sender and do not print paper. They
require a usable local network address; set `TMALL_HTTP_TEST_IP` if automatic
address selection is unsuitable. Physical print verification and remaining
power-recovery limitations are recorded in `docs/verification.md`.

## Export public source

```sh
python3 tools/export_source.py
```

This creates a ZIP from tracked source without Git history or local assets.
It does not upload anything. See docs/publishing.md before making a public release.
Windows APK builds and a public APK without embedded proprietary firmware are
not implemented or verified by this preparation.
