# School-Life-Assistant: a download page and a step-by-step setup, for students who don't know code

**Date:** 2026-10-02
**Scope:** a public download page (GitHub Pages, English and Vietnamese); the first-time form becomes step-by-step pages with a device-key guide, an Outlook (classic) check with install and sign-in guides, and a question about the Desktop icon at the end; updates stop bringing back a Desktop icon the student deleted or declined
**Owner:** Nguyen Khang Vy
**Status:** Built (see docs/superpowers/plans/2026-10-02-easy-install.md)
**Builds on:** [Accounts window](2026-10-01-accounts-window-design.md), [the agent as one .exe](2026-10-02-agent-exe-design.md), [Outlook mailbox](2026-09-28-outlook-mailbox-design.md). Everything there stays the same unless this document says otherwise.

---

## 1. Goal

People who don't know code should be able to install School-Life-Assistant and set it up without help. Today:

- the download button is only inside the website, after logging in;
- the first-time form shows everything on one long page;
- "Get a key" opens the Devices page with no steps;
- the Outlook line says "Classic Outlook isn't open" whether Outlook is missing or only closed;
- the Desktop icon is added without asking, and every update adds it again.

### Decided with the student (2026-10-02)

- **A public download page on GitHub Pages** (download question: A), with an **English / Tiếng Việt** switch (section 1 review). Free and online all the time, even while the website runs only on the student's laptop; no login. Chosen over a page on the website (works only once the site is hosted) and over sharing the bare link (no help with Windows' warnings).
- **Step-by-step pages** (approach question: A): one thing per page, in the order website key → EduSoft → Blackboard → Outlook → Desktop icon. Chosen over keeping the one-page form and adding guides to it (everything at once is what non-technical students find hard).
- **The Outlook page finds out whether classic Outlook is installed and guides**: install it with the IU account, sign in, or skip. IU licensed Office for its students, but Microsoft retired its free desktop-apps plan for students (Office 365 A1 Plus) during 2025, so whether an IU account still includes the desktop apps isn't known; the guide works either way.
- **The Desktop icon is asked for at the end.** The Start menu entries are always made.
- **The window stays in English**, like the rest of the app; only the download page has two languages.

### Not in scope

- Hosting the website (decided 2026-10-02: later). Until then the student tests against `http://localhost:5000` with **Change** (section 3); classmates can finish step 1 only once the site is online.
- Sending the device key from the website to the app by itself ("pairing"). The key is still copied and pasted, now with a guide, a **Paste** button, and a copied key filled in by itself. A link that hands the app a key would let any web page connect a laptop to someone else's account.
- Code signing (as before: "More info → Run anyway").
- Pinning to the taskbar: Windows doesn't let programs pin themselves.
- Vietnamese in the window, a Remove button for the Desktop icon (deleting it works), an uninstall page.

---

## 2. The download page

`https://nguyenkhangvy.github.io/School-Life-Assistant/`, from a new `pages/` folder: `index.html` (both languages), `style.css`, and `img/` for screenshots. Plain HTML and CSS; a few lines of JavaScript only for the language switch.

Top to bottom, the same in both languages:

1. One line about the app ("Your IU timetable, exams, tuition bills, Blackboard and mail on one calendar") and a **Download for Windows** button: `https://github.com/nguyenkhangvy/School-Life-Assistant/releases/latest/download/School-Life-Assistant.exe`, always the newest release.
2. **You'll need:** a Windows 10 or 11 laptop and your EduSoft student ID and password. Blackboard and Outlook (classic) are optional. The app shows how to connect it to your School-Life-Assistant account.
3. **Three steps:**
   1. If the browser warns about the download, keep it. In Edge: **…** → **Keep** → **Show more** → **Keep anyway**.
   2. Open the downloaded file. At "Windows protected your PC", press **More info**, then **Run anyway**. It needs no administrator rights.
   3. The School-Life-Assistant window opens and walks you through the rest.
4. **Questions:** Are my passwords safe? (They stay on your laptop, in Windows Credential Manager; the code is public on GitHub.) Do I need to update it? (No, it updates itself.) On a phone? (Open this page on your Windows laptop.)

