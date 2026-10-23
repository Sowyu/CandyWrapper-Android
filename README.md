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
- T3 Code's sidebar as the main navigation: a drawer on phones (header button or a swipe from the left edge), always beside the thread on tablets and unfolded foldables. Pinned and active threads are cards with their status; snoozed and settled threads sit on shelves below. Long press a thread to pin, snooze, settle, rename, mark read or unread, or archive it, with Undo in the footer. Search, a project filter and the archived list are there too.
- Settings > Sidebar > Working section folds threads that are busy without you into a Working shelf, newest send first, as T3's beta does; they come back to the top of the list when they need you.
- The machine switcher at the top picks one computer or All machines, which lists every computer's threads together, merges the same repository across computers and names each thread's machine. With more than one computer it starts on All machines and remembers the choice.
- Settings > Legacy features brings back T3's per-project sidebar and plan mode. Both are off by default, as in T3, and stay on the phone.
- The app opens on the last thread you had open, drawn from the phone's copy before any network.
- A live thread view: Markdown with coloured code blocks, tool calls that expand to their output or diff, run diffs from checkpoint cards, older history as you scroll up, and timestamps.
- Images where T3 shows them: your attachments and the agent's, Markdown images, and the images a tool returned or looked at. Tap one for a full-screen viewer with pinch zoom, swipe down to close, Save to Pictures and Share.
- A composer: send, queue or steer during a run, Stop, model and effort, access mode and image attachments, plus plan mode when it is turned on. Start a new thread in any project on any computer.
- A prompt stash, as in T3: Stash prompt (in the composer's ⋯ on a phone) keeps the draft and its images on the phone and clears the composer. The Stash tab over the composer lists the last 20, newest first, to restore, swap with the current draft, or delete. Nothing is sent to the computer.
- Approvals and agent questions answered in place of the composer, and queued messages you can steer or cancel.
- Opening a thread marks it read on the computer.
- The thread list and recently opened threads are stored on the phone, so the app opens to them before any network.
- T3's desktop look adapted to a phone, in light and dark, following the system or set in Settings. [docs/design.md](docs/design.md) lists the sources and where it differs from T3.
- A Timing screen with every startup, connect and thread-open step in milliseconds, also logged to logcat with tag `CWTiming`.

Not built yet: terminal, HTML renders, file mentions and slash commands, editing queued messages, dragging threads to reorder them, a custom snooze time, and fork.

## Build

You need JDK 17 (Gradle provisions one if missing) and the Android SDK.

```sh
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew :core:test          # protocol, reducers, DPoP, Markdown, connect sequences
./gradlew :app:assembleDebug  # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease
./gradlew :app:recordRoborazziDebug  # screenshots into app/src/test/snapshots
```

The screenshots in [app/src/test/snapshots](app/src/test/snapshots) show every main screen in light and dark, on a phone and a tablet.

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

Open Timing from the sidebar footer (the pulse icon), then Copy. The report has the device, each environment's state and every mark since the process started. `adb logcat -s CWTiming` shows the same marks live. The budgets are in [docs/perf-budgets.md](docs/perf-budgets.md).
