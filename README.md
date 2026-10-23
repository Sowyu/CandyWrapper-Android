# CandyWrapper for Android

A native Android client for [T3 Code](https://github.com/pingdotgg/t3code), written in Kotlin and Jetpack Compose. It speaks the same wire protocol as the official apps, to computers paired with `npx t3 pair` and to computers linked through T3 Connect. The macOS sibling is [CandyWrapper](https://github.com/Sowyu/CandyWrapper).

It exists because the official Android app is slow to start, slow to connect over T3 Connect and slow to catch up on a thread. Speed comes first here. Feature count comes second.

## Install

Every push to `main` builds a release APK and attaches it to the [nightly release](https://github.com/Sowyu/CandyWrapper-Android/releases/tag/nightly) as `CandyWrapper-nightly.apk`, next to an `update.json` that names the build. The app checks it when it opens and when it comes back to the front, at most every 10 minutes, and shows a banner when a newer build exists. One tap downloads it, checks its SHA-256 and installs it. Timing has the installed version and a Check for updates button.

Builds from before the release key were signed with a CI debug key. If one of those is installed, uninstall it once and install the nightly APK by hand.

Use the release APK when judging speed. Debug builds run Compose without optimizations and are several times slower.

## What works

- Pair a computer by pasting the link from `npx t3 pair`, or share it to the app.
- Sign in to T3 Connect with an email code or a password, list the linked computers and connect to them.
- Thread lists per computer grouped by project, with search, a computer switcher, status and unread marks, rename, archive and mark read or unread.
- A live thread view: Markdown with coloured code blocks, tool calls that expand to their output or diff, run diffs from checkpoint cards, older history as you scroll up, and timestamps.
- A composer: send, queue or steer during a run, Stop, model and effort, access mode, plan mode and image attachments. Start a new thread in any project.
- Approvals and agent questions answered in place of the composer, and queued messages you can steer or cancel.
- Opening a thread marks it read on the computer.
- The thread list and recently opened threads are stored on the phone, so the app opens to them before any network.
- Light and dark themes from CandyWrapper's design tokens, following the system or set in Settings.
- A Timing screen with every startup, connect and thread-open step in milliseconds, also logged to logcat with tag `CWTiming`.

Not built yet: terminal, HTML renders, file mentions and slash commands, editing queued messages, and the full set of thread actions (pin, snooze, settle, fork).

## Build

You need JDK 17 (Gradle provisions one if missing) and the Android SDK.

```sh
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew :core:test          # protocol, reducers, DPoP, Markdown, connect sequences
./gradlew :app:assembleDebug  # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease
```

To try it against a server from an emulator, the host machine is `http://10.0.2.2:<port>`.

## Release signing

CI signs every release with one key from these repository secrets, and fails if any is missing:

- `CW_KEYSTORE_BASE64` holds the keystore file, base64-encoded.
- `CW_KEYSTORE_PASSWORD` holds the keystore password.
- `CW_KEY_ALIAS` holds the key alias.
- `CW_KEY_PASSWORD` holds the key password.

Create a key once and add the secrets:

```sh
keytool -genkeypair -v -keystore candywrapper.jks -alias candywrapper -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 candywrapper.jks | gh secret set CW_KEYSTORE_BASE64
gh secret set CW_KEYSTORE_PASSWORD
gh secret set CW_KEY_ALIAS --body candywrapper
gh secret set CW_KEY_PASSWORD
```

The key is set. Its backup lives outside the repository; losing it means every install has to be removed once, because Android only updates an app signed with the same key.

## Reporting speed

Open Timing from the environment list, then Copy. The report has the device, each environment's state and every mark since the process started. `adb logcat -s CWTiming` shows the same marks live. The budgets are in [docs/perf-budgets.md](docs/perf-budgets.md).
