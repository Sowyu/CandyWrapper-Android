# CandyWrapper for Android

A native Kotlin and Compose client for T3 Code. The user runs T3 Code on a Mac and reads it from an Android phone. Performance and connection speed are the first requirement: cold start, T3 Connect connect time, opening a thread and catching up after work on the Mac.

## Layout

- `core` is a pure-Kotlin JVM module with no Android dependency: RPC framing and connection (`rpc`), HTTP clients (`http`), pairing and environment HTTP (`auth`), Clerk, relay and DPoP (`connect`), reducers, sessions and command encoders (`sync`), models (`model`), the sidebar's sections, project groups and snooze rules (`sidebar`), Markdown and code highlighting (`markdown`), diff parsing and tool output (`diff`), cache and secret interfaces (`store`) and timing marks (`timing`).
- `app` is the Android app: boot and wiring (`AppGraph`, `CandyApp`, `MainActivity`), SQLite and Keystore storage (`data`), and Compose screens (`ui`). Screens draw with the kit in `ui/Kit.kt`, `ui/Theme.kt` and `ui/Icons.kt`. There is no Material dependency. `ui/Shell.kt` is the frame, `ui/Sidebar.kt` T3's sidebar, `ui/LegacySidebar.kt` the per-project tree behind Settings > Legacy sidebar and `ui/SidebarChrome.kt` what both share; [docs/design.md](docs/design.md) says where each value comes from.
- `core/src/test/resources/transcripts` holds recorded traffic from a real `t3 serve`, copied from CandyWrapper's fixtures. Tests replay it.

## Blueprint

The macOS app at `~/CandyWrapper` is the reference. Its `docs/protocol.md` is the wire contract; cite its section numbers in comments. The schemas' ground truth is `~/t3pretzel/packages/contracts` and `packages/client-runtime`. Read those repos, never edit them.

## Commands

- `./gradlew :core:test` runs every test. It takes about 10 seconds.
- `./gradlew :app:assembleDebug` and `./gradlew :app:assembleRelease` build the APKs. Set `ANDROID_HOME=$HOME/Android/Sdk`.
- `./gradlew :app:recordRoborazziDebug` rewrites the screenshots in `app/src/test/snapshots`; `verifyRoborazziDebug` compares against them. Screens take plain state (`SidebarPanel`, `LegacySidebarPanel`, `ThreadView`, `Composer`) so the tests can draw them without a server.
- The system JDK on the dev machine has no `javac`; Gradle uses a JDK 17 toolchain.

## Rules

- Stay wire compatible with official T3 servers. Do not change what the client sends without reading the contract.
- Never reuse T3's bundle id or URL schemes. The application id is `org.anikodev.candywrapper`.
- Keep the core free of Android imports, so it runs in JUnit.
- No DI framework. Dependencies stay at OkHttp, kotlinx.serialization, kotlinx.coroutines, AndroidX Compose and profileinstaller.
- Decoding, reducing and Markdown parsing happen off the main thread. The UI gets immutable lists whose unchanged rows keep their instance.
- Nothing repaints or ticks while idle. No spinners that run without progress; show the real step instead. Motion is T3's, and only while the user does something: 150 ms fades and press scale, 200 ms for the drawer and sheets.
- Copy T3's look from CandyWrapper's references before inventing one. Note a deliberate difference in docs/design.md.
- Add a Timeline mark for any new step on the start, connect or thread-open path.
- Do not launch an emulator or touch a device unless the user agrees.
- Commit subjects look like `fix(core): what changed`. No AI attribution in commits.
- Every command goes through `Commands` in core, with a test against the contract. The UI never builds JSON.
- Mark a deliberate shortcut with `// ponytail: <ceiling>, <upgrade trigger>`.
