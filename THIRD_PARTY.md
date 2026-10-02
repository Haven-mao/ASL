# Third-party components

- QEMU 11.0.3 (GPL-2.0), Android/Bionic build from the official Termux package repository. Upstream: https://www.qemu.org/ ; packaging/source recipes: https://github.com/termux/termux-packages/tree/master/packages/qemu-system-x86-64 . The exact dependency packages, versions and hashes are recorded in `runtime-manifest.json`.
- Runtime dependencies retain their individual upstream licenses. The manifest identifies the original Termux packages; APK binaries only have versioned ELF library names shortened in place. `scripts/prepare_android_runtime.py` documents and reproduces these modifications.
- xterm.js 5.5.0 and addon-fit 0.10.0 (MIT): license texts in `android/assets/xterm-LICENSE` and `android/assets/addon-fit-LICENSE`.
- Debian GNU/Linux 12 ARM64, Linux 6.1, systemd, OpenSSH, Python, Docker and supporting Debian packages retain their individual licenses. Installed package copyright files are available in guest `/usr/share/doc/*/copyright`; kernel source is available through Debian source packages.
- Codex CLI 0.160.0 is installed from the official ARM64 release archive. Upstream source and Apache-2.0 license: https://github.com/openai/codex . Official archive SHA256 is pinned in `scripts/configure_guest.py`.

ASL's background display worker is implemented in this source tree using Android framework APIs. The independent-focus display flags were checked against Android framework source and scrcpy's implementation; scrcpy binaries are not bundled.

This development bundle is intended for local evaluation on the user's tablet. A public binary release should include the complete applicable license/source distribution materials for every bundled runtime dependency.
