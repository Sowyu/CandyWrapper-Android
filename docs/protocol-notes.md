# Protocol notes

Choices this client makes on the wire, and the questions still open. The contract is CandyWrapper's `docs/protocol.md`; section numbers below refer to it.

## What the client sends

- Socket parameters: `clientSurface=mobile`, `clientDeviceType=phone`, `clientOs=Android`, `clientOsMajorVersion`, `clientDeviceModel`, and `connectionMethod=direct` for paired computers or `relay` for every T3 Connect route, direct ones included (2.2, 10.11).
- Token exchange: `client_device_type=mobile`, `client_os=Android`, and a label like "CandyWrapper on Pixel 8" (4.3).
- The relay client id is `t3-web`, as CandyWrapper for macOS uses with the user's approval (10.1, decisions 2026-10-09).
- First requests on a new socket: `orchestration.subscribeShell` with `requestCompletionMarker` and the cached `afterSequence`, one `orchestration.subscribeThread` per open thread with `acceptBoundedSnapshot` and `acceptCompactTurnItems`, and `server.getConfig` for `directEndpoints` (2.1, 7.6, 7.7).
- The client never sends a command. It does not send `thread.visit`, so reading a thread on the phone does not mark it read on the computer.

## Direct and relay routes

A direct address is discoverable without a server change. `ServerConfig.directEndpoints` lists the LAN and tailnet addresses the computer listens on (contracts `server.ts:666-698`). The client reads it after any successful connect and stores it with the environment. The next connect races those addresses against the tunnel; a learned address must answer `/.well-known/t3/environment` with the same environment id before any ticket goes there. Loopback addresses are dropped.

The first connect to a T3 Connect computer always goes through the tunnel, because there is nothing learned yet. The official client also re-checks for a better route every 60 s while on a fallback route (10.6, 10.8); this client checks only when it connects.

## Open questions

1. Clerk sign-in for a third-party app. The relay's `/v1/client/dpop-token` accepts only a Clerk session JWT from the `t3-relay` template, so the client signs in through Clerk's Frontend API in native mode, as CandyWrapper for macOS does (10.2, 10.11; CandyWrapper `docs/research/M-clerk-native.md`). That works today against `clerk.t3.codes` with email codes and passwords. Nothing guarantees it keeps working: T3 can change its Clerk settings, and the header set is clerk-ios's (`x-ios-sdk-version`), copied from the macOS build and not yet tried from Android. Social sign-in and passkeys are left out, since they would borrow T3's own redirect URLs. The durable answer is for T3 to allow this app's client.
2. Whether `t3-mobile` would be the more accurate relay client id. It may also request `mobile:registration`, which this app has no use for.
3. Whether the server should see `clientSurface=mobile` from a third-party app. The parameters are attribution only (2.2).
4. Whether reusing a ticket across connects inside its five minutes stays allowed. Today the server verifies tickets and does not consume them (4.4); if that changes, the cached ticket fails with HTTP 401 and the client mints a new one.
5. History paging. The thread view shows the bounded window the server sends (the last 10 user turns). Older pages from `/api/orchestration/threads/:id/history` are not loaded yet.
6. The superseded-attempt visibility rule for `run_interrupt_result` is not applied (7.8), because run attempts are not kept.
