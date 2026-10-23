# Orchestrator (experimental, Redesign only)

CandyWrapper's Orchestrator on the Mac, ported to the phone. The spec is CandyWrapper's `docs/orchestrator.md`; this page says how the phone does it and how the phone and the Mac share one Orchestrator against the same T3 servers without a server change and without a Mac change.

It is off by default. Settings > Appearance shows "Experimental Orchestrator features" only while Redesign is on. With the toggle off, or Redesign off, the app creates no Orchestrator view, keeps no relay fields in the shells and runs nothing.

## Where it lives

- `core/orchestrator` holds everything that decides: the config and the four default model rows (`OrchestratorConfig.kt`), the brief, its fingerprint and its Hands off list (`OrchestratorBrief.kt`), events, the ask-first rule and the 30-second batch (`OrchestratorEvents.kt`), the fenced plan, memory, digest and call blocks (`OrchestratorBlocks.kt`), the call relay with Hands off, ask-first and the worker limit checked in code (`OrchestratorRelay.kt`), who relays (`RelayOwnership.kt`), the chat items (`OrchestratorChat.kt`), the Overview and Needs you (`OrchestratorOverview.kt`) and the bots (`BotGeometry.kt`). JUnit tests mirror the Mac's `OrchestratorTests`, `OrchestratorRelayTests`, `OrchestratorChatTests` and `BotGeometryTests`, plus `RelayOwnershipTest` for the protocol below.
- `ShellProjection` keeps an `OrchThread` per active thread (subagents too) only while the Orchestrator is on. The cache stores none of these fields, so turning it on subscribes the shell once without the cursor for a full snapshot.
- `app/orchestrator/OrchestratorController.kt` runs it on one serial dispatcher. `data/OrchestratorStore.kt` keeps the settings in `files/orchestrator/config.json`.
- `ui/OrchestratorRow.kt` is the pinned sidebar row, `ui/OrchestratorPane.kt` the pane, `ui/OrchestratorSettings.kt` Settings > Orchestrator and `ui/OrchestratorBot.kt` the Compose bot.

## Finding the Mac's Orchestrator

The Mac keeps its thread id only in its own defaults (`OrchestratorConfig.machines[env].threadId`), so the phone finds it on the server, once per machine and process:

1. The thread the Mac's "while closed" task names. The Mac makes one per machine with the prompt `OrchestratorBrief.scheduledCheckPrompt` and the Orchestrator's `threadId`; the phone matches the prompt exactly and takes the newest such task whose thread is live.
2. Else a top-level thread titled with the phone's name or "Orchestrator" whose first user message starts with `# Orchestrator brief`, the Mac's marker. At most three candidates are read with `orchestration.getThreadProjection`.
3. Else Settings and the pane offer "Start on <machine>". The Mac never adopts a thread it didn't start, so if CandyWrapper on the Mac later starts one there will be two. Both screens say to start it on the Mac first when you use both.

The phone never creates a second thread while one exists: Start shows only after discovery found nothing, and an adopted or started thread is remembered per machine. An archived or deleted Orchestrator is forgotten, as on the Mac.

Settings are per device, as on the Mac (`decisions.md`: client settings live in each client). On adoption the phone records its own brief's fingerprint as already sent, so switching devices never rewrites the Mac's brief by itself; only a change you make on the phone (name, prompt, models, limits, Hands off, projects or machines) goes out as an "Updated brief" with your next message. The phone takes the thread's title as its name if you never named it.

## Two apps, one Orchestrator

Relaying means sending other threads' turn ends, failures and requests as `Event:` lines, and running the Orchestrator's `orchestrator-call` blocks on every machine. Two apps doing it at once would send every event twice and run every call twice, so exactly one may relay. The only shared state is on the server, and the Mac already writes a signal there: it turns its "while closed" task off (`scheduledTasks.upsert`, `enabled: false`) when it opens and back on (`enabled: true`) when it quits. An upsert always writes a new `updatedAt` (ScheduledTaskService.ts:1063-1190); `setEnabled` writes one only when the flag flips (:1192-1220).

The rule, in `RelayOwnership.decide`:

1. In front, with the toggle on, the phone subscribes to the home machine's tasks (`scheduledTasks.subscribe`, a push stream, nothing polled) and finds the Orchestrator's task.
2. The task is on: no CandyWrapper is relaying. The phone sends `scheduledTasks.setEnabled(false)` and keeps the `updatedAt` its write produced as its hold (`ScheduleClaim`, saved with the machine). If the server wrote nothing, because the task was already off, the phone holds nothing.
3. The task is off with exactly the phone's `updatedAt`: nobody else wrote since, so the phone relays.
4. The task is off with any other `updatedAt`: another app turned it off, CandyWrapper on the Mac or another phone. The phone only watches, and the pane says "CandyWrapper on your Mac relays while it's open."
5. No task names the thread: the Mac has its "while closed" check off, so nothing says whether it is open. The phone only watches.
6. Leaving the front, or the toggle going off, the phone sends what its batch holds at once, then turns the task back on only while it still holds it, and forgets the hold either way.

