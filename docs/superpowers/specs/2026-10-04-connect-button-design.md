# School-Life-Assistant: Connect this laptop, without copying a device key

**Date:** 2026-10-04
**Scope:** setup step 1 and Accounts → Website get a **Connect** button: the browser opens the website, the student logs in (or creates an account) and presses **Connect** there, and the app receives its device key by itself; registering returns to the page that asked for login; pasting a key stays as a backup
**Owner:** Nguyen Khang Vy
**Status:** Built (see docs/superpowers/plans/2026-10-04-connect-button.md)
**Builds on:** [Easy install](2026-10-02-easy-install-design.md), [Accounts window](2026-10-01-accounts-window-design.md), [Java website](2026-09-26-java-website-design.md). Everything there stays the same unless this document says otherwise.

---

## 1. Goal

Today a student connects a laptop in six steps (setup step 1, from the easy install): open the website, log in or create an account, go to **School → Devices**, type a name, press **Add device**, press **Copy**, and come back to the window. Accounts → Website → **Change** asks for a key the same way. The key is long and shown only once, and the Devices page is one more place to find.

With **Connect**, the student presses one button in the app, logs in (or creates an account) in the browser, and presses **Connect** on the website. The app gets its key by itself.

### Decided with the student (2026-10-04)

- **The browser comes back to the app** (approach A). The app listens on `127.0.0.1` while the student is in the browser; the website sends the browser back there with a one-time code, and the app trades the code for the key. Chosen over:
  - B, the app showing a short code and asking the website every few seconds: a few seconds' wait, and a student tricked into pressing **Connect** on someone else's code gives that person a key to their account;
  - C, the app asking for the website password: the app would handle that password, the website would need protection against password guessing, and it would break with Google sign-in or two-step login.
