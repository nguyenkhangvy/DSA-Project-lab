# School-Life-Assistant: the laptop agent as one downloadable .exe, with automatic updates

**Date:** 2026-10-02
**Scope:** the laptop agent is built into a single Windows `.exe` that a student downloads and double-clicks (no Python, venv or `pip install`), and that updates itself when a new version is released
**Owner:** Nguyen Khang Vy
**Status:** Draft, waiting for review
**Builds on:** [Accounts window](2026-10-01-accounts-window-design.md), [Live sync](2026-10-01-live-sync-design.md), [Java website, §9 (going online)](2026-09-26-java-website-design.md). Everything there stays the same unless this document says otherwise.

---

## 1. Goal

The website is now online (Render), so students no longer need Java or MySQL. The agent still needs Python 3.12, a virtual environment, `pip install` and sometimes `Set-ExecutionPolicy` before the easy setup window opens, and a new version means `git pull`, which students without Git cannot do. The aim: a student downloads one file, double-clicks it, enters their accounts in the window they already know, and never installs or updates anything by hand afterwards.

### Decided with the student (2026-10-02)

- **One portable `.exe`** (install question: A). The student downloads `School-Life-Assistant.exe` and runs it. On its first run it copies itself to `%LOCALAPPDATA%\School-Life-Assistant\`, starts that copy and quits; the copy opens the accounts window. No administrator rights, as with the scheduled task today. Chosen over a Next-Next-Finish installer (a second tool to build and maintain) and a zip folder (least friendly).
- **Automatic updates in the background** (update question: 1). The agent checks for a newer release about once a day, downloads it, checks it, and swaps it in when no sync is running. The student does nothing. Chosen over an "Update" button the student must press (many never would, leaving old versions in use).
- **PyInstaller** builds the `.exe`. It handles Tkinter and pywin32, which the agent uses; the alternatives (Nuitka, Briefcase) are slower to build or heavier and give nothing this project needs.

### Not in scope

- Code signing (it costs money; Windows will show "Windows protected your PC" the first time, and the download instructions say to press **More info → Run anyway**)
- A Next-Next-Finish installer or an "Installed apps" entry
- Other operating systems (the agent is Windows only: Credential Manager, Task Scheduler, classic Outlook)
- Changing what the agent reads or uploads, or the website
- Updating a laptop that runs the agent from source (`pip install`): developers still use `git pull`

---

## 2. Release

- The version number lives in one place, `sla_agent/__init__.py` (`__version__`), and `agent/pyproject.toml` reads it from there. The agent's User-Agent and the version the website is told (section 6) come from it.
- A new workflow, `.github/workflows/release.yml`, runs when a tag `v*` (for example `v0.2.0`) is pushed. On a Windows runner it installs the agent and PyInstaller, builds `School-Life-Assistant.exe`, runs it once with `--version` as a smoke test (the build fails if it does not print the tag's version), and publishes a GitHub Release with two files: `School-Life-Assistant.exe` and `School-Life-Assistant.exe.sha256` (the checksum).
- The existing `ci.yml` also builds the `.exe` and runs the smoke test on every pull request, so a change that breaks packaging shows on the pull request and not on release day.
- The repository is public, so the Releases page and the update check need no login.
- The `.exe` is built **windowed** (no console), so the window and the scheduled task never flash a black window. The terminal commands (`status`, `sync-now`, …) stay for developers who run from source. The `.exe` supports `window` (default when started by double-click), `run`, `open-mail`, `schedule`, and `--version`.

## 3. First run: it installs itself

When the `.exe` starts and is **not** inside the install folder `%LOCALAPPDATA%\School-Life-Assistant\`:

1. Copy itself to `%LOCALAPPDATA%\School-Life-Assistant\School-Life-Assistant.exe` (replacing an older copy unless that copy is newer, in which case the older download is ignored and the newer copy is started).
2. Start the installed copy with the `window` command, then quit.

From then on the installed copy runs everything. The accounts window, as built, still turns on automatic sync and makes the shortcuts and links; those now point to the installed copy (section 4). The downloaded file can be deleted.

## 4. One helper for "how to start myself"

Three places build `python -m sla_agent …`: the scheduled task (`scheduler.py`), the Desktop and Start menu shortcuts (`shortcuts.py`) and the `sla-mail:` and `sla-agent:` link types (`mail_link.py`). A new `sla_agent/launcher.py` gives them one function, `program_and_arguments(command)`:

- Running from source: `pythonw.exe` (or `python.exe`) and `-m sla_agent <command>`, exactly as now.
- Running as the `.exe` (`sys.frozen` is set): the installed `.exe` and `<command>`.

Their tests check both forms. The scheduled task keeps running as the logged-in user with the same triggers.

## 5. Automatic update

An `update.py` module, called at the start of every `run`:

1. **When.** At most once every 24 hours (the time of the last check is saved with the agent's other state), and only when running as the `.exe`. A failed check (no internet, GitHub busy) does nothing and tries again at the next `run`; it never stops a sync.
2. **Check.** `GET https://api.github.com/repos/nguyenkhangvy/School-Life-Assistant/releases/latest`. If its tag's version is not newer than `__version__` (compared as numbers, so 0.10.0 is newer than 0.9.0), stop.
3. **Download.** `School-Life-Assistant.exe` is saved as `School-Life-Assistant.exe.new` in the install folder, over HTTPS only, from `github.com` release assets only. Its SHA-256 must equal the value in the release's `.sha256` file; on a mismatch or any error the `.new` file is deleted and nothing changes. A new version whose `--version` output does not match its tag is also refused.
4. **Swap.** At the start of a `run` that has a verified `.new` file and no sync in progress: rename the running `.exe` to `.old`, rename `.new` to `School-Life-Assistant.exe`, record "Updated to <version>" in the state, and carry on. (Windows lets a running program be renamed but not overwritten, which is why it is a rename.) The next `run`, one minute later, is the new version, and it deletes `.old`.
5. **If the new version fails to start**, the next `run` cannot rename it back by itself; the website's sync status shows no recent sync, and the README says to download the latest `.exe` again. (An automatic rollback is not worth its complexity for a student project.)

