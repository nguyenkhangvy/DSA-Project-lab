# School-Life-Assistant: a window on the laptop to enter and change your accounts

**Date:** 2026-10-01
**Scope:** a School-Life-Assistant window on the laptop replaces the terminal for setting up the agent and for changing the website connection, the EduSoft and Blackboard logins and the Outlook account; the website gets an Accounts page that opens it; messages that tell students to run terminal commands point to Accounts instead
**Owner:** Nguyen Khang Vy
**Status:** Built (see docs/superpowers/plans/2026-10-01-accounts-window.md)
**Builds on:** [EduSoft first, phase 1](2026-09-25-edusoft-first-phase1-design.md), [Blackboard](2026-09-26-blackboard-design.md), [Outlook mailbox](2026-09-28-outlook-mailbox-design.md), [Live sync](2026-10-01-live-sync-design.md). Everything there stays the same unless this document says otherwise.

---

## 1. Goal

Entering the student ID and password is too hard for people who don't know code: today they open PowerShell, run `sla-agent setup` and answer questions in the terminal, and changing a login later means remembering `sla-agent setup --blackboard` or `--outlook`. The student wants people to enter their accounts in the app, once as before, and to change them later in an Accounts page.

### Decided with the student (2026-10-01)

- **A window on the laptop, not the website** (question 1: B). Passwords never leave the laptop: the rule from phase 1 ("the password appears in Windows Credential Manager and nowhere in MySQL") stays. The website's Accounts page shows what is set up and opens the window.
- **The window covers everything `sla-agent setup` asks** (question 2: A): the website address and device key, EduSoft, Blackboard (optional) and Outlook (optional), first time and later changes. Nobody needs PowerShell after installing.
- **The device key is pasted** (question 3: A): copied from School → Devices as now, pasted into the window. No new pairing step.
- **A plain Tkinter window** (approach 1). Tkinter ships with Python on Windows (Tk 8.6 in this project's Python 3.12), so nothing new is installed. Chosen over a local web page served by the agent (a server on the laptop to protect and manage, only to look nicer) and over a packaged `.exe` (belongs to making installation easy, not here).

### Not in scope

- Installing the agent itself (Python, the venv, `pip install`): still the README's terminal steps
- Removing Blackboard or Outlook, "forget everything", Sync now, import and fetch: still terminal commands (the website already has Sync now)
- Entering or storing any password on the website
- Showing the student ID or Blackboard username on the website (the laptop doesn't upload them)
- Several laptops' accounts in one window (each laptop's window shows its own)

---

## 2. How it works

```
 Website: School → Accounts ──"Open Accounts on this laptop"──▶ sla-agent:accounts link
                                                                     │ (Windows, this user only)
 Desktop / Start menu shortcut ─────────────────────────────────────┤
 School-Life-Assistant.cmd (project folder, first time) ────────────┤
 sla-agent window (terminal) ───────────────────────────────────────┘
                                                                     ▼
                                                  pythonw -m sla_agent window  (one at a time)
                                                                     │
                              window.py (Tkinter screens) ──calls──▶ accounts.py (check, save, turn on sync)
                                                                     │
              site key check · EduSoft login · Blackboard login · Outlook accounts · Credential Manager · state.json
                                                                     ▲
                                              sla-agent setup (terminal) ──calls──┘
```

---

## 3. The window

### 3.1 First time (nothing set up)

Shown when `state.json` has no website address or no student ID. Title "School-Life-Assistant", heading "Set up this laptop". One form, top to bottom:

1. **Website:** address (filled with the last address, else `http://localhost:5000`) and device key (hidden like a password; pasted). A "Get a key" link opens `<address>/school/devices` in the default browser.
2. **EduSoft:** student ID and password.
3. **Blackboard (optional):** username and password. Left empty: skipped.
4. **Outlook (optional):** a dropdown with "Don't read Outlook" first and then the accounts found in classic Outlook; when Outlook isn't set up or can't be read, the line says so ("Classic Outlook isn't set up on this laptop") with a **Refresh** button. Looking for accounts runs in the background when the window opens.

**Check and save** runs, in this order, showing a line per step with ✓ or the message:

1. the device key with the website (`check()`);
2. the EduSoft login, one attempt;
3. only if 1 and 2 passed: save the device key, the EduSoft password and the state ("Saved. Your password is in Windows Credential Manager, not in any file.");
4. Blackboard, when filled in: one login attempt, saved if it passed; a failure leaves EduSoft saved and shows the Blackboard message;
5. Outlook, when an account is chosen: saved;
6. turn on sync (§4.3): the scheduled task, the `sla-mail:` and `sla-agent:` link types, the shortcuts.

If step 1 or 2 fails, nothing is saved and the form stays filled in (passwords included) to correct and try again. The messages are the ones `sla-agent setup` prints today, e.g. "EduSoft rejected the student ID or password. Nothing was saved." After a successful save the window switches to Accounts (§3.2), with a line "Done. This laptop checks in every minute; everything syncs every 30 minutes."

### 3.2 Accounts (set up)

Heading "Accounts". One row per account, each with a **Change** button (Blackboard and Outlook say **Set up** while not set up):

| Row | Shows |
|---|---|
| Website | the address and the last sync's result from the state ("success at 09:22"; no network call when the window opens) |
| EduSoft | the student ID; "on" or "paused: wrong student ID or password" / the other pause messages |
| Blackboard | the username, "on" or "paused: …"; or "not set up" |
| Outlook | the account; or "not set up" |
| Automatic sync | "on: every minute"; or "off" / "points to a Python that no longer exists" with a **Repair** button |

**Change** opens that account's fields under its row (the others are disabled meanwhile) with **Check and save** and **Cancel**:

- Website: address and device key; checked with `check()`, then saved (the old address's key is removed from Credential Manager when the address changed).
- EduSoft: student ID (filled) and password; one login attempt; saved if it passed; a changed ID forgets the old ID's password; a saved login clears an EduSoft pause.
- Blackboard: username (filled) and password; as `setup --blackboard` does now, including clearing its pause.
- Outlook: the dropdown of §3.1 (without "Don't read Outlook": removing is out of scope); saving also registers `sla-mail:`.
- Automatic sync, **Repair**: what `sla-agent schedule` does.

A failed check changes nothing: the old login stays and keeps syncing. The row shows the message under the fields.

### 3.3 Behaviour

- Passwords and the device key are typed into hidden fields (`show="•"`), passed to `log.protect()` as soon as they are read, saved only in Windows Credential Manager, and cleared from the fields after a save.
- Every check runs on a background thread; the screen shows "Checking…" and disables the buttons; results come back through Tk's event loop (`after`), since Tk may only be touched from its own thread.
- An unexpected error in a check: "Something went wrong ({error type}). Nothing was saved." on screen; the details go to agent.log (scrubbed).
- The window reads the state when it opens and after each save; it never syncs by itself.
- Closing the window while a check runs: the check finishes in the background and saves as it would have (the thread is not a daemon), then the process exits.

---

## 4. The agent

### 4.1 `accounts.py`: the logic, no screen

Functions that check, save and return a `Result(ok: bool, message: str)`; they never print, ask or touch Tk. Their messages are today's `setup` messages.

- `connect_site(state, address, key, make_server, save=True)`: `check_server_url`, then `check()`; saves the key and `state.server_url` only when `save` is true (first setup passes `save=False` and saves them together with EduSoft).
- `save_edusoft(state, student_id, password, make_edusoft)`
- `save_blackboard(state, username, password, make_blackboard)`
- `outlook_accounts()` and `choose_outlook(state, address)` (also registers `sla-mail:`)
- `first_setup(state, form, ...)`: the order and the "nothing saved unless the site and EduSoft pass" rule of §3.1; returns a result per step
- `turn_on_sync()`: the scheduled task (as now), both link types, the shortcuts; returns one result per part, and a failed part never undoes a save
- `sync_task_state()`: "on", "off" or "points nowhere", from `scheduler.task_program()`

The makers (`make_server`, `make_edusoft`, `make_blackboard`) and the Windows parts are passed in, so tests use the existing fakes.

### 4.2 `sla-agent setup` keeps working

`cmd_setup`, `--blackboard` and `--outlook` keep their questions and output, and call `accounts.py` instead of doing the work themselves. Their existing tests stay unchanged and must pass. `cmd_setup`'s final line becomes "Automatic sync is on: this laptop checks in every minute while you're logged in." (it still says 15 minutes).

### 4.3 Windows parts

- **`sla-agent:` link type**: `HKEY_CURRENT_USER\Software\Classes\sla-agent`, like `sla-mail:` (`mail_link.py` grows a second, shared registration helper). Command: `"<pythonw>" -m sla_agent window "%1"`. Only `sla-agent:accounts` is known; anything else opens the window too (the argument is ignored apart from being logged at debug level).
- **Shortcuts**: "School-Life-Assistant.lnk" on the Desktop and in the user's Start menu Programs folder, made with `WScript.Shell` through pywin32; target `pythonw.exe`, arguments `-m sla_agent window`, start in the agent's folder. Made or replaced on every `turn_on_sync()` and on Repair, so a moved project folder is fixed with the task.
- **`School-Life-Assistant.cmd`** in the project root: `start "" "%~dp0.venv\Scripts\pythonw.exe" -m sla_agent window`, so a double-click in File Explorer opens the window without leaving a console.
- **One window at a time**: a named Windows mutex (`SchoolLifeAssistant-Window`). When it already exists, the new process finds the window by its title (`win32gui.FindWindow`), restores it, brings it to the front and exits.
- **`sla-agent forget`** also removes the `sla-agent:` link type and both shortcuts.
- **`sla-agent window`** is the new command (also what the link, the shortcuts and the `.cmd` run). When Tkinter is missing: "This Python has no Tkinter. Use `sla-agent setup` in the terminal instead." and exit 1.

### 4.4 Messages that point to Accounts

The agent's pause messages (`PAUSE_MESSAGES`) say "Change it in Accounts (open School-Life-Assistant on this laptop)." instead of "run `sla-agent setup` …" / "run `sla-agent setup --blackboard` …". `sla-agent status` says the same.

---

## 5. The website

### 5.1 The Accounts page

- **`GET /school/accounts`**, a new School tab "Accounts" after Devices.
- One line per system, built from the latest runs as the status box is (`SyncStatus`): EduSoft, Blackboard, IUPay, Outlook, each "synced at …", "paused: …", "not set up" or "never synced".
- The laptops from Devices with their last check-in (read only; adding and cancelling stay on Devices).
- A button-styled link **"Open Accounts on this laptop"** to `sla-agent:accounts`, with: "Nothing opened? Open School-Life-Assistant from the Start menu on the laptop where you set it up. Passwords are only ever entered there, never on this website."
- No laptop yet: the button is replaced by "Add your laptop on the Devices page, install sla-agent, then double-click School-Life-Assistant.cmd in the project folder."
- The page has no password field and no form.

### 5.2 Messages that point to Accounts

Every message that tells the student to run `sla-agent setup` (with or without `--blackboard` / `--outlook`) says "Change it in Accounts" with a link to `/school/accounts` (status box and Mailbox notice through `SyncStatus`; `courses.html`; `mailbox.html`'s "Outlook isn't connected yet"; `devices.html`'s new-key card: "Open School-Life-Assistant on the laptop and paste it when asked."). Messages about `sla-agent import`, `sla-agent status` and "needs an update" stay.

---

## 6. Security and privacy

- Passwords and the device key are typed only into the laptop window, kept only in Windows Credential Manager, and never sent to the website (the device key is sent as today, as the upload's credential).
- The website's Accounts page shows no student ID, username or password and accepts none.
- The `sla-agent:` link only opens the window; it carries no data and changes nothing by itself. A web page that follows it can only open the window, where the student decides.
- The log-scrubbing test (no password in any log line) covers the window's path.

---

## 7. Testing

**Agent (pytest):**

- `accounts.py` with the existing fakes (site, EduSoft, Blackboard) and fakes for the Outlook account list, the task, the link types and the shortcuts: first setup saves everything; device key rejected → nothing saved; EduSoft rejected → nothing saved; Blackboard rejected → EduSoft saved, Blackboard message; changed student ID forgets the old password; a wrong new password keeps the old one; Outlook chosen; a failed task, link or shortcut keeps what was saved; `sync_task_state` on / off / points nowhere.
- The existing `sla-agent setup` tests pass unchanged (apart from the "every minute" line).
- The window: tests build the real Tk window hidden (`withdraw`), fill fields, press buttons, with a switch that runs checks at once instead of on a thread; they check the ✓ and error lines, the switch to Accounts, the cleared password fields, disabled buttons while checking, and the Change / Cancel rows. Skipped when Tk can't start.
- The mutex and window lookup, the registry and the shortcuts with fakes; `forget` removes the new link type and the shortcuts.
- No password in any log line during a window first setup and a window Blackboard change.

**Website (MockMvc):** the Accounts tab and page; each system's state; laptops listed; the `sla-agent:accounts` link; no `<input type="password">` and no form on the page; no-laptop text; the new "Change it in Accounts" messages in the status box, Mailbox, Courses and Devices; the page needs login.

**By hand, in the main session, on the student's laptop (already set up, so the Accounts view):** double-click `School-Life-Assistant.cmd`; change Blackboard with a wrong password and see the old login keep working; change it back; open the window from the website's button (Edge asks once); a second launch brings the open window forward; the shortcuts exist and open it.

---

## 8. Build order

1. Agent: `accounts.py`, and `sla-agent setup` calling it (existing tests green).
2. Agent: the Windows parts (link type, shortcuts, mutex, `forget`, `.cmd`) and `sla-agent window` with the Tkinter screens.
3. Agent: pause messages and `status` point to Accounts.
4. Website: the Accounts page and the "Change it in Accounts" messages.
5. README: installing ends with double-clicking `School-Life-Assistant.cmd`; the command table stays for terminal users. The student's check by hand.

---

## 9. Risks

- **Antivirus or company policy blocks shortcuts or registry writes:** the window says which part failed and keeps the saves; the `.cmd` file still opens it.
- **Edge doesn't offer to open `sla-agent:`** (link type not registered yet, or blocked): the page's text says to use the Start menu.
- **A frozen Outlook while listing accounts:** the list runs in the background with the existing time limit and shows "Classic Outlook isn't set up on this laptop" with Refresh; it never blocks the window.
- **Tk looks plain on high-DPI screens:** the window asks Windows for DPI awareness (`SetProcessDpiAwareness`) before creating Tk, so it isn't blurry.
