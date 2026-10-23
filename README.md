# CandyWrapper for Android

A native Android client for [T3 Code](https://github.com/pingdotgg/t3code), written in Kotlin and Jetpack Compose. It speaks the same wire protocol as the official apps, to computers paired with `npx t3 pair` and to computers linked through T3 Connect. The macOS sibling is [CandyWrapper](https://github.com/Sowyu/CandyWrapper).

It exists because the official Android app is slow to start, slow to connect over T3 Connect and slow to catch up on a thread. Speed comes first here. Feature count comes second.

## Install

Every push to `main` builds a release APK and attaches it to the [nightly release](https://github.com/Sowyu/CandyWrapper-Android/releases/tag/nightly). The release notes say which key signed it. With the CI debug key, a nightly installs over the previous one as long as the CI cache kept that key; if Android refuses the update, uninstall first.

Use the release APK when judging speed. Debug builds run Compose without optimizations and are several times slower.

## What works

- Pair a computer by pasting the link from `npx t3 pair`, or share it to the app.
- Sign in to T3 Connect with an email code or a password, list the linked computers and connect to them.
- Environment list, thread list per environment grouped by project, and a read-only thread view with messages, collapsed tool calls, thinking and plans, updating live.
- The thread list and recently opened threads are stored on the phone, so the app opens to them before any network.
- Light and dark themes from CandyWrapper's design tokens.
- A Timing screen with every startup, connect and thread-open step in milliseconds, also logged to logcat with tag `CWTiming`.

Not built yet: sending messages, approvals, diffs, terminal, HTML renders, and settings beyond connections and timing.

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

CI signs with a release key when these repository secrets exist, and with its debug key otherwise:

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

Switching from the debug key to the release key changes the signature, so that one update needs an uninstall.

## Reporting speed

Open Timing from the environment list, then Copy. The report has the device, each environment's state and every mark since the process started. `adb logcat -s CWTiming` shows the same marks live. The budgets are in [docs/perf-budgets.md](docs/perf-budgets.md).
