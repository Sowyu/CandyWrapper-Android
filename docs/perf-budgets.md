# Performance budgets

These are the targets, how the code is built to meet them and which Timeline marks measure them. Every mark is in milliseconds since the process forked; the Timing screen and `adb logcat -s CWTiming` show them. Measure release builds only.

## Budgets

| Budget | Target | Marks |
| --- | --- | --- |
| Process start to the cached thread list on screen | under 500 ms on a mid-range phone | `cache.loaded`, `first.frame` |
| Opening a cached thread to its first painted rows | under 100 ms | `thread.tap`, `env.<name>.thread.<id>.cache.loaded`, `thread.<id>.paint` |
| Opening a thread to live | no budget yet, report the number | `env.<name>.thread.<id>.live` |
| Connect, T3 Connect, nothing expired | socket open on the first round trip after TLS | `connect.<name>.*`, `env.<name>.socket.open` |
| Shell caught up after a reconnect | report the number | `env.<name>.shell.live` |

## How the code meets them

Cold start:

1. `CandyApp.onCreate` starts a boot thread that opens SQLite and reads every environment and its thread-list rows. Rows are plain columns, so nothing decodes JSON.
2. `MainActivity` waits up to 400 ms for that read, which usually finishes while the activity is being created, so the first frame shows the cached list. The last thread list opened reopens directly.
3. Connecting starts on the boot thread right after the read, before the first frame. DNS for every known host resolves in parallel.
4. The release APK carries a baseline profile, applied by profileinstaller on sideloaded installs, so start-up code runs compiled ahead of time.

Connecting. Each path below lists the serial round trips before the socket opens.

| Stored state | Round trips | What runs at the same time |
| --- | --- | --- |
| A ticket from the last five minutes | the socket upgrade | |
| A credential (paired: 30 days; T3 Connect: one hour) | ticket, then upgrade on the same connection | |
| T3 Connect, relay token under 30 minutes old | relay connect, `/oauth/token`, ticket, upgrade | the tunnel descriptor check, which also warms the tunnel's TLS connection |
| T3 Connect, nothing stored | Clerk JWT, relay token, relay connect, `/oauth/token`, ticket, upgrade | a request that warms the relay's TLS connection during the Clerk call |

The official client runs the full sequence on every connect: Clerk, relay token, relay connect, descriptor, token exchange, ticket and socket, with a fresh TLS handshake to each host.

- Environment calls use an HTTP/1.1 client that shares its pool with the WebSocket client, so the upgrade reuses the connection the ticket request opened.
- The relay token, the environment token, the last ticket and the Clerk client token are stored encrypted (AES-GCM under an Android Keystore key). The DPoP key lives in Android Keystore.
- Every attempt races the primary address against the LAN and tailnet addresses the computer reported in `server.getConfig` last time, and keeps the first socket that opens. On the home network that skips the tunnel.
- After the socket opens, the shell subscription (with `afterSequence` from the cache), every open thread's subscription and `server.getConfig` go out back to back without waiting.
- Every stream chunk is acked as soon as it is applied.
- No pings on direct routes. On a T3 Connect tunnel, one ping a minute while the app is in front, because tunnels drop idle sockets. Returning to the app sends `server.probe` with a 3 s limit instead of reconnecting.
- In the background nothing reconnects until the app comes back.

Threads:

- An opened thread paints from its cached rows, then subscribes with `afterSequence` from that cache, so the server replays only the events since. The four most recent threads stay in memory for instant reopen.
- Decoding, reducing, Markdown parsing and grouping tool calls happen on the socket's reader thread. The UI gets one immutable list per chunk, and rows that did not change keep their instance, so Compose skips them.
- The thread list is a reversed `LazyColumn` with stable keys and content types, so it starts at the newest row with no scroll work.
- Markdown renders as Compose text. There is no WebView.

## Measured on the JVM

From `PerfTest`, a desktop JVM, warm, median of 30 runs. A mid-range phone is roughly 3 to 5 times slower.

| Step | Time |
| --- | --- |
| Decode a bounded thread snapshot, 70 KiB frame | 0.55 ms |
| Decode and reduce it to 19 rows | 0.87 ms |
| Restore a thread from its 13 KiB cache blob | 0.37 ms |
| Parse a 6 KiB Markdown reply | 1.5 ms |
| Shell snapshot of 300 threads to the list model | 7.1 ms |
| Rebuild the 300-thread list after one delta | 0.17 ms |
| Decode one `thread.updated` row | 0.02 ms |

Device numbers are not measured yet. Run a release build, open Timing, and copy the report.
