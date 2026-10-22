# Official Memora synthetic share harness

This standalone debug donor instruments **itself**, then drives the unmodified, permanent-signed `com.personallibrary.app` through Android accessibility and real content-URI intents. It does not link app code or require Memora's signing key. Backup instrumentation links the existing core archive engine solely to authenticate/decrypt its own UI-generated public exports with a generated fixture password; it compares every manifest record and staged asset hash/size, and removes its own private decrypted stages in cleanup. Root `settings.gradle.kts` must include `:qa-harness` inside its existing non-`coreOnly` block.

Tests fail closed unless `ro.kernel.qemu=1`, the available `ro.boot.qemu.avd_name` / `ro.kernel.qemu.avd_name` properties identify only `Memora-QA-API37`, and installed Memora is 1.0.1/code3 with its permanent certificate. Tests operate through UI, granted content URIs and scoped emulator commands, without reading product-private credentials or databases. Ordinary suites turn on **Pause Background AI through Memora Settings UI**. The separate live-AI class briefly unpauses only after verifying the exact excluded-record scope and one eligible synthetic control, performs at most one Ask action, and verifies pause again in cleanup. No reset implementation exists. Destructive management/restore steps target only their own uniquely named fixtures.

Run serially while no other tool operates the emulator UI. Use the existing JDK21/SDK and verified serial:

```powershell
$env:ANDROID_SERIAL = 'emulator-5580'
$env:ANDROID_HOME = Join-Path $PWD '.tools/android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$PWD\.tools"
.\gradlew.bat :qa-harness:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.memora.qa.harness.OfficialShareTest' --no-daemon --no-configuration-cache --console=plain --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx1536m'
```

This installs only the donor and its instrumentation package. The official Memora APK must already be installed. Build-only checkpoint: `:qa-harness:assembleDebug :qa-harness:assembleDebugAndroidTest` with the same Gradle flags. No SDK components or new dependency versions are required.

For a local fixed-candidate rerun, **OfficialFilePickerTest, OfficialShareTest and OfficialEnvironmentTest** accept runner argument `candidateApkSha256=<exact local APK SHA256>`. It streams the installed APK through SHA256 and refuses a mismatch before any product interaction. Omitting the argument retains the permanent-certificate guard. The three offline suites reuse one fail-closed identity helper. Candidate results are logged explicitly and must be recorded separately from official release evidence. This option does not change the emulator, pause-AI or fixture guards, and is unavailable in the live-provider class.

Pass it with `-Pandroid.testInstrumentationRunnerArguments.candidateApkSha256=<hash>` for Gradle, or `-e candidateApkSha256 <hash>` for direct `adb shell am instrument`.

Share tests cover:

- T10 plain-text explicit incoming intent, Share Preview, Save, local search and reopened content.
- T10 actual Android chooser selection of Memora for a reserved-domain link, Save and reopened details. It intentionally does not navigate the link or claim browser/network acceptance.
- T10 creates24 owned synthetic collections, selects the last option, proves Discard creates no item, then saves and reopens the exact body through that last collection. Collections remain for subsequent long-list media checks.
- T11 JPEG and PNG incoming FileProvider shares, saved PHOTO detail previews and byte-exact external opening.
- T14 GIF incoming share, PHOTO preview and byte-exact external opening. Animation timing is not claimed.
- T12 deterministic standard PDF, actual page-rendering UI and byte-exact external opening.
- T13 deterministic five-second PCM WAV, actual in-app Play/Pause state and byte-exact external opening. Audible output quality is not claimed.
- T15 real SEND_MULTIPLE plus ClipData/read grants, two distinct saved search results and both originals reopened.
- T13 generated MP4 thumbnail and byte-exact external opening; actual third-party playback remains unclaimed.
- T14 ordinary and sticker-named WebP classification/previews/originals, plus malformed WAV/MP4 preview fallbacks that retain the originals. A filename-driven sticker classification does not certify real WhatsApp integration.
- T15 a missing provider source retains the other file and reports one failure; repeated URI within one batch saves once, while a separate share creates a second logical record with the same original bytes.

The JPEG/PNG/GIF bytes are exact copies of validated synthetic QA assets. PDF and WAV use Android/JDK standard APIs only. The donor's narrow `synthetic-shares/` FileProvider grants test inputs. Its external viewer accepts only Memora's FileProvider URI while an expected synthetic hash is armed, reads at most 2MiB, and displays success only for matching bytes. This tests Memora's real **Open File** URI permission and bytes, independently of external viewer availability; it does not certify third-party viewer rendering.

Each test creates unique synthetic names and stores tree/screenshot checkpoints in donor-private `files/qa-evidence/<test>-<elapsed>/`. `MemoraQA` log lines record case/method start and PASS/FAIL without library content. Checkpoint capture refuses unexpected foreground packages. The harness is debuggable, so evidence can be collected with the explicitly verified emulator serial, for example:

Checkpoint artifacts include visible fixture text and full screenshots. Use only this dedicated AVD with its known synthetic dataset; emulator name and APK identity alone do not prove that arbitrary future content is synthetic. Never reuse these checkpoints on a personal library.

```powershell
.tools/android-sdk/platform-tools/adb.exe -s emulator-5580 shell run-as com.memora.qa.harness ls files/qa-evidence
```

Touch fallbacks derive coordinates from live accessibility bounds. Waits, text entry (`ACTION_SET_TEXT`), scrolling and Back use bounded native UiAutomation helpers; no fixed-coordinate taps or UiAutomator dependency. Failures preserve evidence instead of resetting the product. Relaunching the product clears only its activity task so a previously opened donor viewer cannot remain above it; library data is retained. Ordinary text via Add → Files and official WhatsApp GIF migration use separate audit flows. Runtime outcomes and remaining media/visual limitations belong in the canonical case reports.

Additional classes cover management/search, real SAF file selection, encrypted backup/restore, native reminders/OS notifications and environment changes. Environment tests restore their original radio/display settings in cleanup. `OfficialLiveAiTest` can resume a known interrupted fixture with runner argument `resumeControlToken=QALIVE<digits>`; it still requires exactly235 excluded Random Links items and one Unfiled control with exact expected title/body. Never loosen these privacy guards to accommodate an unknown library.

T45 uses the native Developer Options Force RTL switch and verifies the actual system layout. A property write alone did not change the running layout on this AVD. It temporarily enables Developer Options, restores the original actual direction, closes only the native Settings UI, then restores the captured developer/RTL/property/debugging values. Native Settings screenshots are allowed only under the same dedicated synthetic-AVD boundary. Environment evidence includes donor-private `outcome.txt`: `STARTED` is inconclusive, `PASS` is written after the test and cleanup succeed, and `FAIL` preserves the exception stack. An interrupted ADB connection never counts as PASS.

The unchanged official APK has known QA-003 background-save observation and QA-004 Add-label defects. Assisted Add selection uses unique live accessibility bounds and logs the limitation. Some independent persistence checks restart after confirmed saving; these workarounds never certify those defects as fixed. See `qa-reports/memora-v1-final/` for observed per-case results and the distinction between official, local debug and physical evidence. Compilation or one passing method does not imply all48 cases passed.