A thread this phone started is unknown to the Mac, so the phone creates its task itself (off, held, with the phone's "while closed" interval) and the same rule runs. With the phone's check off, the task stays off and stays held across the background.

When the phone takes over it starts a fresh baseline, so nothing from before is news, and it runs calls only for turns it watched end, as the Mac does. A turn's calls run 2 seconds after it ends, and only if the phone still relays and the thread has no `Result:` message newer than the turn's last reply (call-level dedupe).

### Failure cases

| Case | What happens | How it resolves |
|---|---|---|
| Mac open, phone opens | The task is off with the Mac's `updatedAt`. The phone watches. | Quit the Mac: its upsert turns the task on, the phone's subscription sees it and takes over within a round trip. |
| Phone relaying, Mac opens | The Mac's launch upsert writes a new `updatedAt`. The phone stops on that push, sends the batched events from before the Mac's write (the Mac's first state already holds them, so it won't), drops later ones (they are the Mac's), and doesn't turn the task on when it leaves. | Between the Mac's first shell state and its upsert, about one round trip, both can see the same event or turn end. An event in that window can go out twice. A call block whose turn ends in that window can run on both: the phone waits 2 s and checks for the Mac's `Result:`, which covers a Mac that answers first; a Mac slower than that can't be stopped without a Mac change. |
| Both open on a sleeping Mac | The Mac turned the task off at launch and doesn't relay while asleep. The phone watches; nobody relays and the "while closed" check doesn't run. | Same as with the Mac alone today. Quitting CandyWrapper on the Mac hands over. |
| Phone killed in front, or crashes | The task stays off with the phone's `updatedAt`, so the "while closed" check doesn't run. | The phone's next start finds its hold intact and relays; leaving the front turns the task on. If the Mac opens first, its upsert replaces the hold and its quit turns the task on. |
| Network lost on the phone while relaying | The socket drops, so nothing relays and the hold stays. A reconnect starts over: the task list is unknown until the new subscription answers, so a stale list can't claim. | Back online, the hold still matches and relaying resumes with a fresh baseline. Backgrounded while offline, the release can't reach the server: the task stays off until the phone returns or the Mac opens and quits. |
| Mac killed, never quit | The task stays off with the Mac's write. The phone watches, and nobody relays. | Open and quit CandyWrapper on the Mac. A safe takeover without the Mac would need a liveness signal the Mac doesn't write. |
| Two phones | The second sees an off task with another `updatedAt` and watches. | It takes over once the first leaves the front and turns the task on. |
| Someone edits the task in T3 | Any write changes `updatedAt`: the phone stops and forgets its hold. Turning it on hands it to whichever app looks next. | |

### What a Mac change would add

The protocol needs no Mac change. Three small ones would close the gaps above: the Mac checking the task's `updatedAt` before relaying, as the phone does (closes the takeover window for calls and a killed phone); the Mac re-syncing its task after sleep (hands off while asleep); and the Mac adopting a thread by the brief marker, as the phone does (no second thread after "Start on <machine>").

## Limits enforced in code

As on the Mac, with two additions so either device's marks hold whoever relays:

- Hands off: the phone's own list, plus the list in the thread's latest brief, whichever device wrote it (`OrchestratorBrief.handsOffIn`).
- Ask first: inside the window (10 minutes by default) `send` refuses until the call carries `"confirmed":true`. The phone's own relayed prompts don't open the window; the Mac's do, so the phone asks more often, never less.
- Worker limit: running children of the Orchestrator thread, plus workers the phone launched, plus every worker a `Result: launched …` line in the thread names, from either device.

## Background

In the background the phone relays nothing, polls nothing and sets no timers: the controller decides only on pushes and on the activity's start and stop, the task subscription closes, and the batch's one-shot timer is cancelled. The T3 scheduled task covers that time.

## Phone layout and differences from the Mac

| Mac | Phone | Why |
|---|---|---|
| The pane in the main area beside the sidebar | The pinned row opens the pane full screen | A phone shows one thing at a time |
| Overview and Needs you beside the chat | A segmented control under the header: Chat, Overview, Needs you with its count | Same reason |
| Double-click the row's name to rename | Long press the row | No double click on touch |
| Pages render in a web view above the reply | A page card that opens the signed page in the browser | The app has no web view (perf-budgets.md) |
| Bots spring toward the pointer | No pointer: the face changes with its state only | Touch has no hover |
| The typing bubble animates while the turn runs | The dot grid on the shared second ticker | The app's single-ticker rule |
| Needs you and Overview cover the Orchestrator's machine | The same | Phase 4 on the Mac covers every machine |
| Unsent images survive leaving the pane | The same, in memory only | As on the Mac |

## Bots

`BotGeometry` is the Mac's math: 14 polar outlines inflated into 14 slices, turned by yaw and pitch, eyes lifted onto the face dome, and the gaze clamped to 94% of each shape's worked-out turn limit in 32 directions. The 315-pose sweep runs for every shape in `BotGeometryTest`. `Bot()` draws cached polygons on a Canvas; poses are worked out once per identity, gaze and size on `Dispatchers.Default`. A mood change plays one flourish under a second (a look to each side when work starts, a hop when it needs you), then stops; with animations off the face changes without moving. Idle, nothing draws again.
