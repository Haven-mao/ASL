# ASL 0.5 validation — 2026-10-03

## Installed and preserved

ASL 0.5.0-dev was installed on the Xiaomi Pad 7 Pro without uninstalling or replacing its personalized disk. The Linux account, UID1000, workspace persistence marker and SHA256 of the user's Codex configuration remained unchanged. Kernel `6.1.0-53-cloud-arm64`, systemd, 2 CPUs and the 4 GiB memory configuration were retained.

## Application control and ASLTree

- Real background Calculator workflow: learned six semantic-button steps and verified 7+8=15; reused with 3+9=12 and 6+8=14. Both runs passed, each about 3.9 seconds in the measured configuration.
- Real background Douyin search: learned four steps, then reused different queries. Query text, result tabs and result list were checked. Verified runs took about 8–12 seconds including app/network loading.
- Codex CLI actually completed calls to `android_status` and `asltree_catalog` through the registered `android` MCP server. Its JSON events reported both calls completed.
- ASLTree's native operation directory displayed learned workflows, counts, drafts and workflows needing review. The tested parameterized recipes and detailed evidence are maintained in the ASLTree repository.
- 15 ASLTree tests and five ASL Android MCP tests passed locally. ASLTree's GitHub Actions test run passed; ASL's source-check workflow runs on publication.

## Background screen

- Independent display creation and accessibility reading/clicking/input worked while Android's `FocusedDisplayId` remained 0.
- A real defect was fixed: Android put an idle virtual display to sleep. The worker now holds a wake lock for that display group only while it has an active app and the tablet is unlocked.
- Preview renders the actual background screen. Final display geometry/density follows the tablet; preview images are scaled to at most 1600 pixels, and screenshot-coordinate metadata preserves the full display dimensions.
- Completed tasks produced an actual Android notification, verified through the app's active notifications. The completion payload included the current app and task ID.
- Result selection was fixed to match the current app and task, rather than picking an older app from the background task list. The verified Douyin task moved from the virtual display to display 0.

**Cross-display limitation:** Douyin rebuilt its search page when moved to the physical display, even with matching geometry. Therefore “查看结果” opens the retained background preview by default. “打开原应用” explicitly migrates the app and may cause app-specific page reloads. The preview communicates this limitation. ASL does not silently replay business operations to reconstruct a page.

## Terminal and Linux

Independent PTY integration tests passed separate shell processes, UID1000, 101×31 resize, split Unicode input, active-job close confirmation and survival of another terminal after closing one. Earlier measured 80-line streaming output had a maximum visible update gap of 91 ms; local tab selection took 2.3–3.5 ms. These are terminal measurements, not promises about model-generation speed.

The black-screen regression was a wrapper CSS name colliding with xterm's own class. The installed APK contains the permanent `.asl-terminal` fix. Touch scrollback, per-tab history and Esc-to-`1b` were validated during development; terminal state persists across ordinary activity switching.

Docker `hello-world` ran from a verified imported official ARM64 image. Direct guest access to Docker Hub previously timed out and is not claimed fixed. TCG software virtualization still makes cold starts slower than hardware virtualization.

## Artifacts

APK Java/dex compilation and signature verification passed. Generic image `e2fsck` and `qemu-img check` passed. The generic image contains the default account and no development authorized SSH key or user API configuration. Git excludes build outputs, signing keys, private keys, disk images, databases and generated ASLTree staging files.
