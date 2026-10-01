# School-Life-Assistant: the laptop agent as one downloadable .exe, with automatic updates

**Date:** 2026-10-02
**Scope:** students install the laptop agent by downloading one `School-Life-Assistant.exe` and double-clicking it (no Python, venv or `pip install`), and the agent updates itself when a new version is released
**Owner:** Nguyen Khang Vy
**Status:** Draft, waiting for review
**Builds on:** [Accounts window](2026-10-01-accounts-window-design.md), [Live sync](2026-10-01-live-sync-design.md), [Java website, §9 (going online)](2026-09-26-java-website-design.md). Everything there stays the same unless this document says otherwise.

---

## 1. Goal

The website is online (https://school-life-assistant.onrender.com), so students no longer need Java or MySQL. The agent still needs Python 3.12, a virtual environment, `pip install` and sometimes `Set-ExecutionPolicy` before the setup window opens, and a new version means `git pull`, which students without Git cannot do. The aim: a student downloads one file, double-clicks it, enters their accounts in the window they already know, and never installs or updates anything by hand afterwards.

### Decided with the student (2026-10-02)

- **One `.exe` to download and double-click** (install question: A). It installs into the student's own folder, with no administrator rights, as the scheduled task already runs today. Chosen over a Next-Next-Finish installer (another tool to build and maintain) and a zip folder (least friendly).
- **Automatic updates in the background** (update question: 1). The agent checks for a newer release about once a day, downloads it, checks it, and installs it. The student does nothing. Chosen over an "Update" button the student must press (many never would).
- **PyInstaller** builds the `.exe`. It handles Tkinter and pywin32, which the agent uses; Nuitka and Briefcase are slower to build or heavier and give nothing this project needs.

### Decided while writing this spec

- **The download is a small setup; what it installs is a folder.** A single-file PyInstaller `.exe` unpacks its whole Python (about 30 MB) into a temporary folder every time it starts, and the scheduled task starts the agent every minute: 1,440 unpacks a day, costing battery, disk writes and an antivirus scan each time. So the release has two builds: the **app** (a folder with `School-Life-Assistant.exe` and its files, which starts without unpacking anything) and the **setup** (the single `School-Life-Assistant.exe` students download, which carries the app inside it and unpacks only when it installs or updates). For the student nothing changes: one file, double-click, the window opens.
- **Updates replace the app folder from outside it.** Windows does not let a program's files be replaced while it runs, so the running agent downloads the new setup, starts it, and exits; the setup swaps the folder. The scheduled task, links and shortcuts always point to the same path, so an update re-registers nothing.

### Not in scope

- Code signing (it costs money; Windows shows "Windows protected your PC" the first time, and the download page says to press **More info → Run anyway**). Section 9 makes antivirus warnings less likely.
- An "Installed apps" entry with Uninstall (the window's existing "forget" and deleting the folder do the job)
- Other operating systems (the agent is Windows only: Credential Manager, Task Scheduler, classic Outlook)
- Changing what the agent reads or uploads
- Updating a laptop that runs the agent from source (`pip install`): developers still use `git pull`

---

## 2. What the student sees

1. On the website, School → Devices has **Download School-Life-Assistant for Windows**. The link always gives the newest release: `https://github.com/nguyenkhangvy/School-Life-Assistant/releases/latest/download/School-Life-Assistant.exe`.
2. They double-click the download. The first time, Windows may say "Windows protected your PC": **More info → Run anyway** (the page says so).
3. After a few seconds the School-Life-Assistant window opens, with the website's address already filled in (`https://school-life-assistant.onrender.com`). They paste the device key from the Devices page and enter their accounts, as today. Saving turns on automatic sync and adds the Start menu and Desktop shortcuts, as today.
4. They never do anything again. New versions install themselves within a day of being released. Accounts shows the version, and the day it last updated itself.

## 3. Release

- **One version number**, `__version__` in `sla_agent/__init__.py`. `agent/pyproject.toml` reads it from there, and the agent's User-Agent becomes `SchoolLifeAssistant/<version> (IU student project)` (today it is fixed at `0.1`). The first `.exe` release is `0.2.0`.
- **Build script** `agent/packaging/build.py`, used by CI, by the release and by anyone on Windows:
  1. PyInstaller builds the **app** as a folder (`--onedir --windowed`): `dist/School-Life-Assistant/School-Life-Assistant.exe` plus its `_internal` folder, and a `version.txt` next to the `.exe` holding the version.
  2. The app folder is zipped.
  3. PyInstaller builds the **setup** as one file (`--onefile --windowed`) from `sla_agent/installer.py` (standard library only, so it stays small), with the zip inside it: `dist/School-Life-Assistant.exe`.
  4. `SHA256SUMS.txt` lists the setup's SHA-256 checksum.
- **The app** gets a new command, `self-check <version>`: it exits with 0 only if its version is `<version>` and everything it needs loads from the bundle (Tkinter with its Tcl files, pywin32, keyring's Windows Credential Manager backend, the data contract, and the HTTPS certificates). Built **windowed** (no console), the `.exe` cannot print, so the exit code is the answer. Started with no command (double-clicked), the app opens the window.
- **Release workflow** `.github/workflows/release.yml` runs when a tag such as `v0.2.0` is pushed. On a Windows runner it checks that the tag matches `__version__`, builds (section 9: PyInstaller's launcher compiled from source), runs the smoke tests below, and publishes a GitHub Release with `School-Life-Assistant.exe` and `SHA256SUMS.txt`. The repository is public, so downloading and the update check need no login.
- **Smoke tests**, in `ci.yml` on every pull request (a new `exe` job on Windows) and in the release: the built app passes `self-check`; the built setup, run in update mode against an empty temporary `LOCALAPPDATA`, installs an app that passes `self-check`.

## 4. The setup

`School-Life-Assistant.exe` (the download) has two modes and installs to `%LOCALAPPDATA%\SchoolLifeAssistant\app\`, next to the agent's existing `state.json` and `agent.log`.

**Install** (double-clicked, no arguments):

1. If an app is installed and its `version.txt` is newer than the setup's version, open it (`app\School-Life-Assistant.exe window`) and stop: an old download double-clicked from Downloads never replaces a newer app.
2. Unpack the app into `app.new\`, then run `app.new\School-Life-Assistant.exe self-check <version>`. If it fails, delete `app.new\` and show a message box with what to do.
3. Swap: rename `app\` to `app.old\`, then `app.new\` to `app\`, then delete `app.old\`. Windows refuses to rename a folder while a program runs from it; the setup tries for up to a minute (a sync usually ends by then), then deletes `app.new\` and says "Close School-Life-Assistant, then run this again." If the second rename fails, `app.old\` is renamed back.
4. Start `app\School-Life-Assistant.exe window` and exit.

**Update** (`--update <process id>`, started by the agent, section 6): the same steps 2–3, with no window and no message boxes. Before step 3 it waits for that process (the agent that started it) to exit, and it keeps trying the swap for up to 10 minutes, since a full sync can take a few minutes. Any failure leaves the old app as it was.

The setup always works from the `SchoolLifeAssistant` folder (never from inside `app\`), and writes what it did to `setup.log` there.

## 5. One helper for "how to start myself"

Three places build `python -m sla_agent …` today: the scheduled task (`scheduler.py`), the Desktop and Start menu shortcuts (`shortcuts.py`) and the `sla-mail:` and `sla-agent:` link types (`mail_link.py`). A new `sla_agent/launcher.py` gives them:

- `program()`: the installed app's `.exe` when running as the app (`sys.frozen`), else `pythonw.exe` next to the running Python, as `windowless_python()` does today (it moves here).
- `arguments(program, command)`: `-m sla_agent <command>` when `program` is a Python, `<command>` when it is `School-Life-Assistant.exe`.

The accounts window's **Repair** button covers a laptop set up from source before: when the app's window sees a scheduled task that runs a different program, Automatic sync says "runs another copy of School-Life-Assistant" with Repair, which points the task, links and shortcuts at the app (the existing `turn_on_sync`).

## 6. Automatic update

A new `sla_agent/update.py`, called at the start of every `run` when running as the app (never from source):

1. **When.** At most once every 24 hours: the time of the check is saved in `state.json` (`update_checked_at`) before the check starts, so a failed or slow check waits a day like a successful one and never retries every minute.
2. **Check.** `GET https://api.github.com/repos/nguyenkhangvy/School-Life-Assistant/releases/latest`. Its `tag_name` (for example `v0.3.0`) is compared with `__version__` as numbers (0.10.0 is newer than 0.9.0). Not newer: done.
3. **Download.** The release's `SHA256SUMS.txt` and `School-Life-Assistant.exe`, only from `https://github.com/nguyenkhangvy/School-Life-Assistant/releases/download/…` links (GitHub then redirects to its own download servers), over HTTPS, at most 150 MB, into `SchoolLifeAssistant\update\`. If the file's SHA-256 is not the one listed, it is deleted and nothing else happens.
4. **Hand over.** Start the downloaded setup with `--update <this process id>`, detached from the scheduled task, and end this `run` without syncing. The setup swaps the app (section 4); the next minute's `run` is the new version.
5. **After an update**, the new version's first `run` sees that `agent_version` in `state.json` differs from its own, saves its version and the time (`updated_at`, shown in Accounts), and deletes the downloaded setup.

Any error in steps 2–4 is logged in `agent.log` and changes nothing; a sync still happens at the next `run`. If a release is broken so badly that its app cannot start, its `self-check` fails during the setup's step 2 and the old app keeps running.

## 7. The website

- **"Please update".** A new check before the device key check reads the agent's version from its User-Agent. An agent older than the website's minimum gets HTTP 426 with `{"error": "update_required"}`. The minimum is `0.1` today, so nothing is refused; it is there for the day the upload format changes. When the agent gets 426, it clears `update_checked_at` (so the next `run` checks for an update right away) and records "This version of School-Life-Assistant is too old; it is updating itself." as the last result, shown in Accounts. Requests without a recognisable version are let through.
- **School → Devices** gets an "Install on your laptop" card with the download link and the "More info → Run anyway" note, and the new-key text says to open School-Life-Assistant from the Start menu instead of double-clicking `School-Life-Assistant.cmd`.

## 8. The window

- The address in the first-time form is `https://school-life-assistant.onrender.com` when running as the app, and stays `http://localhost:5000` from source.
- Accounts gets a **Version** row: "0.3.0, updated by itself on 05/10 14:02" (or just "0.2.0" before any update). No button.
- Automatic sync can say "runs another copy of School-Life-Assistant" (section 5).

## 9. Fewer antivirus warnings

Without code signing, false alarms cannot be ruled out. These make them less likely:

- **No UPX.** Both builds use `--noupx`; UPX-packed files are a common trigger.
- **PyInstaller's launcher compiled from source** in the release workflow (`PYINSTALLER_COMPILE_BOOTLOADER=1`, `pip install --no-binary=PyInstaller`). The prebuilt launcher is shared by many programs, some of them malware, so antivirus engines know its fingerprint. CI pull-request builds use the prebuilt one for speed.
- **Windows version details** (product name, version, description) in both `.exe` files, shown in Properties.
- **The app is a folder build**; only the setup is a single file, and it runs once per install or update.
- **Before telling classmates about a release:** upload `School-Life-Assistant.exe` to virustotal.com; if Microsoft Defender flags it, report it as a false positive at microsoft.com/wdsi/filesubmission (usually cleared in a day or two). The README's release steps list this.
- **Later, if needed:** apply to SignPath Foundation, which signs open-source projects for free.

## 10. Safety

- Downloads only over HTTPS, only from this repository's releases, and only a file whose SHA-256 matches the release's `SHA256SUMS.txt`. The checksum catches a broken or incomplete download; HTTPS and the repository's own release are what make the file genuine.
- A new app is used only after its own `self-check` passes.
- The update never touches Credential Manager, `state.json` (other than the fields in section 6) or the saved device key.
- Everything runs as the student, with no administrator rights, in the student's own folder.

## 11. Tests (written first)

- `launcher.py`: both forms (Python and app); `scheduler`, `shortcuts` and `mail_link` use it, and their existing tests keep passing.
- `update.py`, with a fake GitHub (the `responses` library, as in the existing tests): newer, same and older versions; 0.10.0 against 0.9.0; the 24-hour limit; checksum mismatch deletes the file; links outside the repository are refused; a download error changes nothing; after 426 the next `run` checks.
- `installer.py`, with temporary folders and a fake `self-check`: first install; replacing an older app; an installed newer app is opened, not replaced; a failing `self-check` leaves the old app; a folder in use is retried and then given up with the old app intact; a failed second rename puts the old app back.
- `self-check`, `run` calling the update, the window's Version row and the "another copy" state, the default address when frozen.
- Website: an old version gets 426; the current version, and a request with no version, are let through; the Devices page shows the download link.
- CI: the smoke tests in section 3 on a Windows runner.
- By hand, once: install `0.2.0` on a laptop, release `0.2.1`, and see the laptop update itself (deleting `update_checked_at` from `state.json` makes it check at the next minute).

## 12. Risks

- **Antivirus false alarms**, for the setup or for "a program that downloads and runs a program", which is how the update works. Mitigations in section 9.
- **Windows ending the setup when the scheduled `run` that started it exits.** The setup is started detached (and outside the task's job where Windows allows it); the by-hand test in section 11 confirms an update completes from the scheduled task.
- **The window left open during an update** blocks the swap for that day; the next day's check tries again.
- **GitHub's limit of 60 unauthenticated checks per hour per network.** One check a day per laptop stays far below it, even for a class behind one school network.