- **Language:** "English | Tiếng Việt" at the top. The page starts in Vietnamese when the browser's language is Vietnamese, otherwise in English, and the browser remembers the choice. Without JavaScript the English text shows.
- **Screenshots:** none at first; the button names are in bold. The student takes three during the by-hand test (section 7): the browser's download warning, "Windows protected your PC" after **More info**, and the first setup page. Each goes in `pages/img/` and shows under its step in both languages.
- **Publishing:** `.github/workflows/pages.yml` publishes `pages/` with GitHub's own Pages actions on a push to `main` that changes `pages/`, or when started by hand. It runs only in `nguyenkhangvy/School-Life-Assistant` (`if: github.repository == 'nguyenkhangvy/School-Life-Assistant'`), since `main` is also pushed to the dsa and ooad repositories. Once, by hand: the repository's **Settings → Pages → Source: GitHub Actions**.
- **Links to it:** School → Devices' install card and the README's "Install on your laptop" (section 6).

---

## 3. The setup pages

Shown when the laptop isn't set up (`state.json` has no website address or no student ID), instead of today's one-page form. Accounts doesn't change, apart from sections 4 and 5. Each page has a header "Step N of 4 · <name>" and its buttons at the bottom right.

### Step 1 · Connect to the website

1. Press **Open the website**.
2. Log in, or create an account.
3. Go to **School → Devices**.
4. Type a name for this laptop (for example "My laptop") and press **Add device**.
5. Press **Copy** next to the new key.
6. Come back to this window: the key is filled in by itself (or press **Paste**).

- **Open the website** opens `<address>/school/devices` in the default browser. The website asks for a login first, then shows Devices (it already returns to the page that asked).
- **Device key**, hidden like a password, with **Paste**. Paste takes the clipboard's text if it is a device key (`sla_` and 43 letters, digits, `-` or `_`); otherwise it says "The copied text isn't a device key. Press Copy on the Devices page, then Paste." When the window comes back to the front, the box is empty and the clipboard holds a device key, it is filled in by itself.
- As soon as the box holds text in a device key's shape, the key is checked (`check_site`), and the line under it shows "✓ The website accepted this key." or today's messages. Any other text shows "This isn't a device key. Press Copy on the Devices page, then Paste." and isn't sent anywhere.
- The address shows as small text, "Website: <address> · **Change**". Change shows the address box, for testing on `http://localhost:5000` or after the site moves. A changed address checks the key again.
- **Next** works only while the key is accepted. Nothing is saved yet.

### Step 2 · EduSoft

- "The student ID and password you use on edusoftweb.hcmiu.edu.vn." Student ID, Password.
- **Next** checks the login once (`check_edusoft`).
  - Failed: the message under the fields, as today; nothing saved; the fields stay filled in.
  - Passed: the key and EduSoft are saved together (`save_site_and_edusoft`), then automatic sync is turned on (`turn_on_sync`: the scheduled task, the links and the Start menu entries, no Desktop icon). If sync couldn't be turned on, the All set page says so, with Repair in Accounts.
- **Back** (on this page only) returns to step 1 with the key still there.
- From here on the laptop is set up: closing the window keeps what was saved, and the next start opens Accounts.

### Step 3 · Blackboard (optional)

"The username and password you use on blackboard.hcmiu.edu.vn." **Next** checks and saves (`change_blackboard`); a failure shows the message and stays. **Skip** goes on.

### Step 4 · Outlook (optional)

Section 4.

### All set