The accounts window shows the version ("School-Life-Assistant 0.2.0") and, after a swap, "Updated to 0.2.0 on <date>".

## 6. The website says "please update"

- The agent sends its version with every call to the sync API in its User-Agent, `SchoolLifeAssistant/<version>`. Today `edusoft_client.USER_AGENT` is fixed at `0.1`; it is changed to read `__version__`.
- The website has one constant, the oldest agent version it still accepts. A call from an older agent is refused with HTTP 426 and a message "Update School-Life-Assistant" instead of today's generic refusal. The agent shows that message in the window and in `status`. Today that constant is `0.1.0`, so nothing is refused; it is there for the day the upload format changes (the schema version check in `SyncContract` stays as it is).
- Because the update check runs daily, an old agent catches up within a day of a release.

## 7. Safety

- Only HTTPS, only `github.com`, only the checksum-verified file, only the repository named above. A file whose checksum does not match is never run.
- The update never touches Credential Manager, the agent's state or the saved device key.
- The agent runs as the student and needs no administrator rights. The install folder is the student's own.
- Unsigned files make Windows and some antivirus programs cautious. Section 1 and the README say what the student will see.

## 8. Tests (written first)

- `launcher.py`: both forms, from source and as the `.exe`; `scheduler`, `shortcuts` and `mail_link` use it (their existing tests keep passing).
- `update.py`, with a fake GitHub (the `responses` library, as in the existing tests): newer, same and older versions; 0.10.0 against 0.9.0; checksum mismatch deletes the download and leaves the old file; download error and "no internet" change nothing; the daily limit; a swap only when no sync is running.
- First run: copies itself and starts the installed copy; an already-installed copy is not overwritten by an older download.
- Website: an old agent version gets 426 with the message; the current version is accepted.
- CI: the packaged `.exe` starts and prints its version.
- The real update path (two releases, one updating to the other) can only be tried by hand on a Windows laptop; the plan includes that check.

## 9. Risks

- **Antivirus false alarms.** Unsigned PyInstaller files are sometimes flagged. Mitigation: publish the checksum, say what the student will see, and build one-folder-style if it turns out to be a problem.
- **A single-file `.exe` unpacks itself at every start** (about 1–3 seconds). It runs once a minute in the background, which is fine; the window opens a little slower than today.
- **Updating a broken release.** A release that cannot start leaves students without sync until they download again (section 5, point 5). Mitigation: the smoke test in the release workflow, and trying a release on one laptop before telling classmates.
