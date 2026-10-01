# School-Life-Assistant: new emails within a minute, full syncs every 30 minutes, pages that update themselves

**Date:** 2026-10-01
**Scope:** the laptop agent checks every minute; a new email in Outlook reaches Mailbox within about a minute through a mail-only sync; EduSoft, IUPay, Blackboard and Outlook sync fully every 30 minutes instead of every 12 hours; Mailbox and Overview refresh their content on their own; `sla-agent schedule` fixes the scheduled task after the project folder moved
**Owner:** Nguyen Khang Vy
**Status:** Built (see docs/superpowers/plans/2026-10-01-live-sync.md)
**Builds on:** [Java website](2026-09-26-java-website-design.md), [Outlook mailbox](2026-09-28-outlook-mailbox-design.md), [IUPay tuition](2026-09-30-iupay-tuition-design.md). Everything there stays the same unless this document says otherwise.

---

## 1. Goal

The student wants the app "realtime". Today the agent checks in every 15 minutes and syncs only every 12 hours or on Sync now, so a new email can take hours to appear. (Since the project folder moved from `C:\IU SCHOOL` to `C:\IU_SCHOOL` on 2026-09-28, the scheduled task has not run at all: it still starts the old folder's Python.)

### Decided with the student (2026-10-01)

- **New emails within about a minute; everything else every 30 minutes** (question 1: A). EduSoft and Blackboard can't notify anyone, and logging in every minute risks a lock or being flagged by IU, so only mail is checked every minute.
- **Pages update themselves** (question 2: A): Mailbox and Overview replace their lists in place when a newer sync arrives; no F5.
- **A check every minute** (approach 1): the scheduled task runs the agent every minute; nothing stays running. Chosen over a watcher that stays running (seconds instead of a minute, but more to crash or hang) and over Outlook "run a script" rules (blocked by default).

### Not in scope

- Instant sync of EduSoft, Blackboard or IUPay (they have no way to notify)
- Syncing while the laptop is off or asleep
- Live refresh of the Timetable calendar, Courses, Exams or Tuition pages
- Notifications (sound, pop-up, phone)
- Cleaning up old run rows

---

## 2. How it works

```
Windows task, every minute and at logon (a second copy never starts while one runs)
  sla-agent run
    ├─ check in with the site ── full sync due (30 min, or Sync now)? ── yes ─▶ full sync (trigger scheduled/manual)
    │                                                                          EduSoft · IUPay · Blackboard · Outlook
    └─ no ─▶ Outlook set up and already open?
               └─ yes ─▶ newest Inbox time > newest time uploaded? ── yes ─▶ mail-only sync (trigger mail)
                                                                             reads the Inbox, uploads only "outlook"
Website
  Scheduling ── counts only full runs for "due" (every 30 minutes)
  Status box ── headline from the latest full run; the Outlook line from the newest run with Outlook (full or mail)
  GET /school/api/version ── grows when a sync starts or ends ◀── live.js on Mailbox and Overview, every 30 s (5 s while busy)
                                                      └─ newer? ─▶ fetch the page, replace the marked areas
```

---

## 3. The agent

### 3.1 The scheduled task

- `scheduler.py` builds the task with a repetition interval of **1 minute** (`PT1M`, was `PT15M`), still at logon too, still `MultipleInstancesPolicy IgnoreNew` (a run never overlaps another) and `ExecutionTimeLimit PT10M`. The description becomes "School-Life-Assistant: new mail every minute; EduSoft, IUPay, Blackboard and Outlook every 30 minutes."
- New command **`sla-agent schedule`**: installs (replaces) the task with the Python the command runs under (`sys.executable`'s `pythonw.exe`), without asking for passwords again. It says where the task points: "The sync task now runs C:\IU_SCHOOL\...\pythonw.exe every minute."
- `sla-agent setup` still installs the task as before (now with the 1-minute interval).
- `sla-agent status` adds a line when the task's program no longer exists: "The sync task points to a Python that no longer exists (the project folder moved?). Run `sla-agent schedule`." (read with `schtasks /query /xml`; any failure to read it leaves the line out).

### 3.2 Each run

`cmd_run` becomes:
1. `server.check()` (as today; also tells the site the laptop is alive). On a server error: log and exit 1.
2. If `decision.due`: the full sync, exactly as today (trigger `manual` when requested, else `scheduled`). The run ends here.
3. Otherwise, the **quick mail check** (§3.3). When it finds newer mail: a **mail-only sync** (§3.4).
4. Otherwise: exit 0 without writing to the log.

`everything_paused` and the EduSoft/Blackboard pause rules are unchanged; the mail check runs even while EduSoft or Blackboard is paused.

### 3.3 The quick mail check

- Only when an Outlook account is set up (`state.outlook_account`).
- Only with an **Outlook that is already open**: `GetActiveObject("Outlook.Application")`; if that fails, no check (the full sync still opens Outlook with `Dispatch`, as today).
- Reads the newest item of that account's Inbox: `Items.Sort("[ReceivedTime]", True)`, first item's `ReceivedTime` (made timezone-aware as the reader already does). No text is read.
- Compares it with `state.mail_newest` (ISO time, new state field). Newer, or no `mail_newest` yet: newer mail.
- Gives up after **5 seconds** (a security prompt or a busy Outlook): no check this minute, nothing recorded, never clicks anything.
- Any Outlook error during the check: no check this minute, logged at debug level only.

### 3.4 The mail-only sync

- `server.start("mail")`, then the same `read_outlook(...)` a full sync uses (same context: courses and Blackboard names from the last full sync, kept in the state), then `server.finish(run_id, FinishRun(outlook=...))`.
- It never logs in to EduSoft, Blackboard or IUPay, and never reads their passwords.
- After the site answered, `state.mail_newest` moves forward (never back) to the newest of: the `received_at` times uploaded, and the newest time the minute's check saw. So a newest email the upload lacks (unreadable, or older than the semester) is synced once, not every minute; the next full sync reads the Inbox again. `state.last_result` is left alone (it describes full syncs).
- A full sync also moves `state.mail_newest` forward when its Outlook part was uploaded `ok`.
- If the site refuses the run because another is running (`RunInProgress`): exit 0; the next minute tries again.
- If Outlook refuses the read: the run is finished with the Outlook part failed (`outlook_blocked` / `outlook_not_set_up`), as in a full sync; `mail_newest` still moves to the time the check saw, so a lasting problem (a security prompt, a slow Outlook) isn't hit every minute: the next newer email or the next full sync tries again. (Final review, 2026-10-01: retrying every minute repeated the failed read, a run row and a page refresh every minute.)
- The log gets one line per mail-only sync: "Mail sync success: 3 new emails." (count of uploaded emails newer than the previous `mail_newest`).

### 3.5 The upload format

`Trigger` becomes `Literal["scheduled", "manual", "import", "mail"]` (Python) and `scheduled|manual|import|mail` (Java `StartRun`). A `mail` run carries only the `outlook` part; the format already allows a run with any single part.

---

## 4. The website

### 4.1 When a full sync is due

- `Scheduling` gets `FULL_SYNC_EVERY = Duration.ofMinutes(30)`, used instead of `intervalHours` in `decide(...)`. `MIN_SCHEDULED_GAP` (1 hour between retries of a failed scheduled sync), `MIN_MANUAL_GAP` (5 minutes) and `RUN_TIMEOUT` (15 minutes) stay.
- `SyncRuns.check(...)` passes `decide` the latest **full** runs only: last attempt, last success/partial, and running are read from runs whose trigger is not `mail`. (A running `mail` run doesn't block the decision: the full sync's own `start` refuses while any run is running, and the agent tries again a minute later.)
- `school_sync_settings.interval_hours` stays in the table and in the check answer (agents don't use it); nothing reads it for deciding.

### 4.2 The status box and Mailbox's notice

- `SchoolController.status(...)`: `latest` and `lastGood` come from full runs only (trigger not `mail`).
- System lines and Mailbox's Outlook problem are built from **the 10 newest full runs plus the newest `mail` run**, merged newest first. So the Outlook line reads "synced at 14:31" from a mail run, and EduSoft, IUPay and Blackboard never drop off the box however many mail runs there were.
- `SyncStatus.laptopWarning` warns after **1 hour** without a check-in (`LAPTOP_SILENT = Duration.ofHours(1)`, was `2 × interval hours`).
- New repository queries: `findTop10ByUserIdAndTriggerNotOrderByStartedAtDescIdDesc(userId, "mail")`, `findFirstByUserIdAndTriggerOrderByStartedAtDescIdDesc(userId, "mail")`, and the `latestRun` variants with `TriggerNot`.

### 4.3 Pages that update themselves

- **`GET /school/api/version`** (logged-in user only; session login like the pages, not the device key): `{"version": <2 × the id of the user's newest run of any trigger, + 1 once it has finished>}`, or `{"version": 0}` when none: it grows when a sync starts and again when it ends, so an open Overview shows "Syncing…" too. (Changed 2026-10-01 after the student pressed Sync now and saw nothing happen on the page; it was the newest finished run's id.)
- Mailbox and Overview carry `data-version` (that id when the page was built) on `<main>` and mark the areas to replace with `data-live="<name>"`:
  - Mailbox: `status` (the status box / Mailbox notice), `read` (the "Mail read from Outlook …" line), `mail` (the boxes: Class, Events, Needs action, Other, Done, Past, and the "gone" joined events);
  - Overview: `status`, `today`, `tomorrow`, `to-submit`, `announcements`, `next-exam`, `bills`, `notice` (the tuition notice area, present even when empty), `changes`.
- `static/js/live.js` (loaded by both pages):
  - every 30 seconds while `document.visibilityState === "visible"`, `fetch("/school/api/version")`; every 5 seconds while the Overview is busy (`<main data-busy>`: a sync requested or running), for at most 5 minutes after the page loaded; the next round is planned after the previous one (and any refresh) ended;
  - when the answer is greater than `data-version`: `fetch(location.pathname)`, parse with `DOMParser`, and for each `[data-live]` area replace its contents with the new page's area of the same name — except an area where the student is typing or choosing (the focused element is an `input`, `select`, `textarea` or `[contenteditable]`), which waits for the next round; a focused link or button doesn't count, because a click focuses it (final review, 2026-10-01: clicking an email froze Mailbox). `<details>` the student opened stay open (matched by the nearest element with an id around them and their place in it). Once every area was replaced, set `data-version`;
  - then shows "Updated 14:32" (Vietnam time from the browser clock + 7 h, like the calendar) in a small `role="status"` note for 5 seconds;
  - any failed request or unexpected answer: ignored, tried again 30 seconds later; never an error on the page;
  - Mailbox's own scripts (Web ↗ opened marking, auto-Done setting) keep working after a replacement: they listen on `document` (event delegation) instead of on the replaced elements.
- Scroll position is kept (only area contents change).

---

## 5. Security and privacy

- `/school/api/version` returns only a number, filtered by the logged-in user; no device key access.
- The quick mail check reads only the newest item's received time; no subject, sender or text. The mail-only sync uploads exactly what a full sync's Outlook part uploads.
- `live.js` inserts HTML the site itself rendered (already escaped by Thymeleaf), from the same origin.

---

## 6. Testing

**Agent**
- `newer_mail(state, open_outlook=…)`: newer / same / no `mail_newest` / Outlook not open (no `Dispatch`) / Outlook hangs past 5 s / Outlook error — with fakes.
- `cmd_run`: a due full sync wins (no mail check); not due + newer mail → one `mail` run with only `outlook`, no EduSoft/Blackboard/IUPay login, `mail_newest` updated; not due + nothing new → no server `start`, no log line; mail sync refused as `RunInProgress` → exit 0; Outlook refuses the read → failed Outlook part, `mail_newest` unchanged.
- A full sync with an `ok` Outlook part sets `mail_newest`.
- `scheduler`: the task XML repeats every `PT1M`, keeps `IgnoreNew`; `sla-agent schedule` installs it with the current `pythonw.exe`; `status` warns when the task's program is missing.
- Contract: trigger `mail` accepted; an unknown trigger refused.

**Website**
- `Scheduling`: due after 30 minutes; not due at 29.
- `SyncRuns.check`: a `mail` success after the last full sync doesn't make the full sync "not due"; a running `mail` run doesn't block the decision.
- Status: headline from the latest full run; Outlook line from a newer mail run; 20 mail runs after a full run still show the EduSoft/IUPay/Blackboard lines; laptop warning after 61 minutes, not at 59.
- `/school/api/version`: 2 × newest id + 1 once finished, any trigger; 0 without runs; login needed; another user's runs don't count. Overview carries `data-busy` only while requested or syncing; Sync now adds no message (the status box says it).
- Mailbox and Overview have `data-version` and every `data-live` area named in §4.3.
- Sync API: `start` with trigger `mail`.

**In a real browser** (headless Chrome, as for the Timetable's Show more): an area is replaced when the version grows; a focused field's area is left for the next round and the note waits too; after clicking a link the area is still replaced; an opened Done list stays open; the note appears; a failed version request changes nothing.

**By hand:** after `sla-agent schedule`, send an email to yourself; Mailbox shows it within about a minute without F5; the status box headline doesn't change.

---

## 7. Build order

1. Contract: trigger `mail`.
2. Website: `Scheduling` 30 minutes; `SyncRuns.check` ignores mail runs; repository queries.
3. Website: status box and Mailbox notice from full runs plus the newest mail run; laptop warning 1 hour.
4. Website: `/school/api/version`; `data-version` and `data-live` on Mailbox and Overview; `live.js`; Mailbox scripts by delegation.
5. Agent: the quick mail check and the mail-only sync in `cmd_run`; `mail_newest`.
6. Agent: the 1-minute task, `sla-agent schedule`, the status warning.
7. README; the student runs `sla-agent schedule` and tries it.

---

## 8. Risks

- **Outlook's security prompt** can appear for programmatic access when antivirus is off; the quick check gives up after 5 seconds and never clicks it, so mail waits for the next full sync.
- **A laptop asleep or off** syncs nothing; the next minute after it wakes, a full sync runs if 30 minutes passed.
- **Blackboard and EduSoft every 30 minutes** is 48 logins a day each while the laptop is on; still far fewer than a student opening them by hand, but if IU complains, `FULL_SYNC_EVERY` is one constant.
- **Run rows** grow by one per mail-only sync (a few dozen a day); a clean-up can come later.