- "✓ All set! This laptop checks in every minute, and everything syncs every 30 minutes."
- One line per part: EduSoft ✓; Blackboard and Outlook ✓, or "skipped: set it up later in Accounts"; any note, as today (a link or Start menu entry that couldn't be made, or sync not on, with what to do).
- "Put a School-Life-Assistant icon on your Desktop, to open it quickly?" **Yes, add the icon** / **No, thanks**.
  - Yes adds the Desktop icon (section 5); if that fails, a line says so and setup still finishes.
  - Either button then shows Accounts and opens School-Life-Assistant (the website as an app), as the form does today.
- Closing the window on this page adds no icon, like No.

### As today's form

- Checks run on a worker thread: "Checking…" and the buttons disabled; the answer comes back through Tk's event loop.
- Passwords and the key go to `log.protect()` as soon as they are read and are saved only in Credential Manager; their boxes are cleared once saved.
- An unexpected error shows "Something went wrong (…). Nothing was saved."; the details go to agent.log.

---

## 4. The Outlook page

When the page opens, it reads Windows' registry in the background (about a second; it never starts Outlook):

| What this laptop has | The page shows |
|---|---|
| No `Outlook.Application` (no classic Outlook; the new Outlook app doesn't register it) | **A**: how to install it |
| Classic Outlook, but no Outlook profile for this Windows user (no subkey in `HKCU\Software\Microsoft\Office\16.0\Outlook\Profiles`; 16.0 is every Office from 2016 on, Microsoft 365 included) | **B**: how to sign in |
| Classic Outlook with a profile | **C**: its accounts |

**A. Not installed.** "School-Life-Assistant reads your IU mail through Outlook (classic). The new Outlook app can't be used. This laptop doesn't have Outlook (classic) yet:"

1. Press **Install Office**: microsoft365.com opens. Sign in with your IU email (it ends with @student.hcmiu.edu.vn).
2. Press **Install apps**, then **Microsoft 365 apps**. Open the downloaded file and wait until it finishes (10–20 minutes). No Install apps button? Your IU account has only the web version of Office: press **Other way** for Microsoft's own Outlook (classic) download (it may say it isn't licensed), or skip Outlook.
3. Open **Outlook (classic)** from the Start menu and sign in with your IU email.
4. Come back and press **Check again**. This takes a while: you can press **Skip** now and set up Outlook later in School-Life-Assistant Accounts (Start menu).

Buttons: **Install Office** (`https://www.microsoft365.com/`), **Other way** (Microsoft's "Install or reinstall classic Outlook on a Windows PC" page), **Check again**, **Skip**. The button names on Microsoft's pages are checked when the page is built.

**B. Installed, nobody signed in.**

1. Press **Open Outlook** and sign in with your IU email (it ends with @student.hcmiu.edu.vn). If the new Outlook opens instead, turn off its **New Outlook** switch (top right) to get Outlook (classic).
2. Wait until it says "All folders are up to date", then press **Check again**.

Buttons: **Open Outlook** (starts `outlook.exe`, which Windows finds through App Paths), **Check again**, **Skip**.

**C. Signed in.** The page asks Outlook for its accounts. An open Outlook answers at once; otherwise a hidden one starts for a moment, as Refresh does today (with a profile, Outlook's first-run wizard doesn't appear). "Read the Inbox of:" lists the accounts, with the first one ending in `@student.hcmiu.edu.vn` chosen (otherwise the first). **Next** saves it (`choose_outlook`); **Skip** goes on. If Outlook has no account after all, or doesn't answer, the page shows B's guide with the reason.

- **Check again** looks again from the top, so the page moves from A to B to C as the student goes.
- Accounts → Outlook → **Set up** or **Change** uses the same page, with **Cancel** instead of Skip, in place of today's dropdown and Refresh.

---

## 5. The Desktop icon

- `shortcuts.make` makes or refreshes the two Start menu entries, and refreshes the Desktop icon only if it is there. It never adds a Desktop icon. As today, it runs when sync is turned on, after an update (`point_links_and_shortcuts_here`), on a laptop set up before the window existed (`add_window_links`), and on Repair.
- New `shortcuts.add_desktop` adds the Desktop icon, and `shortcuts.on_desktop` says whether it is there. The All set page's **Yes** and Accounts' **Add** use them, through `accounts.add_desktop_icon(tools)`.
- Accounts gets a row, **Desktop icon**: "on", or "off" with **Add**. There is no Remove: deleting the icon removes it, and nothing brings it back.
- A laptop set up with 0.2.x keeps its Desktop icon: it is there, so updates refresh it.
- Nothing new in `state.json`: whether the icon is on the Desktop is the answer.

---

## 6. Other changes

- **School → Devices:** the install card gets a **Step-by-step guide** link (the download page) next to the download button. The new-key card says: "Copy it now. It won't be shown again. Press **Copy**, then go back to the School-Life-Assistant window on your laptop: the key is filled in by itself (or press **Paste**)."
- **README**, "Install on your laptop": the download page's link and the four steps.
- **Code layout:** `window.py` keeps the App, Accounts and its editor; the setup pages go in a new `setup_steps.py`, and what both use (fields, answer lines, running a check on a worker thread) moves to `window_parts.py`, so neither file grows past about 500 lines.
- **Outlook detection** in `outlook_reader.py`: `classic_outlook()` returns `"missing"`, `"not_signed_in"` or `"signed_in"` from the registry, and `start_classic_outlook()` starts it. They reach the window through `accounts.Tools` (new `outlook_state`, `start_outlook`, `add_desktop_shortcut`, `has_desktop_shortcut`).
- `accounts.first_setup` and `SetupForm` go: the setup pages call the existing checks and saves themselves. `sla-agent setup` in the terminal doesn't change, except that it no longer adds a Desktop icon.
- **Version 0.3.0.** `ONLINE_ADDRESS` doesn't change here; it changes when the website is hosted.

---

## 7. Tests (written first)

- **Setup pages**, in a real hidden Tk window with fakes, as `test_window.py` does:
  - The pages come in order. **Next** stays off until the key is accepted.
  - A rejected key and a failed EduSoft login show their messages and save nothing.
  - Step 2 saves both and turns on sync with the Start menu entries and no Desktop icon. **Back** from step 2 keeps the key.
  - **Skip** works on Blackboard and Outlook, and All set lists what is on and what was skipped.
  - **Yes** adds the Desktop icon and opens the app; **No** and closing don't add it.
  - **Change** shows the address box, and a new address checks the key again.
- **Paste and the filling-in by itself:** text in a device key's exact shape is taken and checked; anything else isn't, and Paste says why.
- **Outlook page:**
  - Fake registry answers give A, B and C, and **Check again** moves between them.
  - C chooses the student account first; C with no accounts shows B's guide.
  - A's and B's buttons open the right pages or start Outlook (fakes).
  - Accounts' Outlook editor uses the same page.
- **`classic_outlook()`** with a fake registry: installed or not, with a profile or not.
- **Shortcuts** in the fake shell (`test_shortcuts.py`):
  - `make` never adds the Desktop icon, and refreshes it when it is there; `add_desktop` adds it.
  - After an update, a deleted Desktop icon stays deleted.
  - Accounts' **Add** adds it.
- **Download page**, `pages/tests/test_pages.py` (added to pytest's `testpaths`): both languages have the newest-download link, the same three steps and the same questions, and every image the page shows exists.
- **Website:** the Devices page links to the download page.
- **Where the tests run:** window tests need a desktop, so CI's Linux runner skips them, as today; run `pytest` on Windows before opening the pull request.
- **By hand, after 0.3.0 is released:**
  1. Open the download page in both languages and download with Edge.
  2. Go through Edge's warning and "Windows protected your PC", taking the screenshots.
  3. Set up against `http://localhost:5000` with **Change**.
  4. Answer **No** to the icon (none appears), then **Add** it from Accounts.
  5. If a Windows user or laptop without classic Outlook is at hand, see page A.

## 8. Build order

1. Shortcuts: `make` without the Desktop icon, `add_desktop`, Accounts' Desktop icon row.
2. Outlook detection (`classic_outlook`, `start_classic_outlook`) and the Outlook page, in Accounts too.
3. The setup pages (`setup_steps.py`, `window_parts.py`), replacing the one-page form.
4. The download page, its workflow, and the links from Devices and the README.
5. Release 0.3.0, the by-hand test, and the screenshots added to the page.

## 9. Risks

- **The website isn't online yet.** Classmates can't finish step 1 until it is hosted. The download page never names the website's address, so it needs no change when the site moves.
- **IU accounts may have web-only Office.** Then Outlook (classic) can't be licensed: page A says so and offers Skip, and Mailbox stays empty for that student.
- **Microsoft renames buttons** ("Install apps", the "New Outlook" switch). The guides use the names on screen in October 2026; when they change, the text changes.
- **Reading the clipboard when the window comes forward.** It only reads, only while the key box is empty, and only takes text in a device key's exact shape; nothing is kept or logged.
- **Browsers word their download warnings differently** (Edge, Chrome, Cốc Cốc). The page shows Edge's (Windows' own browser) and otherwise says to keep the file.
- **GitHub Pages needs its one-time setting.** Until it is set, the workflow fails and says so.