- **The key stays.** The app syncs every minute with nobody at the laptop, so it keeps a key: one per laptop, cancellable on its own, good only for uploading sync data. Only the copying goes away.
- **Both places use Connect**: setup step 1 and Accounts → Website → **Change**. **Paste a key instead** stays as a backup (a developer's own site, a browser that can't come back).
- **The laptop is named after the computer** (Windows' `COMPUTERNAME`, for example `LAPTOP-KHANG`). Renaming stays on the Devices page.
- **Registering returns to the Connect page**, so a classmate without an account finishes in one go.
- **Release 0.4.0, website first.**

### Why this is safe now

The easy install left "pairing" out because "a link that hands the app a key would let any web page connect a laptop to someone else's account". Here no link carries a key, and a web page can't turn the flow against a student:

- The browser carries only a one-time code, good for one trade-in and two minutes. The trade-in also needs a secret (the *verifier*) that never leaves the app.
- The app takes only a code that comes back with its own random *state*, so a page can't hand it someone else's code (which would connect the laptop to that person's account).
- A laptop is added only after the student, logged in, presses **Connect** on a page that names the account. The page can't be shown inside another site (`X-Frame-Options: DENY`, Spring Security's default).
- The website sends codes only to `127.0.0.1` at a port number, never to an address taken from the link.

### Next, as its own plan

- Cancelling the old entry on Devices when a laptop connects again (today it stays until cancelled by hand).
- Connecting from a phone or another device.
- Renaming the laptop on the Connect page.

### Not in scope

- Google sign-in, two-step login.
- Keeping codes in the database (one server runs the site; section 4.2).

---

## 2. What the student sees

### Setup step 1 · Connect to the website

- One line: "Press **Connect**. Your browser opens: log in (or create an account), then press **Connect** there."
- A **Connect** button, then "Website: <address> · **Change**" as today.
- While the app waits: "Finish in your browser…". Pressing **Connect** again starts over in a new tab; the first tab no longer counts.
- When the key comes back: "✓ Connected to <email>.", the window comes to the front (Windows may only flash its taskbar button), and **Next** turns on. Nothing is saved yet, as today: step 2 saves the key with EduSoft.
- **Paste a key instead** (a small link) shows today's key box with **Paste** and its checks (easy install, section 3).

### In the browser

1. Not logged in: the login page. **Create one** → register → back to the Connect page (section 4.3).
2. The Connect page: "Connect this laptop? **LAPTOP-KHANG** will upload your timetable, exams and tuition to the account **<email>**.", with **Connect** and **Cancel**.
3. After either button, the tab shows a small page from the app: "✓ Done. You can close this tab and go back to School-Life-Assistant." or "Cancelled. You can close this tab."

### Accounts → Website → Change

The address box (for a moved site or `http://localhost:5000`), **Connect**, and **Paste a key instead** (today's key box and **Save**). A key that comes back is saved at once (`change_site`), with today's "Saved. This laptop now syncs with <address>."

### When something goes wrong

Each case shows one sentence on the answer line, saves nothing, and leaves **Connect** ready:

| What happened | The app says |
|---|---|
| **Cancel** on the website | "You pressed Cancel on the website. Nothing was saved." |
| Nothing back within 5 minutes (tab closed, gave up) | "Nothing came back from the website. Press Connect to try again." |
| The trade-in is refused (code expired or used, or the server restarted) | "The website didn't accept the connection. Press Connect to try again. Nothing was saved." |
| The website can't be reached, or answers with an error page | today's `check_site` messages |
| The app can't listen on `127.0.0.1` | "Connect isn't working on this laptop. Use Paste a key instead." |

A website too old for Connect shows "not found" in the browser; the student uses **Paste a key instead**. The website is always updated before the app (section 7).

### The Devices page

**Add a device** and the one-time key stay, for the paste path. "Install on your laptop" adds: "Open it and press **Connect**: there's no key to copy."

---

## 3. How it works

1. **Connect pressed (app).** The app makes a random *state* and *verifier* (32 random bytes each, URL-safe Base64 without padding) and the *challenge*, the same encoding of SHA-256(verifier). It listens on `127.0.0.1` at a port Windows picks, and opens `<address>/school/devices/connect?port=<port>&state=<state>&challenge=<challenge>&name=<computer name>` in the default browser.
2. **The Connect page (website).** Login is required; login and register return here. The link is checked: the port is a number from 1024 to 65535; state and challenge are 43 URL-safe Base64 characters; the name is trimmed and 1–100 characters long, otherwise "My laptop". A link that fails shows "This link didn't come from School-Life-Assistant. Press Connect in the app again." and nothing else.
3. **Connect pressed (website).** The form posts the same values with the usual CSRF token, and the website checks them again. It makes a one-time **code** (32 random bytes), keeps SHA-256(code) → account, name, challenge, and the time 2 minutes ahead, and redirects to `http://127.0.0.1:<port>/callback?code=<code>&state=<state>`. **Cancel** redirects to `http://127.0.0.1:<port>/callback?error=cancelled&state=<state>`. No laptop is added yet.
4. **The callback (app).** Only `GET /callback` with the app's state counts (compared in constant time). Anything else gets 404, and the app keeps waiting. With a code or `error=cancelled`, the app answers the tab with its small page and stops listening.
5. **The trade-in.** The app posts `{"code": …, "verifier": …}` to `<address>/api/school/sync/connect`. The website removes the code at once (one try per code), then checks that it existed, hasn't expired, and that the verifier matches the challenge. It adds the laptop as **Add device** does (`DeviceKeys.create`) and answers `{"key": "sla_…", "email": "<the account's email>"}` with `Cache-Control: no-store`. Anything wrong is HTTP 400 `{"error": "invalid_code"}`, the same for every case.
6. **The check (app).** The app checks the new key with today's `check_site` and shows "✓ Connected to <email>."

Because the laptop is added only at the trade-in, a code that never comes back (the app or the tab closed) leaves no extra entry on Devices.

---

## 4. Website

### 4.1 The Connect page

`ConnectController` (`school/pages`, next to `DevicesController`):

- `GET /school/devices/connect` shows `school/connect.html` in the site's layout: the laptop's name, the account's email, **Connect** and **Cancel**, and the link's values as hidden fields.
- `POST /school/devices/connect` with `action=connect` or `action=cancel` redirects to the app (section 3, step 3).

The redirect is built from the port alone: `http://127.0.0.1:` + port + `/callback`, with the code (or the error) and the state as query parameters. It names `127.0.0.1` rather than `localhost`, which can mean `::1` while the app listens on IPv4.

### 4.2 Codes and the trade-in

- `ConnectCodes` (`school/sync`): `issue(userId, name, challenge)` returns the raw code; `redeem(code, verifier)` returns the pending connection, or empty. Codes are kept in memory, by their SHA-256, for 2 minutes (by the `Clock` bean, so tests can move time); expired ones are dropped whenever a code is issued. One server holds them, which is true on Lightsail and on Render; a restart only means pressing **Connect** again.
- `POST /api/school/sync/connect` on `SyncApiController`, under the sync API's stateless filter chain (no session, no CSRF). `DeviceKeyInterceptor` skips this one path, since the app has no key yet; `AgentVersionInterceptor` still applies. Every other `/api/school/sync` address still needs a device key.
- Codes, verifiers and keys are never logged.

### 4.3 Register returns to the page that asked for login

`AuthController.register` redirects to the saved request (Spring Security's `RequestCache`) when there is one, as login already does, and to `/` otherwise. This helps every page that asks for login, not only Connect.

### 4.4 The data format

`contract/` gains the trade-in's request and answer (`ConnectRequest`, `ConnectResult`) with samples in `contract/samples/`, checked by the Python contract tests and by `SyncContractTest`, as for the other sync messages.

---

## 5. App

### 5.1 `connect.py` (new, no window code)

One `Connection` per press of **Connect**:

- On creation it makes the state, verifier and challenge, and listens on `127.0.0.1:0` (a small `http.server` on a worker thread).
- `url(address, name)` returns the Connect page's address.
- `wait(timeout)` returns the code; it raises `Cancelled` for `error=cancelled`, `TimedOut` after the timeout, and `Closed` when closed meanwhile.
- `close()` stops listening (pressing **Connect** again, leaving the page, closing the window).

`accounts.connect_laptop(address, tools)` puts it together: start a `Connection`, open the browser (`tools.open_browser`), wait up to 5 minutes, trade the code in (`server_client.connect`), check the key (`check_site`), and return a `Result` with the key and email, or the sentence from section 2. The state, verifier, code and key go through `log.protect` first.

### 5.2 Server client

`server_client.connect(address, code, verifier)` posts the trade-in without a device key and returns the key and email; HTTP 400 becomes `ConnectRefused`.

### 5.3 Setup step 1 and Accounts

- `SitePage` replaces the six instructions and **Open the website** with section 2's line and **Connect**; the key box and **Paste** move under **Paste a key instead**. A key that comes back sets `accepted` as an accepted pasted key does, so step 2 saves it unchanged.
- Accounts' Website editor gets **Connect** next to the address, and **Paste a key instead** for today's key box.
- A late answer for a page left meanwhile, or for an older press of **Connect**, is dropped (the pattern `SitePage.checked` uses).

---

## 6. Tests (written first)

- **Website:**
  - The Connect page asks for login and comes back after login and after registering; registering with no saved page still goes to `/`.
  - A link with a bad port, state or challenge shows the error page; an empty or too long name becomes "My laptop".
  - **Connect** redirects only to `http://127.0.0.1:<port>/callback` with a code and the state; **Cancel** with `error=cancelled`; neither adds a laptop.
  - The trade-in: the right code and verifier give a key that `/check` accepts, the laptop on Devices under its name, and the account's email. A wrong verifier, an expired code (the clock moved on) or a second try gives 400 and adds nothing. The trade-in needs no device key; `/check` still does.
  - The Connect page can't be framed (`X-Frame-Options`).
- **Contract:** the samples pass on both sides.
- **App:**
  - `connect.py` with a real listener on `127.0.0.1`: the right state gives the code; a wrong or missing state, or another path, gets 404 and the wait goes on; `error=cancelled` raises `Cancelled`; a short timeout raises `TimedOut`; `close()` raises `Closed`; afterwards nothing listens on the port.
  - `connect_laptop` with fakes: each row of section 2's table gives its sentence and saves nothing; success gives the key and email.
  - Setup step 1 and Accounts in a hidden Tk window with fakes: **Connect** turns **Next** on and step 2 saves that key; **Paste a key instead** works as today; an older press's answer is dropped.
  - No state, verifier, code or key appears in the log.
- **Where the tests run:** window tests need a desktop, so CI's Linux runner skips them, as today; run `pytest` on Windows before merging.
- **By hand, after 0.4.0 is released:**
  1. In a fresh Windows account, with the built .exe and the live site: **Connect** → **Create one** → back on the Connect page → **Connect** → finish setup.
  2. Accounts → Website → **Change** → **Connect**.
  3. Once in Edge and once in Chrome.

---

## 7. Build order

1. Contract: the trade-in's request, answer and samples.
2. Website: `ConnectCodes` and the trade-in; the Connect page; register returning to the saved page; the Devices card text.
3. App: `server_client.connect`, `connect.py`, `connect_laptop`.
4. Setup step 1 and Accounts' Website editor.
5. Texts: the README's install and developer steps, the download page in both languages, version 0.4.0.
6. Merge; run `update.sh` on the server and open a Connect link once; tag `v0.4.0`; the by-hand test.

---

## 8. Risks

- **Browsers tightening access to `127.0.0.1`.** Chrome's Local Network Access permission covers requests a page makes to local addresses; this flow is a top-level redirect, the way desktop sign-ins (GitHub's and Google Cloud's command-line tools) come back to their app. The by-hand test runs it in Edge and Chrome; if a browser ever blocks it, **Paste a key instead** stays.
- **Antivirus and a listening app.** The app listens only on `127.0.0.1`, which Windows' firewall doesn't ask about. If an antivirus blocks it, the student sees "Connect isn't working on this laptop" and pastes a key. The 0.3.0 .exe is already reported to Microsoft as a false positive; 0.4.0 may need the same.
- **Codes in memory** work only while one server runs the site. If it ever runs on several, the codes move to the database.
- **A shared laptop logged into someone else's website account.** The Connect page names the account, so the student can press **Cancel**.
