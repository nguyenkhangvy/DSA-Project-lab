# Easy Install Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Students who don't know code download School-Life-Assistant from a public page, set it up through step-by-step pages (a device-key guide, EduSoft, Blackboard, Outlook (classic) with install and sign-in guides), and choose whether to get a Desktop icon.

**Architecture:** The laptop agent's Tkinter window replaces its one-page first-time form with `setup_steps.py` (one page per step). The Outlook page (`outlook_page.py`) is shared by the setup and Accounts. What the screens share moves to `window_parts.py`. `accounts.py` still does every check and save. Shortcuts split into Start menu entries, which are always made, and a Desktop icon made only when the student asks. A static bilingual page in `pages/` is published to GitHub Pages by its own workflow.

**Tech Stack:** Python 3.12, Tkinter/ttk (Tk 8.6), pywin32, pytest. Java 17 / Spring Boot / Thymeleaf (one template and its test). Plain HTML, CSS and a little JavaScript. GitHub Actions (Pages).

**Spec:** `docs/superpowers/specs/2026-10-02-easy-install-design.md`, committed on branch `easy-install`. Read it before starting.

**Where to run things:** PowerShell, in the repository root (`School-Life-Assistant\`, the folder with `README.md`), on branch `easy-install`. Python is the project's `.venv`. The baseline before Task 1 is `.\.venv\Scripts\python.exe -m pytest` → **773 passed**.

## Global Constraints

- **Platform:** Windows 10 and 11 only. Python 3.12 with Tk 8.6. No new dependencies: the standard library and what `requirements-dev.txt` already has.
- **Language:** the window's text stays in English. Only `pages/index.html` has Vietnamese too.
- **Device key shape:** `sla_` followed by exactly 43 characters from `A–Z a–z 0–9 _ -` (`web/.../sync/DeviceKeys.java`). Text of any other shape is never sent to the website.
- **Fixed addresses:**
  - Download link, always the newest release: `https://github.com/nguyenkhangvy/School-Life-Assistant/releases/latest/download/School-Life-Assistant.exe`
  - Download page: `https://nguyenkhangvy.github.io/School-Life-Assistant/`
  - IU student email domain: `@student.hcmiu.edu.vn`
- **Secrets:**
  - Passwords and the device key go to `log.protect()` as soon as they are read, are saved only in Windows Credential Manager, and their boxes are cleared once saved.
  - The clipboard is only read, and only text in a device key's exact shape is taken.
- **Threads:** every check runs through `app.run`, on a worker thread. Tk is touched only on its own thread.
- **Outlook:** opening a page never starts Outlook. Only state C (someone signed in) asks Outlook for its accounts.
- **Desktop icon:**
  - It is added only when the student asks (All set → **Yes, add the icon**, or Accounts → **Add**).
  - `shortcuts.make` never adds one, and nothing re-creates a deleted one.
  - The Start menu entries are always made.
- **Pages workflow:** `.github/workflows/pages.yml` publishes only when `github.repository == 'nguyenkhangvy/School-Life-Assistant'`. `main` is also pushed to the dsa and ooad repositories.
- **Version:** `0.3.0`. `ONLINE_ADDRESS` stays `https://school-life-assistant.onrender.com`; hosting is decided separately.
- **Tests:** fakes only, never the real keyring, registry, Windows shell, clipboard, browser or Outlook. `agent/tests/conftest.py` and the fixtures below see to it.
- **Words on screen:** exactly the constants in this plan, which copy the spec.
- **Commits:** the repo's style (`feat(agent): …`, `docs: …`). Every message ends with the line `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

The five inputs a student is most likely to hit that the spec implies but doesn't spell out. Each has a test in the task named.

1. **The website can't be reached on step 1** (not hosted yet, or the laptop is offline): the page says so plainly, **Next** stays off, nothing is saved. *(Task 3: `test_a_website_that_cant_be_reached_says_so_and_saves_nothing`)*
2. **A key copied with spaces or a line break around it** (selected by hand in the browser): it is still recognised, checked and saved without them. *(Task 3: `test_a_key_with_spaces_or_a_line_break_around_it_is_checked_without_them`, `test_paste_takes_a_copied_key`)*
3. **An address typed after Change** that is `http://` for another computer, or ends with `/`: the key is never sent over plain HTTP, and the trailing slash still works. *(Task 3: `test_a_plain_http_address_is_refused_without_sending_the_key`, `test_change_shows_the_address_box_and_a_new_address_checks_the_key_again`)*
4. **Outlook hangs while the Outlook step looks** (a security prompt is waiting): the page keeps "Looking…", **Skip** still works, and Outlook's late answer changes nothing. *(Task 4: `test_skip_works_while_outlook_is_still_being_looked_for`)*
5. **The built app is missing one of the new window modules**: the first window a student sees would crash with no message. The self-check catches it before any release. *(Task 3: `test_the_window_and_its_pages_load`)*

---

## File map

| File | Responsibility | Task |
|---|---|---|
| `agent/sla_agent/shortcuts.py` | Start menu entries always; Desktop icon on request (`add_desktop`, `on_desktop`) | 1 |
| `agent/sla_agent/accounts.py` | `add_desktop_icon`, `desktop_icon_on`, new `Tools` fields (1); `outlook_state`/`start_outlook` fields (2); `is_device_key`, `save_and_turn_on_sync`, `SITE_ACCEPTED`; `first_setup` removed (3) | 1, 2, 3 |
| `agent/sla_agent/outlook_reader.py` | `classic_outlook()` and `start_classic_outlook()`: registry only, never starts Outlook | 2 |
| `agent/sla_agent/window_parts.py` (new) | What the screens share: running a check off Tk's thread; headings, fields, lines | 2 |
| `agent/sla_agent/outlook_page.py` (new) | The Outlook page: states A/B/C, guides and buttons | 2 |
| `agent/sla_agent/setup_steps.py` (new) | The setup pages: website key, EduSoft, (Blackboard, Outlook), All set | 3, 4 |
| `agent/sla_agent/window.py` | App and Accounts (Desktop icon row, Outlook editor); the one-page form removed | 1, 2, 3 |
| `agent/sla_agent/cli.py` | `tools()` gets the new fields; `find_open_outlook_accounts` removed | 1, 2, 3 |
| `agent/sla_agent/selfcheck.py` | The built app's self-check also loads the window and its pages | 3 |
| `agent/tests/…` | Tests, fakes (`accounts_fakes.py`), fixtures (`conftest.py`) | 1–4 |
| `pages/index.html`, `pages/style.css` (new) | The download page, English and Vietnamese | 5 |
| `.github/workflows/pages.yml` (new) | Publishes `pages/` to GitHub Pages | 5 |
| `deploy/tests/test_download_page.py` (new) | Checks the page and its workflow. It lives outside `pages/` because everything in `pages/` is published; the spec's `pages/tests/` would publish the test too. | 5 |
| `web/…/templates/school/devices.html`, `web/…/DevicesPageTest.java` | The Devices page links to the download page | 6 |
| `README.md` | Install steps point to the download page; the one-time Pages setting | 6 |
| `agent/sla_agent/__init__.py`, the spec's status | 0.3.0, "Built" | 7 |

The spec's code layout names `window_parts.py` and `setup_steps.py`. This plan also gives the Outlook page its own `outlook_page.py`, because both the setup and Accounts show it. That keeps `setup_steps.py` to the pages alone.

---

### Task 1: The Desktop icon only when the student asks

**Files:**
- Modify: `agent/sla_agent/shortcuts.py` (whole file)
- Modify: `agent/sla_agent/accounts.py` (`Tools`, constants, `_links_and_shortcuts`, new Desktop icon section)
- Modify: `agent/sla_agent/cli.py` (`tools()`)
- Modify: `agent/sla_agent/window.py` (`AccountsScreen`)
- Modify: `agent/tests/accounts_fakes.py`
- Test: `agent/tests/test_shortcuts.py`, `agent/tests/test_launcher.py`, `agent/tests/test_cli.py`, `agent/tests/test_accounts.py`, `agent/tests/test_window.py`

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - `shortcuts.make(program, folder)`: the Start menu entries; refreshes the Desktop icon only if it is there.
  - `shortcuts.add_desktop(program, folder)` and `shortcuts.on_desktop() -> bool`.
  - `accounts.Tools.add_desktop_shortcut: (program, folder)` and `accounts.Tools.has_desktop_shortcut: () -> bool`.
  - `accounts.DESKTOP_ICON_ADDED: str`, `accounts.add_desktop_icon(tools) -> Result`, `accounts.desktop_icon_on(tools) -> bool`.
  - Accounts row `"desktop"`: "on", or "off" with **Add**.
  - In `accounts_fakes.Fakes`, the parts `"desktop icon"` (made by `add_desktop_shortcut`, and failable through `fakes.fail`) and `has_desktop_shortcut` returning `"desktop icon" in fakes.done`.

- [ ] **Step 1: Write the failing tests for the shortcuts**

In `agent/tests/test_shortcuts.py`, replace everything above `def test_the_project_folder_has_a_double_click_starter():` with:

```python
"""The Start menu entries and the Desktop icon, made in a fake Windows shell (conftest.py): tests never touch the real
ones."""

from pathlib import Path

from sla_agent import shortcuts

PYTHONW = r"C:\IU_SCHOOL\p\.venv\Scripts\pythonw.exe"


def made(isolated_agent):
    return sorted(isolated_agent.shell.root.rglob("*.lnk"))


def places(isolated_agent):
    """(the Desktop icon, the app in the Start menu, Accounts in the Start menu), where the fake shell keeps them."""
    root = isolated_agent.shell.root
    return (root / "OneDrive" / "Máy tính" / "School-Life-Assistant.lnk",
            root / "Start Menu" / "Programs" / "School-Life-Assistant.lnk",
            root / "Start Menu" / "Programs" / "School-Life-Assistant Accounts.lnk")


def test_make_puts_the_app_and_accounts_in_the_start_menu_but_no_icon_on_the_desktop(isolated_agent, tmp_path):
    _, start_menu, accounts = places(isolated_agent)

    shortcuts.make(PYTHONW, tmp_path / "home")

    assert made(isolated_agent) == sorted([start_menu, accounts])
    assert start_menu.read_text(encoding="utf-8").splitlines() == [
        f"{PYTHONW} -m sla_agent open", str(tmp_path / "home")]
    assert accounts.read_text(encoding="utf-8").splitlines() == [
        f"{PYTHONW} -m sla_agent window", str(tmp_path / "home")]
    assert not shortcuts.on_desktop()


def test_add_desktop_puts_the_app_on_the_desktop(isolated_agent, tmp_path):
    desktop, _, _ = places(isolated_agent)

    shortcuts.add_desktop(PYTHONW, tmp_path / "home")

    assert desktop.read_text(encoding="utf-8").splitlines() == [f"{PYTHONW} -m sla_agent open", str(tmp_path / "home")]
    assert shortcuts.on_desktop()


def test_make_points_a_desktop_icon_that_is_there_at_the_new_program_and_never_brings_back_a_deleted_one(
        isolated_agent, tmp_path):
    desktop, _, _ = places(isolated_agent)
    shortcuts.add_desktop(r"C:\IU SCHOOL\old\pythonw.exe", tmp_path)

    shortcuts.make(PYTHONW, tmp_path)

    assert desktop.read_text(encoding="utf-8").startswith(f"{PYTHONW} -m sla_agent open")
    desktop.unlink()  # the student deleted it

    shortcuts.make(PYTHONW, tmp_path)

    assert not desktop.exists()


def test_make_again_replaces_them_and_remove_is_fine_when_they_are_gone(isolated_agent, tmp_path):
    shortcuts.make(r"C:\IU SCHOOL\old\pythonw.exe", tmp_path)
    shortcuts.add_desktop(r"C:\IU SCHOOL\old\pythonw.exe", tmp_path)
    shortcuts.make(PYTHONW, tmp_path)

    assert len(made(isolated_agent)) == 3
    assert all(path.read_text(encoding="utf-8").startswith(PYTHONW) for path in made(isolated_agent))

    shortcuts.remove()
    shortcuts.remove()

    assert made(isolated_agent) == []
```

(The rest of the file stays: `test_the_project_folder_has_a_double_click_starter` and the COM section. `Path` is still used there.)

In `agent/tests/test_launcher.py`, in `test_the_task_the_links_and_the_shortcuts_start_the_app_without_python`, replace

```python
    shortcuts.make(APP, tmp_path)
```

with

```python
    shortcuts.make(APP, tmp_path)
    shortcuts.add_desktop(APP, tmp_path)
```

(Its expected list of three shortcuts stays as it is.)

In `agent/tests/test_cli.py`:

1. Replace `test_setup_adds_the_window_link_type_and_the_shortcuts` with:

```python
def test_setup_adds_the_window_link_type_and_the_start_menu_entries(world, isolated_agent, capsys):
    world.answer_setup()

    assert cli.main(["setup"]) == 0

    assert isolated_agent.registry.keys[WINDOW_COMMAND][""].endswith('-m sla_agent window "%1"')
    assert sorted(path.name for path in isolated_agent.shell.root.rglob("*.lnk")) == [
        "School-Life-Assistant Accounts.lnk", "School-Life-Assistant.lnk"]
    assert "every minute" in capsys.readouterr().out
```

2. In `test_setup_says_when_the_shortcuts_could_not_be_made_but_keeps_the_rest`, replace

```python
    assert "Couldn't make the Desktop and Start menu shortcuts" in out
```

with

```python
    assert "Couldn't make the Start menu entries" in out
```

3. In `test_forget_removes_the_window_link_type_and_the_shortcuts`, replace

```python
    shortcuts.make("pythonw.exe", tmp_path)
```

with

```python
    shortcuts.make("pythonw.exe", tmp_path)
    shortcuts.add_desktop("pythonw.exe", tmp_path)
```

4. In `test_the_first_run_of_a_new_version_points_the_links_and_shortcuts_at_this_app`, replace

```python
    assert made == [("School-Life-Assistant Accounts.lnk", f"{APP} window"),
                    ("School-Life-Assistant.lnk", f"{APP} open"), ("School-Life-Assistant.lnk", f"{APP} open")]
```

with

```python
    assert made == [("School-Life-Assistant Accounts.lnk", f"{APP} window"), ("School-Life-Assistant.lnk", f"{APP} open")]
```

5. Right after that test, add:

```python
def test_an_update_points_a_desktop_icon_at_this_app_and_never_brings_back_a_deleted_one(world, built_app,
                                                                                       isolated_agent, monkeypatch):
    from sla_agent import shortcuts

    configure()
    shortcuts.add_desktop(r"C:\IU_SCHOOL\p\.venv\Scripts\pythonw.exe", agent_home())  # the student asked for it
    desktop = isolated_agent.shell.root / "OneDrive" / "Máy tính" / shortcuts.APP
    monkeypatch.setattr(launcher, "program", lambda: APP)

    def a_new_version_runs():
        state = load_state()
        state.agent_version = "0.1.0"  # what ran before, so this run is a new version's first
        save_state(state)
        cli.main(["run"])

    a_new_version_runs()
    assert desktop.read_text(encoding="utf-8").splitlines()[0] == f"{APP} open"

    desktop.unlink()  # the student deletes the icon
    a_new_version_runs()
    assert not desktop.exists()
```

- [ ] **Step 2: Run them to see them fail**

Run: `.\.venv\Scripts\python.exe -m pytest agent\tests\test_shortcuts.py agent\tests\test_launcher.py agent\tests\test_cli.py -v`
Expected: FAIL. `AttributeError: module 'sla_agent.shortcuts' has no attribute 'add_desktop'` (or `on_desktop`). The setup test lists three `.lnk` files instead of two. The message test can't find "Couldn't make the Start menu entries".

- [ ] **Step 3: Make the Desktop icon a separate shortcut**

Replace the whole of `agent/sla_agent/shortcuts.py` with:

```python
"""The School-Life-Assistant shortcuts (spec 2026-10-01-accounts-window-design.md, 4.3; spec
2026-10-02-easy-install-design.md, 5): "School-Life-Assistant" in the Start menu opens the website as an app
(`open`), and "School-Life-Assistant Accounts" in the Start menu opens the accounts window (`window`), through the
built app or a windowless Python (launcher.py). The Desktop icon is the student's choice: only add_desktop makes one,
and one they deleted stays deleted.

Made with Windows' own WScript.Shell (through pywin32), for this Windows user only. Tests replace `_shell`."""

from pathlib import Path

from sla_agent.launcher import arguments

APP = "School-Life-Assistant.lnk"
ACCOUNTS = "School-Life-Assistant Accounts.lnk"
OPEN_DESCRIPTION = "Open School-Life-Assistant"
ACCOUNTS_DESCRIPTION = "Enter and change your School-Life-Assistant accounts"


def _shell():
    import pythoncom
    import win32com.client

    pythoncom.CoInitialize()  # COM is per thread, and Repair and the setup pages save on a worker thread
    return win32com.client.Dispatch("WScript.Shell")


def _desktop(shell):
    """The Desktop icon's path (the Desktop may be OneDrive's, with Vietnamese letters)."""
    return Path(shell.SpecialFolders("Desktop")) / APP


def _start_menu(shell):
    """(path, command, description) of the Start menu entries: the app, and Accounts."""
    programs = Path(shell.SpecialFolders("Programs"))
    return [(programs / APP, "open", OPEN_DESCRIPTION), (programs / ACCOUNTS, "window", ACCOUNTS_DESCRIPTION)]


def _save(shell, path, program, command, description, folder):
    shortcut = shell.CreateShortcut(str(path))
    shortcut.TargetPath = str(program)
    shortcut.Arguments = arguments(program, command)
    shortcut.WorkingDirectory = str(folder)
    shortcut.Description = description
    shortcut.Save()


def make(program, folder):
    """Make, or replace, the Start menu entries, each starting `program` (launcher.arguments) in `folder`, and point
    the Desktop icon at `program` too when it is there. Never adds a Desktop icon."""
    shell = _shell()
    for path, command, description in _start_menu(shell):
        _save(shell, path, program, command, description, folder)
    desktop = _desktop(shell)
    if desktop.exists():
        _save(shell, desktop, program, "open", OPEN_DESCRIPTION, folder)


def add_desktop(program, folder):
    """The School-Life-Assistant icon on the Desktop, starting `program` in `folder`: the student asked for it."""
    shell = _shell()
    _save(shell, _desktop(shell), program, "open", OPEN_DESCRIPTION, folder)


def on_desktop():
    """Whether the School-Life-Assistant icon is on this user's Desktop."""
    return _desktop(_shell()).exists()


def remove():
    shell = _shell()
    for path in [_desktop(shell), *(path for path, _, _ in _start_menu(shell))]:
        path.unlink(missing_ok=True)
```

In `agent/sla_agent/accounts.py`, in `_links_and_shortcuts`, replace

```python
        notes.append(f"Couldn't make the Desktop and Start menu shortcuts ({error.__class__.__name__}). "
                     "The Accounts page on the website opens this window too.")
```

with

```python
        notes.append(f"Couldn't make the Start menu entries ({error.__class__.__name__}). "
                     "The Accounts page on the website opens this window too.")
```

- [ ] **Step 4: Run them to see them pass**

Run: `.\.venv\Scripts\python.exe -m pytest agent\tests\test_shortcuts.py agent\tests\test_launcher.py agent\tests\test_cli.py -v`
Expected: PASS.

- [ ] **Step 5: Write the failing tests for adding the icon**

In `agent/tests/test_accounts.py`, after `test_turn_on_sync_fails_when_the_task_cannot_be_made`, add:

```python
def test_turn_on_sync_makes_the_start_menu_entries_but_no_desktop_icon(fakes):
    assert accounts.turn_on_sync(fakes.tools()).ok

    assert "shortcuts" in fakes.done
    assert "desktop icon" not in fakes.done


# ---- the Desktop icon ---------------------------------------------------------------


def test_the_desktop_icon_is_added_when_asked(fakes):
    assert not accounts.desktop_icon_on(fakes.tools())

    assert accounts.add_desktop_icon(fakes.tools()) == accounts.Result(True, accounts.DESKTOP_ICON_ADDED)

    assert accounts.desktop_icon_on(fakes.tools())


def test_a_desktop_icon_that_cannot_be_added_says_where_the_app_is(fakes):
    fakes.fail = {"desktop icon": RuntimeError("com_error")}

    result = accounts.add_desktop_icon(fakes.tools())

    assert not result.ok
    assert result.message == ("Couldn't add the Desktop icon (RuntimeError). School-Life-Assistant is in the Start "
                              "menu.")


def test_the_desktop_icon_counts_as_off_when_windows_cannot_say(fakes):
    def broken():
        raise RuntimeError("com_error")

    tools = fakes.tools()
    tools.has_desktop_shortcut = broken

    assert accounts.desktop_icon_on(tools) is False
```

In `agent/tests/test_window.py`, after `test_repair_turns_sync_on_again`, add:

```python
def test_accounts_offers_the_desktop_icon_and_add_puts_it_there(root, fakes):
    set_up()
    app = open_window(root, fakes)
    assert app.screen.values["desktop"].get() == "off"

    app.screen.buttons["desktop"].invoke()

    assert "desktop icon" in fakes.done
    assert app.screen.notice.get() == "✓ " + accounts.DESKTOP_ICON_ADDED
    assert app.screen.values["desktop"].get() == "on"
    assert "desktop" not in app.screen.buttons


def test_a_desktop_icon_that_cannot_be_added_says_so(root, fakes):
    set_up()
    fakes.fail = {"desktop icon": RuntimeError("com_error")}
    app = open_window(root, fakes)

    app.screen.buttons["desktop"].invoke()

    assert app.screen.notice.get().startswith("✗ Couldn't add the Desktop icon (RuntimeError).")
    assert app.screen.values["desktop"].get() == "off"
```

- [ ] **Step 6: Run them to see them fail**

Run: `.\.venv\Scripts\python.exe -m pytest agent\tests\test_accounts.py agent\tests\test_window.py -v`
Expected: FAIL with `AttributeError: module 'sla_agent.accounts' has no attribute 'desktop_icon_on'` and `KeyError: 'desktop'`.

- [ ] **Step 7: Add the icon in accounts.py, the tools, the fakes and Accounts**

In `agent/sla_agent/accounts.py`:

Replace

```python
    make_shortcuts: Callable  # (program, folder)
```

with

```python
    make_shortcuts: Callable  # (program, folder): the Start menu entries; points a Desktop icon that is there too
    add_desktop_shortcut: Callable  # (program, folder): the Desktop icon, when the student asks for it
    has_desktop_shortcut: Callable  # () -> whether the Desktop icon is there
```

After the line `SYNC_ON = "Automatic sync is on: this laptop checks in every minute while you're logged in."` add:

```python
DESKTOP_ICON_ADDED = "The School-Life-Assistant icon is on your Desktop."
```

After the function `point_links_and_shortcuts_here`, add:

```python
# ---- the Desktop icon -------------------------------------------------------------------


def add_desktop_icon(tools):
    """The School-Life-Assistant icon on the Desktop, which only the student asks for (spec
    2026-10-02-easy-install-design.md, 5): the setup's last page, or Accounts' Add."""
    try:
        tools.add_desktop_shortcut(tools.program(), agent_home())
    except Exception as error:  # pywin32 raises its own com_error, not an OSError
        log.warning("Couldn't add the Desktop icon: %s", error)
        return Result(False, f"Couldn't add the Desktop icon ({error.__class__.__name__}). "
                             "School-Life-Assistant is in the Start menu.")
    return Result(True, DESKTOP_ICON_ADDED)


def desktop_icon_on(tools):
    """Whether the School-Life-Assistant icon is on the Desktop; False when Windows can't say."""
    try:
        return bool(tools.has_desktop_shortcut())
    except Exception as error:  # pywin32's com_error, or not Windows
        log.debug("Couldn't look for the Desktop icon (%s)", error.__class__.__name__)
        return False
```

In `agent/sla_agent/cli.py`, in `tools()`, replace

```python
        register_window_link=mail_link.register_window, make_shortcuts=shortcuts.make,
        has_window_link=mail_link.window_registered, open_site=app_window.open_app)
```

with

```python
        register_window_link=mail_link.register_window, make_shortcuts=shortcuts.make,
        add_desktop_shortcut=shortcuts.add_desktop, has_desktop_shortcut=shortcuts.on_desktop,
        has_window_link=mail_link.window_registered, open_site=app_window.open_app)
```

In `agent/tests/accounts_fakes.py`, replace

```python
            make_shortcuts=self._part("shortcuts"), has_window_link=lambda: "window link" in self.done,
            open_site=self.opened.append)
```

with

```python
            make_shortcuts=self._part("shortcuts"), add_desktop_shortcut=self._part("desktop icon"),
            has_desktop_shortcut=lambda: "desktop icon" in self.done,
            has_window_link=lambda: "window link" in self.done, open_site=self.opened.append)
```

and in the `Fakes` docstring replace `` `fail` maps a part ("task", "mail link", "window link", "shortcuts") to the error it raises;`` with `` `fail` maps a part ("task", "mail link", "window link", "shortcuts", "desktop icon") to the error it raises;``.

In `agent/sla_agent/window.py`, in `AccountsScreen`:

Replace

```python
    ROWS = ("site", "edusoft", "blackboard", "outlook", "sync", "version")
    NAMES = {"site": "Website", "edusoft": "EduSoft", "blackboard": "Blackboard", "outlook": "Outlook",
             "sync": "Automatic sync", "version": "Version"}

    def __init__(self, app, state, notice=""):
        self.app, self.state = app, state
        self.sync_state = accounts.sync_task_state(app.tools)
```

with

```python
    ROWS = ("site", "edusoft", "blackboard", "outlook", "sync", "desktop", "version")
    NAMES = {"site": "Website", "edusoft": "EduSoft", "blackboard": "Blackboard", "outlook": "Outlook",
             "sync": "Automatic sync", "desktop": "Desktop icon", "version": "Version"}

    def __init__(self, app, state, notice=""):
        self.app, self.state = app, state
        self.sync_state = accounts.sync_task_state(app.tools)
        self.desktop = accounts.desktop_icon_on(app.tools)
```

In `describe`, replace

```python
            return __version__ + (f", updated by itself on {local_time(state.updated_at)}" if state.updated_at else "")
        return SYNC_STATES[self.sync_state]
```

with

```python
            return __version__ + (f", updated by itself on {local_time(state.updated_at)}" if state.updated_at else "")
        if row == "desktop":
            return "on" if self.desktop else "off"
        return SYNC_STATES[self.sync_state]
```

In `button_label`, replace

```python
        if row == "sync":
            return None if self.sync_state == "on" else "Repair"
```

with

```python
        if row == "sync":
            return None if self.sync_state == "on" else "Repair"
        if row == "desktop":
            return None if self.desktop else "Add"
```

In `open`, replace

```python
        if row == "sync":
            self.buttons["sync"].state(["disabled"])
            self.answers["sync"].set(CHECKING)
            self.app.run(lambda: accounts.turn_on_sync(self.app.tools),
                         lambda result: self.app.show(notice=mark(result)))
            return
```

with

```python
        if row in ("sync", "desktop"):  # Repair and Add: one press, no fields
            action = accounts.turn_on_sync if row == "sync" else accounts.add_desktop_icon
            self.buttons[row].state(["disabled"])
            self.answers[row].set(CHECKING)
            self.app.run(lambda: action(self.app.tools), lambda result: self.app.show(notice=mark(result)))
            return
```

- [ ] **Step 8: Run them to see them pass, then everything**

Run: `.\.venv\Scripts\python.exe -m pytest agent\tests\test_accounts.py agent\tests\test_window.py -v`
Expected: PASS.

Run: `.\.venv\Scripts\python.exe -m pytest`
Expected: all pass, 0 failed.

- [ ] **Step 9: Commit**

```powershell
git add agent/sla_agent/shortcuts.py agent/sla_agent/accounts.py agent/sla_agent/cli.py agent/sla_agent/window.py agent/tests/accounts_fakes.py agent/tests/test_shortcuts.py agent/tests/test_launcher.py agent/tests/test_cli.py agent/tests/test_accounts.py agent/tests/test_window.py
git commit -m @'
feat(agent): the Desktop icon only when the student asks; updates never bring back a deleted one; Accounts can add it

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
'@
```

---

### Task 2: The Outlook page (installed? signed in? which account?)

**Files:**
- Modify: `agent/sla_agent/outlook_reader.py` (new constants and two functions)
- Modify: `agent/tests/conftest.py` (registry fake, `outlook_reader` patched, `root` fixture moved here)
- Create: `agent/sla_agent/window_parts.py`
- Create: `agent/sla_agent/outlook_page.py`
- Modify: `agent/sla_agent/window.py` (imports; shared pieces moved out; the Outlook editor)
- Modify: `agent/sla_agent/accounts.py` (`Tools`), `agent/sla_agent/cli.py` (`tools()`), `agent/tests/accounts_fakes.py`
- Test: `agent/tests/test_outlook_reader.py`, `agent/tests/test_outlook_page.py` (new), `agent/tests/test_window.py`

**Interfaces:**
- Consumes (Task 1): `accounts.Tools` with `add_desktop_shortcut` and `has_desktop_shortcut`.
- Produces:
  - **`outlook_reader`:** `MISSING`, `NOT_SIGNED_IN` and `SIGNED_IN` (strings), `OUTLOOK_APPLICATION`, `OUTLOOK_PROFILES`, `classic_outlook() -> str`, and `start_classic_outlook()`, which raises `OSError`.
  - **`accounts.Tools`:** `outlook_state: () -> str` and `start_outlook: ()`.
  - **`window_parts`:** `CHECKING`, `WRAP`, `attempt(work)`, `run_at_once(work, done)`, `run_in_background(root)`, `mark(result)`, `heading`, `section`, `field(parent, label, variable, row, secret=False) -> ttk.Entry`, `answer_line`, and `text_line(parent, text, row)`.
  - **`outlook_page`:** `OutlookPage(app, parent, variable, skippable, changed=lambda: None)`.
    - What it holds: `.frame`, `.state`, `.lines: list[str]`, `.buttons: dict[str, ttk.Button]`, `.box`, `.note` and `.ready`.
    - What it does: `.look()`.
    - The module also has the text constants and `first_choice(found, current="")`.
  - **`accounts_fakes.Fakes`:** `.outlook`, which defaults to `SIGNED_IN`; an exception instance there is raised instead. The part `"start outlook"`.
  - **`conftest`:** the fixture `root`.

- [ ] **Step 1: Write the failing test for reading the registry**

At the end of `agent/tests/test_outlook_reader.py`, add:

```python
# ---- is classic Outlook installed, and has anyone signed in? (the setup's Outlook page) ----------------------------


def test_classic_outlook_reads_only_the_registry(isolated_agent):
    registry = isolated_agent.registry
    assert outlook_reader.classic_outlook() == outlook_reader.MISSING  # no Outlook.Application: not installed

    registry.CreateKey(registry.HKEY_CLASSES_ROOT, outlook_reader.OUTLOOK_APPLICATION)
    assert outlook_reader.classic_outlook() == outlook_reader.NOT_SIGNED_IN

    registry.CreateKey(registry.HKEY_CURRENT_USER, outlook_reader.OUTLOOK_PROFILES)
    assert outlook_reader.classic_outlook() == outlook_reader.NOT_SIGNED_IN  # the key, but no profile in it

    registry.CreateKey(registry.HKEY_CURRENT_USER, outlook_reader.OUTLOOK_PROFILES + r"\Outlook")
    assert outlook_reader.classic_outlook() == outlook_reader.SIGNED_IN
```

- [ ] **Step 2: Run it to see it fail**

Run: `.\.venv\Scripts\python.exe -m pytest agent\tests\test_outlook_reader.py -v`
Expected: FAIL. The new test fails with `AttributeError: module 'sla_agent.outlook_reader' has no attribute 'classic_outlook'` (or `'MemoryRegistry' object has no attribute 'HKEY_CLASSES_ROOT'`).

- [ ] **Step 3: Read the registry, in outlook_reader.py and the registry fake**

In `agent/sla_agent/outlook_reader.py`:

Replace

```python
import hashlib
import logging
import re
```

with

```python
import hashlib
import logging
import os
import re
```

After the line `LINK = re.compile(r"sla-mail:([0-9A-F]{2,512})/?")` add:

```python
MISSING, NOT_SIGNED_IN, SIGNED_IN = "missing", "not_signed_in", "signed_in"
OUTLOOK_APPLICATION = r"Outlook.Application\CLSID"  # registered by classic Outlook only, not by the new Outlook app
OUTLOOK_PROFILES = r"Software\Microsoft\Office\16.0\Outlook\Profiles"  # 16.0: every Office from 2016 on
```

After the function `running_outlook`, add:

```python
def _winreg():
    import winreg

    return winreg


def classic_outlook():
    """MISSING, NOT_SIGNED_IN or SIGNED_IN (spec 2026-10-02-easy-install-design.md, 4), from the registry only: this
    never starts Outlook. Signed in means this Windows user has an Outlook profile, which classic Outlook makes the
    first time someone signs in to it."""
    try:
        registry = _winreg()
    except ImportError:  # not Windows
        return MISSING
    try:
        with registry.OpenKey(registry.HKEY_CLASSES_ROOT, OUTLOOK_APPLICATION):
            pass
    except OSError:
        return MISSING
    try:
        with registry.OpenKey(registry.HKEY_CURRENT_USER, OUTLOOK_PROFILES) as profiles:
            registry.EnumKey(profiles, 0)  # the first profile; OSError when there is none
    except OSError:
        return NOT_SIGNED_IN
    return SIGNED_IN


def start_classic_outlook():
    """Open Outlook (classic) for the student to sign in. Windows finds outlook.exe through App Paths; OSError when it
    can't."""
    os.startfile("outlook.exe")
```

In `agent/tests/conftest.py`:

In `class MemoryRegistry`, replace

```python
    HKEY_CURRENT_USER = "HKCU"
    REG_SZ = 1
```

with

```python
    HKEY_CLASSES_ROOT = "HKCR"
    HKEY_CURRENT_USER = "HKCU"
    REG_SZ = 1
```

and after its `DeleteKey` method add:

```python
    def EnumKey(self, key, index):
        """The name of `key`'s subkey number `index`, as winreg's; OSError past the last one."""
        root, path = key.path
        names = sorted({other[len(path) + 1:].split("\\")[0] for other_root, other in self.keys
                        if other_root == root and other.startswith(path + "\\")})
        if index >= len(names):
            raise OSError("No more data is available")
        return names[index]
```

In the fixture `isolated_agent`, replace

```python
    from sla_agent import mail_link, shortcuts
```

with

```python
    from sla_agent import mail_link, outlook_reader, shortcuts
```

and replace

```python
    monkeypatch.setattr(mail_link, "_winreg", lambda: registry)
```

with

```python
    monkeypatch.setattr(mail_link, "_winreg", lambda: registry)
    monkeypatch.setattr(outlook_reader, "_winreg", lambda: registry)
```

- [ ] **Step 4: Run it to see it pass**

Run: `.\.venv\Scripts\python.exe -m pytest agent\tests\test_outlook_reader.py -v`
Expected: PASS, including `test_the_reader_only_reads`. The new code calls none of the names it forbids.

- [ ] **Step 5: Move what the screens share into window_parts.py (no change in behaviour)**

Create `agent/sla_agent/window_parts.py`:

```python
"""What the School-Life-Assistant window's screens share (window.py, setup_steps.py, outlook_page.py): running a
check off Tk's thread, and the lines and fields every screen is made of.

Each check runs on a worker thread so the window never freezes, and its answer comes back through Tk's event loop,
since Tk may only be used from its own thread (tests pass `run=run_at_once`)."""

import logging
import queue
import threading
from tkinter import ttk

from sla_agent.accounts import Result

log = logging.getLogger(__name__)

CHECKING = "Checking…"
WRAP = 500  # pixels: longer lines wrap


def attempt(work):
    """work(), or a Result saying something went wrong; the details go to agent.log (secrets scrubbed)."""
    try:
        return work()
    except Exception as error:  # anything unexpected is shown on screen, never a crash
        log.exception("Accounts window: unexpected error")
        return Result(False, f"Something went wrong ({error.__class__.__name__}). Nothing was saved.")


def run_at_once(work, done):
    done(attempt(work))


def run_in_background(root):
    """A runner that does `work` on a worker thread and calls `done` with its answer on Tk's thread. The thread is
    not a daemon: closing the window during a check lets the check finish and save."""

    def run(work, done):
        answers = queue.Queue(maxsize=1)
        threading.Thread(target=lambda: answers.put(attempt(work)), name="accounts-check").start()

        def collect():
            try:
                answer = answers.get_nowait()
            except queue.Empty:
                root.after(100, collect)
                return
            done(answer)

        root.after(100, collect)

    return run


def mark(result):
    return ("✓ " if result.ok else "✗ ") + "\n".join((result.message, *result.notes))


def heading(parent, text, row):
    ttk.Label(parent, text=text, font=("Segoe UI", 14, "bold")).grid(
        row=row, column=0, columnspan=3, sticky="w", pady=(0, 8))


def section(parent, text, row):
    ttk.Label(parent, text=text, font=("Segoe UI", 10, "bold")).grid(
        row=row, column=0, columnspan=3, sticky="w", pady=(12, 2))


def field(parent, label, variable, row, secret=False):
    """A label and its box; returns the box."""
    ttk.Label(parent, text=label).grid(row=row, column=0, sticky="w", padx=(0, 8), pady=2)
    box = ttk.Entry(parent, textvariable=variable, show="•" if secret else "", width=40)
    box.grid(row=row, column=1, sticky="ew", pady=2)
    return box


def answer_line(parent, variable, row):
    ttk.Label(parent, textvariable=variable, wraplength=WRAP, justify="left").grid(
        row=row, column=0, columnspan=3, sticky="w")


def text_line(parent, text, row):
    """One line of a guide."""
    ttk.Label(parent, text=text, wraplength=WRAP, justify="left").grid(
        row=row, column=0, columnspan=3, sticky="w", pady=(0, 4))
```

In `agent/sla_agent/window.py`:

1. Replace the import block

```python
import logging
import queue
import threading
import tkinter as tk
import webbrowser
from datetime import datetime
from tkinter import ttk

from sla_agent import __version__, accounts, launcher
from sla_agent.accounts import Result, SetupForm
from sla_agent.log import protect
from sla_agent.state import load_state
```

with

```python
import logging
import tkinter as tk
import webbrowser
from datetime import datetime
from tkinter import ttk

from sla_agent import __version__, accounts, launcher
from sla_agent.accounts import Result, SetupForm
from sla_agent.log import protect
from sla_agent.state import load_state
from sla_agent.window_parts import CHECKING, answer_line, field, heading, mark, run_in_background, section
```

2. Delete the line `CHECKING = "Checking…"`; the constant now comes from `window_parts`.
3. Delete the whole `# ---- running checks ----` section (`attempt`, `run_at_once`, `run_in_background`). Also delete these functions from the `# ---- small pieces ----` section: `mark`, `heading`, `section`, `field`, `answer_line`. Keep `local_time` and `default_address` under the `# ---- small pieces ----` heading. They now live in `window_parts.py`, unchanged except that `field` returns its box.

In `agent/tests/conftest.py`, add at the end:

```python
@pytest.fixture
def root(request):
    """A real Tk window, hidden, for the window's tests. While pytest redirects the output file descriptors, Tcl on
    Windows now and then fails to read its own library files ("couldn't read file .../tk8.6/entry.tcl"), so Tk starts
    with that redirection paused."""
    import tkinter as tk

    capture = request.config.pluginmanager.getplugin("capturemanager")
    try:
        with capture.global_and_fixture_disabled():
            made = tk.Tk()
    except tk.TclError as error:  # a machine without a desktop
        pytest.skip(f"Tk can't start here: {error}")
    made.withdraw()
    yield made
    made.destroy()
```

In `agent/tests/test_window.py`:
- Delete its own `root` fixture (the one that starts with `def root(request):`) and the line `import tkinter as tk`. The fixture is in `conftest.py` now.
- Replace `from sla_agent import __version__, accounts, credentials, launcher, window` with `from sla_agent import __version__, accounts, credentials, launcher, window, window_parts`.
- Replace `def open_window(root, fakes, run=window.run_at_once):` with `def open_window(root, fakes, run=window_parts.run_at_once):`.
- In `test_while_checking_the_button_is_off_and_a_late_outlook_list_is_ignored`, replace both `done(window.attempt(work))` with `done(window_parts.attempt(work))`.

Run: `.\.venv\Scripts\python.exe -m pytest agent\tests\test_window.py -v`
Expected: PASS. Nothing changed for the student.

- [ ] **Step 6: Write the failing tests for the Outlook page**

Create `agent/tests/test_outlook_page.py`:

```python
"""The Outlook page (outlook_page.py), built for real but hidden, with fakes behind it (accounts_fakes.Fakes): what it
shows for each answer the registry gives, and that only an Outlook someone signed in to is asked for its accounts."""

import tkinter as tk

import pytest

from agent.tests.accounts_fakes import ME, Fakes
from sla_agent import outlook_page, window_parts
from sla_agent.errors import OutlookBlocked
from sla_agent.outlook_page import OutlookPage, first_choice
from sla_agent.outlook_reader import MISSING, NOT_SIGNED_IN, SIGNED_IN

GMAIL = "me@gmail.com"


class Host:
    """What the page needs from the window: the tools, and how to run a check (at once, unless a test holds it)."""

    def __init__(self, fakes, run=window_parts.run_at_once):
        self.tools, self.run = fakes.tools(), run


@pytest.fixture
def fakes():
    return Fakes()


def show(root, fakes, skippable=True, current="", run=window_parts.run_at_once):
    """The page in `root`, its account variable, and the account it held each time it said it changed."""
    changes = []
    variable = tk.StringVar(root, current)
    page = OutlookPage(Host(fakes, run), root, variable, skippable, changed=lambda: changes.append(variable.get()))
    return page, variable, changes


def test_without_classic_outlook_it_shows_how_to_install_it_and_never_asks_outlook(root, fakes):
    fakes.outlook = MISSING

    page, variable, _ = show(root, fakes)

    assert page.state == MISSING
    assert page.lines == [outlook_page.MISSING_INTRO, *outlook_page.MISSING_STEPS, outlook_page.LATER]
    assert list(page.buttons) == ["Install Office", "Other way", "Check again"]
    assert not page.ready and variable.get() == ""
    assert fakes.looks == []


def test_accounts_has_no_skip_so_its_install_guide_doesnt_offer_one(root, fakes):
    fakes.outlook = MISSING

    page, _, _ = show(root, fakes, skippable=False)

    assert outlook_page.LATER not in page.lines


def test_install_office_and_other_way_open_microsofts_pages(root, fakes, monkeypatch):
    opened = []
    monkeypatch.setattr(outlook_page.webbrowser, "open", opened.append)
    fakes.outlook = MISSING
    page, _, _ = show(root, fakes)

    page.buttons["Install Office"].invoke()
    page.buttons["Other way"].invoke()

    assert opened == [outlook_page.INSTALL_OFFICE, outlook_page.OTHER_WAY]


def test_installed_but_nobody_signed_in_shows_how_to_sign_in_and_open_outlook_starts_it(root, fakes):
    fakes.outlook = NOT_SIGNED_IN
    page, _, _ = show(root, fakes)

    assert page.lines == list(outlook_page.SIGN_IN_STEPS)
    assert list(page.buttons) == ["Open Outlook", "Check again"]
    assert fakes.looks == []

    page.buttons["Open Outlook"].invoke()

    assert "start outlook" in fakes.done


def test_open_outlook_that_fails_says_where_to_find_it(root, fakes):
    fakes.outlook = NOT_SIGNED_IN
    fakes.fail = {"start outlook": FileNotFoundError("outlook.exe")}
    page, _, _ = show(root, fakes)

    page.buttons["Open Outlook"].invoke()

    assert page.note.get() == "Couldn't open Outlook (classic) (outlook.exe). Open it from the Start menu."


def test_signed_in_it_lists_the_accounts_with_the_iu_one_chosen(root, fakes):
    fakes.found = [GMAIL, ME]

    page, variable, _ = show(root, fakes)

    assert page.state == SIGNED_IN
    assert page.lines == [outlook_page.CHOOSE]
    assert tuple(page.box.cget("values")) == (GMAIL, ME)
    assert variable.get() == ME and page.ready
    assert fakes.looks == ["start"]


def test_the_account_chosen_before_stays_chosen(root, fakes):
    fakes.found = [GMAIL, ME]

    _, variable, _ = show(root, fakes, current=GMAIL)

    assert variable.get() == GMAIL


def test_without_an_iu_account_the_first_is_chosen():
    assert first_choice([GMAIL, "me@outlook.com"]) == GMAIL
    assert first_choice([GMAIL, ME.upper()]) == ME.upper()


@pytest.mark.parametrize("found, reason", [
    ([], "Classic Outlook has no account yet."),
    (OutlookBlocked("Outlook didn't answer within 3 minutes (a security prompt may be waiting)."),
     "Outlook didn't answer within 3 minutes (a security prompt may be waiting)."),
], ids=["no-account", "no-answer"])
def test_signed_in_without_an_account_it_shows_the_sign_in_guide_with_the_reason(root, fakes, found, reason):
    fakes.found = found

    page, variable, _ = show(root, fakes)

    assert page.state == NOT_SIGNED_IN
    assert page.lines == [reason, *outlook_page.SIGN_IN_STEPS]
    assert variable.get() == "" and not page.ready


def test_check_again_follows_the_student_from_install_to_sign_in_to_choosing(root, fakes):
    fakes.outlook = MISSING
    page, variable, changes = show(root, fakes)

    fakes.outlook = NOT_SIGNED_IN
    page.buttons["Check again"].invoke()
    assert page.state == NOT_SIGNED_IN

    fakes.outlook = SIGNED_IN
    page.buttons["Check again"].invoke()
    assert page.state == SIGNED_IN and variable.get() == ME
    assert changes[-1] == ME


def test_while_looking_it_isnt_ready_and_an_answer_for_a_closed_page_is_dropped(root, fakes):
    held = []
    page, variable, _ = show(root, fakes, run=lambda work, done: held.append((work, done)))

    assert page.lines == [outlook_page.LOOKING] and not page.ready
    page.frame.destroy()  # the student left the page
    work, done = held.pop()
    done(window_parts.attempt(work))

    assert variable.get() == ""


def test_something_unexpected_while_looking_shows_the_sign_in_guide_with_what_happened(root, fakes):
    fakes.outlook = RuntimeError("a bug")

    page, _, _ = show(root, fakes)

    assert page.state == NOT_SIGNED_IN
    assert page.lines[0] == "Something went wrong (RuntimeError). Nothing was saved."
```

- [ ] **Step 7: Run them to see them fail**

Run: `.\.venv\Scripts\python.exe -m pytest agent\tests\test_outlook_page.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'sla_agent.outlook_page'`.

- [ ] **Step 8: Write the Outlook page, and give the tools what it needs**

In `agent/sla_agent/accounts.py`, in `Tools`, replace

```python
    find_open_outlook_accounts: Callable  # the same, asking only an Outlook that is already open
```

with

```python
    find_open_outlook_accounts: Callable  # the same, asking only an Outlook that is already open
    outlook_state: Callable  # () -> outlook_reader.MISSING, NOT_SIGNED_IN or SIGNED_IN, from the registry
    start_outlook: Callable  # () -> opens Outlook (classic) for the student to sign in; raises OSError
```

In `agent/sla_agent/cli.py`:
- In the `from sla_agent.outlook_reader import (...)` block, add the two names `classic_outlook,` (after `accounts as outlook_addresses,`) and `start_classic_outlook,` (after `running_outlook,`).
- In `tools()`, replace

```python
        find_outlook_accounts=find_outlook_accounts, find_open_outlook_accounts=find_open_outlook_accounts,
```

with

```python
        find_outlook_accounts=find_outlook_accounts, find_open_outlook_accounts=find_open_outlook_accounts,
        outlook_state=classic_outlook, start_outlook=start_classic_outlook,
```

In `agent/tests/accounts_fakes.py`:
- After `from sla_agent import accounts, credentials`, add `from sla_agent.outlook_reader import SIGNED_IN`.
- Replace the whole `Fakes` docstring with:

```python
    """`found` is Outlook's account list (an exception instance is raised instead); `outlook` is what the registry
    says about Outlook (SIGNED_IN unless a test changes it; an exception instance is raised instead); `program` is
    what the scheduled task starts; `fail` maps a part ("task", "mail link", "window link", "shortcuts",
    "desktop icon", "start outlook") to the error it raises; `done` lists the parts that ran; `looks` lists how
    Outlook itself was asked: "open" (only an open Outlook) or "start"; `me` is the program this agent is: what
    turn_on_sync points things at."""
```

- In `__init__`, after `self.found = [ME]` add `self.outlook = SIGNED_IN`.
- In `tools()`, replace

```python
            make_blackboard=lambda: self.blackboard, find_outlook_accounts=self._find, find_open_outlook_accounts=self._find_open, program=lambda: self.me,
```

with

```python
            make_blackboard=lambda: self.blackboard, find_outlook_accounts=self._find,
            find_open_outlook_accounts=self._find_open, outlook_state=self._outlook_state,
            start_outlook=self._part("start outlook"), program=lambda: self.me,
```

- After the method `_find_open`, add:

```python
    def _outlook_state(self):
        if isinstance(self.outlook, Exception):
            raise self.outlook
        return self.outlook
```

Create `agent/sla_agent/outlook_page.py`:

```python
"""The Outlook page (spec 2026-10-02-easy-install-design.md, 4), shown by the setup's step 4 and by Accounts →
Outlook. It reads the registry first, which never starts Outlook: no Outlook (classic) gets the install guide (A);
Outlook nobody signed in to gets the sign-in guide (B); only then (C) is Outlook asked for its accounts, which may
start a hidden Outlook for a moment."""

import tkinter as tk
import webbrowser
from tkinter import ttk

from sla_agent import accounts
from sla_agent.accounts import Result
from sla_agent.outlook_reader import MISSING, NOT_SIGNED_IN, SIGNED_IN
from sla_agent.window_parts import answer_line, text_line

STUDENT_DOMAIN = "@student.hcmiu.edu.vn"
INSTALL_OFFICE = "https://www.microsoft365.com/"
OTHER_WAY = ("https://support.microsoft.com/en-us/office/"
             "install-or-reinstall-classic-outlook-on-a-windows-pc-5c94902b-31a5-4274-abb0-b07f4661edf5")
LOOKING = "Looking for Outlook (classic)…"
MISSING_INTRO = ("School-Life-Assistant reads your IU mail through Outlook (classic). The new Outlook app can't be "
                 "used. This laptop doesn't have Outlook (classic) yet:")
MISSING_STEPS = (
    "1. Press Install Office: microsoft365.com opens. Sign in with your IU email (it ends with "
    "@student.hcmiu.edu.vn).",
    "2. Press Install apps, then Microsoft 365 apps. Open the downloaded file and wait until it finishes "
    "(10–20 minutes). No Install apps button? Your IU account has only the web version of Office: press Other way "
    "for Microsoft's own Outlook (classic) download (it may say it isn't licensed), or skip Outlook.",
    "3. Open Outlook (classic) from the Start menu and sign in with your IU email.",
    "4. Come back and press Check again.",
)
LATER = ("This takes a while: you can press Skip now and set up Outlook later in School-Life-Assistant Accounts "
         "(Start menu).")
SIGN_IN_STEPS = (
    "1. Press Open Outlook and sign in with your IU email (it ends with @student.hcmiu.edu.vn). If the new Outlook "
    "opens instead, turn off its New Outlook switch (top right) to get Outlook (classic).",
    "2. Wait until it says \"All folders are up to date\", then press Check again.",
)
CHOOSE = "Read the Inbox of:"
NO_START = "Couldn't open Outlook (classic) ({error}). Open it from the Start menu."
BUTTONS = {MISSING: ("Install Office", "Other way", "Check again"), NOT_SIGNED_IN: ("Open Outlook", "Check again"),
           SIGNED_IN: ("Check again",)}


def first_choice(found, current=""):
    """The account chosen first: the one chosen before, else the first IU student account, else the first."""
    if current in found:
        return current
    return next((address for address in found if address.lower().endswith(STUDENT_DOMAIN)), found[0])


class OutlookPage:
    """A frame (`frame`, placed by the caller) that looks when it is made and on Check again. `variable` holds the
    chosen account in C and is empty otherwise; `changed()` is called whenever that may have changed, so the caller
    can turn its Next or Check and save on and off (`ready`). `skippable`: the setup's step 4, which offers Skip."""

    def __init__(self, app, parent, variable, skippable, changed=lambda: None):
        self.app, self.variable, self.skippable, self.changed = app, variable, skippable, changed
        self.current = variable.get()  # the account chosen before (Accounts → Change)
        self.frame = ttk.Frame(parent)
        self.frame.columnconfigure(0, weight=1)
        self.note = tk.StringVar(self.frame)
        answer_line(self.frame, self.note, 1)
        self.state, self.lines, self.buttons, self.box, self.body = None, [], {}, None, None
        self.look()

    @property
    def ready(self):
        return self.state == SIGNED_IN and bool(self.variable.get())

    def look(self):
        """The registry, then (signed in) Outlook's accounts, on a worker thread; Check again does it again."""
        self.state = None
        self.variable.set("")
        self.note.set("")
        self.show([LOOKING], ())
        self.changed()
        self.app.run(self.find, self.found)

    def find(self):
        state = self.app.tools.outlook_state()
        if state != SIGNED_IN:
            return state, [], None
        found, problem = accounts.outlook_accounts(self.app.tools)
        return state, found, problem

    def found(self, answer):
        if not self.frame.winfo_exists():  # the student left the page meanwhile
            return
        state, found, problem = (NOT_SIGNED_IN, [], answer.message) if isinstance(answer, Result) else answer
        if state == SIGNED_IN and not found:  # no account after all, or Outlook didn't answer
            state = NOT_SIGNED_IN
        self.state = state
        if state == MISSING:
            self.show([MISSING_INTRO, *MISSING_STEPS, *([LATER] if self.skippable else [])], BUTTONS[MISSING])
        elif state == NOT_SIGNED_IN:
            self.show([*([problem] if problem else []), *SIGN_IN_STEPS], BUTTONS[NOT_SIGNED_IN])
        else:
            self.show([CHOOSE], BUTTONS[SIGNED_IN], found)
        self.changed()

    def show(self, lines, buttons, found=()):
        if self.body is not None:
            self.body.destroy()
        self.body = ttk.Frame(self.frame)
        self.body.grid(row=0, column=0, sticky="ew")
        self.lines, self.box = list(lines), None
        for row, line in enumerate(lines):
            text_line(self.body, line, row)
        if found:
            self.box = ttk.Combobox(self.body, textvariable=self.variable, state="readonly", values=list(found),
                                    width=38)
            self.box.grid(row=len(lines), column=0, sticky="w")
            self.box.bind("<<ComboboxSelected>>", lambda event: self.changed())
            self.variable.set(first_choice(found, self.current))
        bar = ttk.Frame(self.body)
        bar.grid(row=len(lines) + 1, column=0, sticky="w", pady=(8, 0))
        commands = {"Install Office": lambda: webbrowser.open(INSTALL_OFFICE),
                    "Other way": lambda: webbrowser.open(OTHER_WAY),
                    "Open Outlook": self.start_outlook, "Check again": self.look}
        self.buttons = {}
        for column, name in enumerate(buttons):
            self.buttons[name] = ttk.Button(bar, text=name, command=commands[name])
            self.buttons[name].grid(row=0, column=column, padx=(0, 8))

    def start_outlook(self):
        try:
            self.app.tools.start_outlook()
        except OSError as error:
            self.note.set(NO_START.format(error=error))
```

- [ ] **Step 9: Run them to see them pass**

Run: `.\.venv\Scripts\python.exe -m pytest agent\tests\test_outlook_page.py -v`
Expected: PASS.

- [ ] **Step 10: Write the failing test for Accounts → Outlook**

In `agent/tests/test_window.py`, replace `from sla_agent import __version__, accounts, credentials, launcher, window, window_parts` with `from sla_agent import __version__, accounts, credentials, launcher, outlook_page, window, window_parts`, and add `from sla_agent.outlook_reader import MISSING` after the `from sla_agent.errors import …` line. Then, after `test_outlook_change_picks_from_the_accounts_found`, add:

```python
def test_accounts_outlook_set_up_guides_the_install_when_classic_outlook_is_missing(root, fakes):
    set_up()
    fakes.outlook = MISSING
    app = open_window(root, fakes)

    app.screen.open("outlook")

    editor = app.screen.editor
    assert editor.outlook.lines[0] == outlook_page.MISSING_INTRO
    assert outlook_page.LATER not in editor.outlook.lines  # Accounts has Cancel, not Skip
    editor.save()
    assert app.screen.answers["outlook"].get() == "✗ Choose an account first."
```

- [ ] **Step 11: Run it to see it fail**

Run: `.\.venv\Scripts\python.exe -m pytest agent\tests\test_window.py -v`
Expected: FAIL with `AttributeError: 'Editor' object has no attribute 'outlook'`.

- [ ] **Step 12: Accounts → Outlook shows the Outlook page**

In `agent/sla_agent/window.py`:
- After `from sla_agent.log import protect` add `from sla_agent.outlook_page import OutlookPage`.
- In `Editor.__init__`, replace

```python
        if row == "outlook":
            self.values["outlook"] = tk.StringVar(self.frame, state.outlook_account or "")
            OutlookPicker(screen.app, self.frame, self.values["outlook"], 0, allow_none=False)
```

with

```python
        if row == "outlook":
            self.values["outlook"] = tk.StringVar(self.frame, state.outlook_account or "")
            self.outlook = OutlookPage(screen.app, self.frame, self.values["outlook"], skippable=False)
            self.outlook.frame.grid(row=0, column=0, columnspan=3, sticky="ew")
```

(The one-page form keeps its `OutlookPicker` until Task 3 removes both.)

- [ ] **Step 13: Run them to see them pass, then everything**

Run: `.\.venv\Scripts\python.exe -m pytest agent\tests\test_window.py agent\tests\test_outlook_page.py -v`
Expected: PASS. `test_outlook_change_picks_from_the_accounts_found` still passes: the page asks Outlook and picks `ME`.

Run: `.\.venv\Scripts\python.exe -m pytest`
Expected: all pass, 0 failed.

- [ ] **Step 14: Commit**

```powershell
git add agent/sla_agent/outlook_reader.py agent/sla_agent/window_parts.py agent/sla_agent/outlook_page.py agent/sla_agent/window.py agent/sla_agent/accounts.py agent/sla_agent/cli.py agent/tests/conftest.py agent/tests/accounts_fakes.py agent/tests/test_outlook_reader.py agent/tests/test_outlook_page.py agent/tests/test_window.py
git commit -m @'
feat(agent): the Outlook page: whether Outlook (classic) is installed and signed in, from the registry, with install and sign-in guides; Accounts → Outlook uses it

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
'@
```

---

### Task 3: The setup pages: the website key, EduSoft, and All set

**Files:**
- Modify: `agent/sla_agent/accounts.py` (`SITE_ACCEPTED`, `is_device_key`, `save_and_turn_on_sync`; `first_setup`, `SetupForm` and `find_open_outlook_accounts` removed)
- Create: `agent/sla_agent/setup_steps.py`
- Modify: `agent/sla_agent/window.py` (whole file: the one-page form removed)
- Modify: `agent/sla_agent/window_parts.py` (`section` removed: only the old form used it)
- Modify: `agent/sla_agent/cli.py` (`find_open_outlook_accounts` removed)
- Modify: `agent/sla_agent/selfcheck.py`
- Modify: `agent/tests/accounts_fakes.py` (`KEY`; `find_open_outlook_accounts` removed)
- Test: `agent/tests/test_accounts.py`, `agent/tests/test_setup_steps.py` (new), `agent/tests/test_window.py` (whole file), `agent/tests/test_cli.py`, `agent/tests/test_selfcheck.py`

**Interfaces:**
- Consumes:
  - Task 1: `accounts.add_desktop_icon`, `accounts.desktop_icon_on`, and `accounts.DESKTOP_ICON_ADDED`.
  - Task 2: `window_parts.*` and `outlook_page.OutlookPage`; `fakes.outlook`; the `root` fixture.
- Produces:
  - **`accounts`:**
    - `SITE_ACCEPTED = "The website accepted this key."` and `DEVICE_KEY` (a regex).
    - `is_device_key(text) -> bool`.
    - `save_and_turn_on_sync(state, address, key, student_id, password, tools) -> dict[str, Result]`, with keys `"edusoft"`, plus `"sync"` once saved.
    - `outlook_accounts(tools)` (no `start`).
  - **`setup_steps`:**
    - Constants: `LOCAL_ADDRESS`, `ONLINE_ADDRESS`, `SITE_STEPS`, `NOT_A_KEY`, `EDUSOFT_HINT`, `ALL_SET`, `SYNC_REPAIR`, `SKIPPED`, `DESKTOP_QUESTION`.
    - Functions: `default_address()` and `read_clipboard(root)`.
    - **`SetupSteps(app, state)`** holds `.values`, `.accepted`, `.checking`, `.address_open`, `.results` and `.page`, and has `.show(page_class)`, `.after(page_class)`, `.key_changed()` and `.came_forward()`.
    - **`Page`** has `.header()`, `.answer`, `.current()`, `.busy(*buttons)` and `.free(*buttons)`.
    - The page classes:
      - `SitePage`: `.open_button`, `.paste_button`, `.change_button`, `.next_button`, `.key_changed()`, `.fill_from_clipboard()`.
      - `EdusoftPage`: `.back_button`, `.next_button`.
      - `DonePage`: `.lines`, `.no_button`, `.yes_button`.
    - `STEPS = (SitePage, EdusoftPage)`.
  - **`selfcheck`:** `setup_window()`.

- [ ] **Step 1: Write the failing tests for the device key and step 2's save**

In `agent/tests/accounts_fakes.py`, replace

```python
KEY = "sla_device-key-0123456789"
```

with

```python
KEY = "sla_device-key-0123456789-abcdefghijklmnopqrstu"  # a real key's shape: sla_ and 43 characters
```

In `agent/tests/test_accounts.py`, replace everything from the top of the file down to (not including) the line `# ---- changes ----…` with:

```python
"""accounts.py: checking and saving this laptop's accounts, with fakes for everything it talks to."""

import logging

import pytest

from agent.tests.accounts_fakes import (
    BB_PASSWORD,
    BB_USER,
    KEY,
    ME,
    PASSWORD,
    SERVER,
    STUDENT,
    Fakes,
    set_up,
)
from sla_agent import accounts, credentials, launcher
from sla_agent.errors import BadCredentials, DeviceKeyRejected, ExtraVerification, OutlookNotSetUp
from sla_agent.log import setup_logging
from sla_agent.scheduler import SchedulerError
from sla_agent.state import State, load_state, save_state


@pytest.fixture
def fakes():
    return Fakes()


# ---- the website and the device key --------------------------------------------------


def test_check_site_says_when_the_website_accepts_the_key(fakes):
    assert accounts.check_site(SERVER, KEY, fakes.tools()) == accounts.Result(True, accounts.SITE_ACCEPTED)
    assert accounts.SITE_ACCEPTED == "The website accepted this key."


def test_check_site_refuses_a_plain_http_address_without_contacting_it(fakes):
    result = accounts.check_site("http://sla.example.com", KEY, fakes.tools())

    assert not result.ok
    assert "https://" in result.message
    assert fakes.servers == []


@pytest.mark.parametrize("text, shaped", [
    (KEY, True), (f"  {KEY}\r\n", True), ("sla_short", False), ("My laptop", False), ("", False), (None, False),
    (KEY + "x", False), ("SLA_" + KEY[4:], False), (KEY[:-1] + "!", False),
])
def test_is_device_key_takes_only_a_keys_exact_shape(text, shaped):
    assert accounts.is_device_key(text) is shaped


# ---- the setup's step 2 -------------------------------------------------------------


def test_step_2_checks_edusoft_then_saves_the_key_and_edusoft_and_turns_on_sync(fakes):
    results = accounts.save_and_turn_on_sync(State(), SERVER, KEY, STUDENT, PASSWORD, fakes.tools())

    assert list(results) == ["edusoft", "sync"]
    assert results["edusoft"] == accounts.Result(True, accounts.SAVED)
    assert results["sync"].ok
    assert credentials.load_device_key(SERVER) == KEY
    assert credentials.load_edusoft(STUDENT) == PASSWORD
    assert (load_state().server_url, load_state().student_id) == (SERVER, STUDENT)
    assert {"task", "mail link", "window link", "shortcuts"} <= set(fakes.done)
    assert "desktop icon" not in fakes.done


@pytest.mark.parametrize("error", [BadCredentials("rejected"), ExtraVerification("captcha")],
                         ids=["wrong-password", "captcha"])
def test_step_2_saves_nothing_when_edusoft_refuses(fakes, isolated_agent, error):
    fakes.edusoft.login_error = error

    results = accounts.save_and_turn_on_sync(State(), SERVER, KEY, STUDENT, PASSWORD, fakes.tools())

    assert list(results) == ["edusoft"]
    assert "Nothing was saved" in results["edusoft"].message
    assert isolated_agent.entries == {}
    assert load_state() == State()
    assert fakes.done == []


def test_step_2_keeps_edusoft_saved_when_sync_cannot_be_turned_on(fakes):
    fakes.fail = {"task": SchedulerError("Couldn't create the scheduled task: Access is denied.")}

    results = accounts.save_and_turn_on_sync(State(), SERVER, KEY, STUDENT, PASSWORD, fakes.tools())

    assert results["edusoft"].ok
    assert not results["sync"].ok
    assert "Access is denied" in results["sync"].message
    assert credentials.load_edusoft(STUDENT) == PASSWORD


def test_an_unexpected_error_after_saving_stops_only_turning_on_sync(fakes):
    fakes.fail = {"task": RuntimeError("a bug")}

    results = accounts.save_and_turn_on_sync(State(), SERVER, KEY, STUDENT, PASSWORD, fakes.tools())

    assert results["edusoft"].ok
    assert results["sync"].message.startswith("Something went wrong (RuntimeError)")
    assert credentials.load_edusoft(STUDENT) == PASSWORD


def test_half_a_blackboard_login_is_refused(fakes):
    result = accounts.change_blackboard(set_up(), BB_USER, "", fakes.tools())

    assert result.message == "Blackboard username and password are both needed. Nothing was saved."
    assert fakes.blackboard.logins == []


```

Further down in the same file:
- Replace the whole test `test_no_password_or_key_reaches_the_log_file` with:

```python
def test_no_password_or_key_reaches_the_log_file(fakes, tmp_path):
    log_path = setup_logging(tmp_path / "logs")
    fakes.fail = {"shortcuts": RuntimeError(f"failed for {PASSWORD} {KEY}")}

    accounts.save_and_turn_on_sync(State(), SERVER, KEY, STUDENT, PASSWORD, fakes.tools())

    for handler in logging.getLogger().handlers[:]:
        if str(tmp_path) in getattr(handler, "baseFilename", ""):
            handler.flush()
            logging.getLogger().removeHandler(handler)
            handler.close()
    text = log_path.read_text(encoding="utf-8")
    assert "***" in text
    assert PASSWORD not in text and KEY not in text
```

- Delete the test `test_an_unexpected_error_after_saving_stops_only_that_step`; the new step-2 test above replaces it.
- Delete the test `test_outlook_accounts_can_ask_only_an_outlook_that_is_already_open`. Outlook is now asked only once someone has signed in, so there is no "open only" way any more.

- [ ] **Step 2: Run them to see them fail**

Run: `.\.venv\Scripts\python.exe -m pytest agent\tests\test_accounts.py -v`
Expected: FAIL with `AttributeError: module 'sla_agent.accounts' has no attribute 'SITE_ACCEPTED'` (and `is_device_key`, `save_and_turn_on_sync`).

- [ ] **Step 3: Add the key's shape and step 2's save to accounts.py**

In `agent/sla_agent/accounts.py`:

Replace

```python
import logging
from dataclasses import dataclass, fields
```

with

```python
import logging
import re
from dataclasses import dataclass, fields
```

After the line `DESKTOP_ICON_ADDED = …` add:

```python
SITE_ACCEPTED = "The website accepted this key."
DEVICE_KEY = re.compile(r"sla_[A-Za-z0-9_-]{43}")  # the website's keys: sla_ and 32 random bytes, URL-safe Base64
```

In `check_site`, replace

```python
    return Result(True, "The web app accepted this device key.")
```

with

```python
    return Result(True, SITE_ACCEPTED)
```

After the function `check_site`, add:

```python
def is_device_key(text):
    """Whether `text`, without the spaces or line break around it, has a device key's exact shape (the website's
    DeviceKeys.java). Text of any other shape is never sent to the website."""
    return bool(DEVICE_KEY.fullmatch((text or "").strip()))
```

At the end of the file (after `first_setup`, which Step 8 removes), add:

```python
def save_and_turn_on_sync(state, address, key, student_id, password, tools):
    """The setup's step 2 (spec 2026-10-02-easy-install-design.md, 3): one EduSoft login attempt; when it passes, the
    website's address and device key (accepted on step 1) and EduSoft are saved together, then automatic sync is
    turned on. Returns {"edusoft": Result} when the login failed and nothing was saved, else {"edusoft": Result,
    "sync": Result}."""
    protect(key)
    protect(password)
    checked = check_edusoft(student_id, password, tools)
    if not checked.ok:
        return {"edusoft": checked}
    saved = save_site_and_edusoft(state, address, key, student_id, password)
    return {"edusoft": saved, "sync": _after_saving(lambda: turn_on_sync(tools))}
```

- [ ] **Step 4: Run them to see them pass**

Run: `.\.venv\Scripts\python.exe -m pytest agent\tests\test_accounts.py -v`
Expected: PASS.

- [ ] **Step 5: Write the failing tests for the setup pages**

Create `agent/tests/test_setup_steps.py`:

```python
"""The setup pages (setup_steps.py), built for real but hidden, with fakes behind them (accounts_fakes.Fakes). Checks
run at once instead of on a thread (run_at_once), except where a test holds them to look at the page meanwhile; the
clipboard and the browser are fakes too."""

import logging

import pytest

from agent.tests.accounts_fakes import KEY, PASSWORD, SERVER, STUDENT, Fakes
from sla_agent import accounts, credentials, launcher, setup_steps, window, window_parts
from sla_agent.errors import BadCredentials, DeviceKeyRejected, ServerUnreachable
from sla_agent.log import setup_logging
from sla_agent.scheduler import SchedulerError
from sla_agent.state import load_state


@pytest.fixture
def fakes():
    return Fakes()


@pytest.fixture(autouse=True)
def clipboard(monkeypatch):
    """What the clipboard holds (clipboard[0]): tests never read the real one."""
    held = [""]
    monkeypatch.setattr(setup_steps, "read_clipboard", lambda root: held[0])
    return held


@pytest.fixture
def browser(monkeypatch):
    opened = []
    monkeypatch.setattr(setup_steps.webbrowser, "open", opened.append)
    return opened


def start(root, fakes, run=window_parts.run_at_once):
    """A new laptop's window on step 1, with the website at SERVER (as after Change)."""
    app = window.App(root, fakes.tools(), run=run)
    app.screen.values["address"].set(SERVER)
    return app, app.screen


def disabled(button):
    return button.instate(["disabled"])


def to_step_2(steps):
    steps.values["key"].set(KEY)
    steps.page.next_button.invoke()


def through_step_2(steps, password=PASSWORD):
    to_step_2(steps)
    steps.values["student_id"].set(STUDENT)
    steps.values["password"].set(password)
    steps.page.next_button.invoke()


def to_the_end(steps):
    """Through every step, skipping what can be skipped: on to the last page."""
    through_step_2(steps)


def logged(log_path, tmp_path):
    for handler in logging.getLogger().handlers[:]:
        if str(tmp_path) in getattr(handler, "baseFilename", ""):
            handler.flush()
            logging.getLogger().removeHandler(handler)
            handler.close()
    return log_path.read_text(encoding="utf-8")


# ---- step 1: the website ---------------------------------------------------------------------


def test_a_new_laptop_starts_at_step_1_with_its_guide(root, fakes):
    steps = window.App(root, fakes.tools(), run=window_parts.run_at_once).screen

    assert isinstance(steps.page, setup_steps.SitePage)
    assert steps.page.header() == "Step 1 of 2 · Connect to the website"
    assert steps.values["address"].get() == setup_steps.LOCAL_ADDRESS  # from source; the built app: online
    assert disabled(steps.page.next_button)


def test_the_built_app_starts_with_the_website_online(root, fakes, monkeypatch):
    monkeypatch.setattr(launcher, "frozen", lambda: True)

    steps = window.App(root, fakes.tools(), run=window_parts.run_at_once).screen

    assert steps.values["address"].get() == "https://school-life-assistant.onrender.com"


def test_open_the_website_opens_the_devices_page(root, fakes, browser):
    _, steps = start(root, fakes)

    steps.page.open_button.invoke()

    assert browser == [f"{SERVER}/school/devices"]


def test_a_key_is_checked_as_soon_as_it_is_there_and_next_works_once_accepted(root, fakes):
    _, steps = start(root, fakes)

    steps.values["key"].set(KEY)

    assert fakes.servers == [(SERVER, KEY)]
    assert steps.page.answer.get() == "✓ The website accepted this key."
    assert not disabled(steps.page.next_button)


def test_a_key_with_spaces_or_a_line_break_around_it_is_checked_without_them(root, fakes):  # Review Focus 2
    _, steps = start(root, fakes)

    steps.values["key"].set(f"  {KEY}\n")

    assert fakes.servers == [(SERVER, KEY)]
    assert not disabled(steps.page.next_button)


def test_text_that_isnt_a_device_key_is_never_sent(root, fakes):
    _, steps = start(root, fakes)

    steps.values["key"].set("My laptop")

    assert fakes.servers == []
    assert steps.page.answer.get() == "✗ " + setup_steps.NOT_A_KEY
    assert disabled(steps.page.next_button)


def test_a_rejected_key_says_so_and_next_stays_off(root, fakes):
    fakes.server.check_error = DeviceKeyRejected("rejected")
    _, steps = start(root, fakes)

    steps.values["key"].set(KEY)

    assert steps.page.answer.get() == ("✗ The web app rejected this device key. Create a new one on the Devices "
                                       "page. Nothing was saved.")
    assert disabled(steps.page.next_button)


def test_a_website_that_cant_be_reached_says_so_and_saves_nothing(root, fakes, isolated_agent):  # Review Focus 1
    fakes.server.check_error = ServerUnreachable("The web app couldn't be reached (ConnectionError).")
    _, steps = start(root, fakes)

    steps.values["key"].set(KEY)

    assert steps.page.answer.get() == ("✗ Couldn't reach the web app: The web app couldn't be reached "
                                       "(ConnectionError). Nothing was saved.")
    assert disabled(steps.page.next_button)
    assert isolated_agent.entries == {}


def test_paste_takes_a_copied_key(root, fakes, clipboard):  # Review Focus 2
    _, steps = start(root, fakes)
    clipboard[0] = f"{KEY}\r\n"

    steps.page.paste_button.invoke()

    assert steps.values["key"].get() == KEY
    assert not disabled(steps.page.next_button)


def test_paste_says_when_the_copied_text_isnt_a_key(root, fakes, clipboard):
    _, steps = start(root, fakes)
    clipboard[0] = "My laptop"

    steps.page.paste_button.invoke()

    assert steps.values["key"].get() == ""
    assert steps.page.answer.get() == "✗ " + setup_steps.NOT_A_KEY


def test_coming_back_to_the_window_fills_in_a_copied_key(root, fakes, clipboard):
    _, steps = start(root, fakes)
    clipboard[0] = KEY

    steps.came_forward()

    assert steps.values["key"].get() == KEY
    assert fakes.servers == [(SERVER, KEY)]


def test_coming_back_never_replaces_a_key_already_there_or_takes_other_text(root, fakes, clipboard):
    _, steps = start(root, fakes)
    clipboard[0] = "some other text"

    steps.came_forward()

    assert steps.values["key"].get() == ""
    steps.values["key"].set("sla_typed-by-hand")
    clipboard[0] = KEY

    steps.came_forward()

    assert steps.values["key"].get() == "sla_typed-by-hand"


def test_change_shows_the_address_box_and_a_new_address_checks_the_key_again(root, fakes):  # Review Focus 3
    _, steps = start(root, fakes)
    steps.values["key"].set(KEY)

    steps.page.change_button.invoke()
    steps.values["address"].set("https://other.example.com/")
    steps.page.key_changed()  # what Enter, or leaving the address box, does

    assert steps.page.change_button is None
    assert fakes.servers == [(SERVER, KEY), ("https://other.example.com", KEY)]
    assert not disabled(steps.page.next_button)


def test_a_plain_http_address_is_refused_without_sending_the_key(root, fakes):  # Review Focus 3
    _, steps = start(root, fakes)
    steps.page.change_button.invoke()
    steps.values["address"].set("http://sla.example.com")

    steps.values["key"].set(KEY)

    assert fakes.servers == []
    assert "must start with https://" in steps.page.answer.get()
    assert disabled(steps.page.next_button)


def test_a_late_answer_for_a_key_changed_meanwhile_is_dropped(root, fakes):
    held = []
    _, steps = start(root, fakes, run=lambda work, done: held.append((work, done)))
    steps.values["key"].set(KEY)
    assert steps.page.answer.get() == window_parts.CHECKING
    work, done = held.pop()

    steps.values["key"].set("")  # cleared while the website answered
    done(window_parts.attempt(work))

    assert steps.page.answer.get() == ""
    assert disabled(steps.page.next_button)


# ---- step 2: EduSoft ---------------------------------------------------------------------


def test_step_2_saves_the_key_and_edusoft_and_turns_on_sync_without_a_desktop_icon(root, fakes):
    _, steps = start(root, fakes)

    through_step_2(steps)

    assert credentials.load_device_key(SERVER) == KEY
    assert credentials.load_edusoft(STUDENT) == PASSWORD
    assert (load_state().server_url, load_state().student_id) == (SERVER, STUDENT)
    assert {"task", "mail link", "window link", "shortcuts"} <= set(fakes.done)
    assert "desktop icon" not in fakes.done
    assert steps.values["key"].get() == steps.values["password"].get() == ""
    assert isinstance(steps.page, steps.after(setup_steps.EdusoftPage))


def test_a_wrong_edusoft_password_saves_nothing_and_back_keeps_the_accepted_key(root, fakes, isolated_agent):
    fakes.edusoft.login_error = BadCredentials("rejected")
    _, steps = start(root, fakes)

    through_step_2(steps, password="wrong")

    assert isinstance(steps.page, setup_steps.EdusoftPage)
    assert steps.page.answer.get() == "✗ EduSoft rejected the student ID or password. Nothing was saved."
    assert isolated_agent.entries == {}
    assert steps.values["password"].get() == "wrong"
    assert not disabled(steps.page.next_button)

    steps.page.back_button.invoke()

    assert isinstance(steps.page, setup_steps.SitePage)
    assert steps.values["key"].get() == KEY
    assert steps.page.answer.get() == "✓ The website accepted this key."
    assert not disabled(steps.page.next_button)
    assert len(fakes.servers) == 1  # an accepted key isn't checked again


def test_something_unexpected_on_step_2_is_shown_not_raised(root, fakes):
    fakes.edusoft.login_error = RuntimeError("a bug")
    _, steps = start(root, fakes)

    through_step_2(steps)

    assert isinstance(steps.page, setup_steps.EdusoftPage)
    assert steps.page.answer.get() == "✗ Something went wrong (RuntimeError). Nothing was saved."
    assert not disabled(steps.page.back_button)


def test_while_edusoft_is_checked_both_buttons_are_off(root, fakes):
    held = []
    app, steps = start(root, fakes)
    to_step_2(steps)
    app.run = lambda work, done: held.append((work, done))
    steps.values["student_id"].set(STUDENT)
    steps.values["password"].set(PASSWORD)

    steps.page.next_button.invoke()

    assert steps.page.answer.get() == window_parts.CHECKING
    assert disabled(steps.page.back_button) and disabled(steps.page.next_button)
    work, done = held.pop()
    done(window_parts.attempt(work))
    assert isinstance(steps.page, steps.after(setup_steps.EdusoftPage))


# ---- the last page --------------------------------------------------------------------------


def test_the_last_page_says_what_is_on(root, fakes):
    _, steps = start(root, fakes)

    to_the_end(steps)

    page = steps.page
    assert isinstance(page, setup_steps.DonePage)
    assert page.header() == "All set"
    assert page.lines == [setup_steps.ALL_SET, f"✓ EduSoft: {STUDENT}",
                          "– Blackboard: skipped: set it up later in Accounts",
                          "– Outlook: skipped: set it up later in Accounts"]


def test_yes_adds_the_desktop_icon_then_shows_accounts_and_opens_the_app(root, fakes):
    app, steps = start(root, fakes)
    to_the_end(steps)

    steps.page.yes_button.invoke()

    assert "desktop icon" in fakes.done
    assert isinstance(app.screen, window.AccountsScreen)
    assert app.screen.notice.get() == "✓ " + accounts.DESKTOP_ICON_ADDED
    assert app.screen.values["desktop"].get() == "on"
    assert fakes.opened == [SERVER]


def test_no_adds_no_desktop_icon_and_still_opens_the_app(root, fakes):
    app, steps = start(root, fakes)
    to_the_end(steps)

    steps.page.no_button.invoke()

    assert "desktop icon" not in fakes.done
    assert isinstance(app.screen, window.AccountsScreen)
    assert app.screen.values["desktop"].get() == "off"
    assert fakes.opened == [SERVER]


def test_a_desktop_icon_that_cant_be_added_says_so_and_still_opens_the_app(root, fakes):
    fakes.fail = {"desktop icon": RuntimeError("com_error")}
    app, steps = start(root, fakes)
    to_the_end(steps)

    steps.page.yes_button.invoke()

    assert app.screen.notice.get().startswith("✗ Couldn't add the Desktop icon (RuntimeError).")
    assert fakes.opened == [SERVER]


def test_the_last_page_says_when_sync_couldnt_be_turned_on(root, fakes):
    fakes.fail = {"task": SchedulerError("Couldn't create the scheduled task: Access is denied.")}
    _, steps = start(root, fakes)

    to_the_end(steps)

    assert steps.page.lines[:2] == ["✗ Couldn't create the scheduled task: Access is denied.",
                                    setup_steps.SYNC_REPAIR]


def test_the_last_page_lists_a_start_menu_entry_that_couldnt_be_made(root, fakes):
    fakes.fail = {"shortcuts": RuntimeError("com_error")}
    _, steps = start(root, fakes)

    to_the_end(steps)

    assert steps.page.lines[0] == setup_steps.ALL_SET
    assert any(line.startswith("Couldn't make the Start menu entries (RuntimeError).") for line in steps.page.lines)


def test_the_setup_pages_never_log_a_password_or_the_key(root, fakes, tmp_path):
    log_path = setup_logging(tmp_path / "logs")
    fakes.fail = {"shortcuts": RuntimeError(f"a bug with {PASSWORD} and {KEY}")}
    _, steps = start(root, fakes)

    to_the_end(steps)

    text = logged(log_path, tmp_path)
    assert "Couldn't make the shortcuts" in text
    assert PASSWORD not in text and KEY not in text
```

- [ ] **Step 6: Run them to see them fail**

Run: `.\.venv\Scripts\python.exe -m pytest agent\tests\test_setup_steps.py -v`
Expected: FAIL with `ImportError: cannot import name 'setup_steps' from 'sla_agent'`.

- [ ] **Step 7: Write the setup pages**

Create `agent/sla_agent/setup_steps.py`:

```python
"""The setup pages (spec 2026-10-02-easy-install-design.md, 3): a laptop that isn't set up gets one page per step,
each with its own guide, instead of one long form. Step 1 connects to the website with a device key and saves
nothing; step 2 checks EduSoft, then saves both and turns on automatic sync; the last page asks about the Desktop
icon and opens School-Life-Assistant.

The pages only collect what the student types and show the answers; accounts.py checks and saves, each check on a
worker thread (app.run), as Accounts does."""

import tkinter as tk
import webbrowser
from tkinter import ttk

from sla_agent import accounts, launcher
from sla_agent.accounts import Result
from sla_agent.log import protect
from sla_agent.state import load_state
from sla_agent.window_parts import CHECKING, answer_line, field, heading, mark, text_line

LOCAL_ADDRESS = "http://localhost:5000"  # a developer's own site, running from source
ONLINE_ADDRESS = "https://school-life-assistant.onrender.com"  # the website students use
SITE_STEPS = (
    "1. Press Open the website.",
    "2. Log in, or create an account.",
    "3. Go to School → Devices.",
    "4. Type a name for this laptop (for example \"My laptop\") and press Add device.",
    "5. Press Copy next to the new key.",
    "6. Come back to this window: the key is filled in by itself (or press Paste).",
)
NOT_A_KEY = "This isn't a device key. Press Copy on the Devices page, then Paste."
EDUSOFT_HINT = "The student ID and password you use on edusoftweb.hcmiu.edu.vn."
ALL_SET = "✓ All set! This laptop checks in every minute, and everything syncs every 30 minutes."
SYNC_REPAIR = "Automatic sync isn't on yet: press Repair next to it in Accounts."
SKIPPED = "skipped: set it up later in Accounts"
DESKTOP_QUESTION = "Put a School-Life-Assistant icon on your Desktop, to open it quickly?"


def default_address():
    """The address step 1 starts with: the website online for the built app, a developer's own site from source."""
    return ONLINE_ADDRESS if launcher.frozen() else LOCAL_ADDRESS


def read_clipboard(root):
    """The clipboard's text, or "" when it holds none. Tests replace it: they never read the real clipboard."""
    try:
        return root.clipboard_get()
    except tk.TclError:
        return ""


class SetupSteps:
    """The pages, one at a time (`page`). What the student typed is kept across them (`values`), with the address and
    key the website accepted on step 1 (`accepted`) and what each step saved (`results`)."""

    def __init__(self, app, state):
        self.app = app
        names = ("address", "key", "student_id", "password", "bb_user", "bb_password", "outlook")
        self.values = {name: tk.StringVar(app.body) for name in names}
        self.values["address"].set(state.server_url or default_address())
        self.values["student_id"].set(state.student_id or "")
        self.accepted = None  # (address, key)
        self.checking = None  # the (address, key) whose check counts: a newer change drops older answers
        self.address_open = False  # Change was pressed: the address box shows
        self.results = {}  # step -> Result
        self.page = None
        self.values["key"].trace_add("write", lambda *args: self.key_changed())
        app.root.bind("<FocusIn>", lambda event: self.came_forward() if event.widget is app.root else None,
                      add="+")
        self.show(SitePage)

    def show(self, page_class):
        if self.page is not None:
            self.page.frame.destroy()
        page_class(self)  # it makes itself self.page before it builds anything

    def after(self, page_class):
        """The page after `page_class`: the next step, or the last page."""
        index = STEPS.index(page_class) + 1
        return STEPS[index] if index < len(STEPS) else DonePage

    def key_changed(self):
        if isinstance(self.page, SitePage):
            self.page.key_changed()

    def came_forward(self):
        """The window came back to the front, as after copying the key in the browser."""
        if self.app.screen is self and isinstance(self.page, SitePage):
            self.page.fill_from_clipboard()


class Page:
    """One page: its heading, then what each page adds, row by row. `answer` is the line its checks answer on."""

    title = ""

    def __init__(self, steps):
        steps.page = self
        self.steps, self.app = steps, steps.app
        self.frame = ttk.Frame(self.app.body)
        self.frame.grid(row=0, column=0, columnspan=3, sticky="nsew")
        self.frame.columnconfigure(1, weight=1)
        self.answer = tk.StringVar(self.frame)
        self.row = 0
        heading(self.frame, self.header(), self.next_row())

    def header(self):
        return f"Step {STEPS.index(type(self)) + 1} of {len(STEPS)} · {self.title}"

    def next_row(self):
        self.row += 1
        return self.row - 1

    def add_line(self, text):
        text_line(self.frame, text, self.next_row())

    def add_field(self, label, name, secret=False):
        return field(self.frame, label, self.steps.values[name], self.next_row(), secret=secret)

    def add_answer(self):
        answer_line(self.frame, self.answer, self.next_row())

    def add_bar(self, *buttons):
        """The page's buttons, bottom right, from (text, command) pairs; returns them in that order."""
        bar = ttk.Frame(self.frame)
        bar.grid(row=self.next_row(), column=0, columnspan=3, sticky="e", pady=(16, 0))
        made = []
        for column, (text, command) in enumerate(buttons):
            made.append(ttk.Button(bar, text=text, command=command))
            made[-1].grid(row=0, column=column, padx=(8, 0))
        return made

    def busy(self, *buttons):
        self.answer.set(CHECKING)
        for button in buttons:
            button.state(["disabled"])

    def free(self, *buttons):
        for button in buttons:
            button.state(["!disabled"])

    def current(self):
        """Whether this page is still the one shown: a check's late answer for a page left meanwhile is dropped."""
        return self.steps.page is self


class SitePage(Page):
    """Step 1: the device key, with its guide. Nothing is saved yet: a key the website accepts turns Next on."""

    title = "Connect to the website"

    def __init__(self, steps):
        super().__init__(steps)
        for text in SITE_STEPS:
            self.add_line(text)
        self.open_button = ttk.Button(self.frame, text="Open the website", command=self.open_site)
        self.open_button.grid(row=self.next_row(), column=0, columnspan=3, sticky="w", pady=(4, 8))
        self.paste_button = ttk.Button(self.frame, text="Paste", command=self.paste)
        self.paste_button.grid(row=self.row, column=2, padx=(8, 0))
        self.add_field("Device key", "key", secret=True)
        self.add_answer()
        self.address_row = self.next_row()
        self.address_box = self.change_button = None
        self.show_address()
        [self.next_button] = self.add_bar(("Next →", lambda: steps.show(EdusoftPage)))
        self.key_changed()  # coming Back from step 2, the accepted key is still there

    def show_address(self):
        """"Website: <address>" with Change, or, once Change was pressed, the address box. Enter, or leaving the
        box, checks the key with the new address (each keystroke would ask a half-typed one)."""
        if self.address_box is not None:
            self.address_box.destroy()
        self.address_box = ttk.Frame(self.frame)
        self.address_box.grid(row=self.address_row, column=0, columnspan=3, sticky="ew", pady=(8, 0))
        self.address_box.columnconfigure(1, weight=1)
        if self.steps.address_open:
            self.change_button = None
            box = field(self.address_box, "Website", self.steps.values["address"], 0)
            for event in ("<Return>", "<FocusOut>"):
                box.bind(event, lambda event: self.key_changed())
            return
        ttk.Label(self.address_box, text="Website:", foreground="gray").grid(row=0, column=0, sticky="w")
        ttk.Label(self.address_box, textvariable=self.steps.values["address"], foreground="gray").grid(
            row=0, column=1, sticky="w", padx=(4, 0))
        self.change_button = ttk.Button(self.address_box, text="Change", command=self.change)
        self.change_button.grid(row=0, column=2, padx=(8, 0))

    def change(self):
        self.steps.address_open = True
        self.show_address()

    def open_site(self):
        webbrowser.open(self.steps.values["address"].get().strip().rstrip("/") + "/school/devices")

    def paste(self):
        text = read_clipboard(self.app.root).strip()
        if not accounts.is_device_key(text):
            self.answer.set(mark(Result(False, NOT_A_KEY)))
            return
        self.steps.values["key"].set(text)  # checked by key_changed

    def fill_from_clipboard(self):
        """A device key copied meanwhile fills the empty box; anything else on the clipboard is left alone."""
        text = read_clipboard(self.app.root).strip()
        if not self.steps.values["key"].get().strip() and accounts.is_device_key(text):
            self.steps.values["key"].set(text)

    def key_changed(self):
        """Check a key-shaped key with the website at once; say so for any other text. Next only once accepted."""
        address = self.steps.values["address"].get().strip()
        key = self.steps.values["key"].get().strip()
        self.next_button.state(["disabled"])
        self.steps.checking = (address, key)
        if not key:
            self.answer.set("")
        elif not accounts.is_device_key(key):
            self.answer.set(mark(Result(False, NOT_A_KEY)))
        elif self.steps.accepted == (address, key):
            self.checked(address, key, Result(True, accounts.SITE_ACCEPTED))
        else:
            protect(key)
            self.answer.set(CHECKING)
            self.app.run(lambda: accounts.check_site(address, key, self.app.tools),
                         lambda result: self.checked(address, key, result))

    def checked(self, address, key, result):
        if not self.current() or self.steps.checking != (address, key):  # left, or changed again meanwhile
            return
        if result.ok:
            self.steps.accepted = (address, key)
            self.free(self.next_button)
        self.answer.set(mark(result))


class EdusoftPage(Page):
    """Step 2: one EduSoft login attempt. When it passes, the key and EduSoft are saved and automatic sync is turned
    on (accounts.save_and_turn_on_sync): from then on this laptop is set up, with no way back to step 1."""

    title = "EduSoft"

    def __init__(self, steps):
        super().__init__(steps)
        self.add_line(EDUSOFT_HINT)
        self.add_field("Student ID", "student_id")
        self.add_field("Password", "password", secret=True)
        self.add_answer()
        self.back_button, self.next_button = self.add_bar(("← Back", lambda: steps.show(SitePage)),
                                                          ("Next →", self.save))

    def save(self):
        address, key = self.steps.accepted
        student_id = self.steps.values["student_id"].get().strip()
        password = self.steps.values["password"].get()
        protect(password)
        self.busy(self.back_button, self.next_button)
        self.app.run(lambda: accounts.save_and_turn_on_sync(load_state(), address, key, student_id, password,
                                                             self.app.tools), self.saved)

    def saved(self, results):
        if not self.current():
            return
        if isinstance(results, Result):  # something unexpected went wrong
            results = {"edusoft": results}
        if not results["edusoft"].ok:
            self.answer.set(mark(results["edusoft"]))
            self.free(self.back_button, self.next_button)
            return
        self.steps.accepted = None
        for secret in ("key", "password"):
            self.steps.values[secret].set("")
        self.steps.results.update(results)
        self.steps.show(self.steps.after(EdusoftPage))


class DonePage(Page):
    """The last page: what is on, then the Desktop icon question. Either answer shows Accounts and opens
    School-Life-Assistant (the website as an app); closing the window instead adds no icon."""

    title = "All set"

    def __init__(self, steps):
        super().__init__(steps)
        self.lines = self.summary()
        for text in self.lines:
            self.add_line(text)
        ttk.Label(self.frame, text=DESKTOP_QUESTION, font=("Segoe UI", 10, "bold")).grid(
            row=self.next_row(), column=0, columnspan=3, sticky="w", pady=(12, 0))
        self.add_answer()
        self.no_button, self.yes_button = self.add_bar(("No, thanks", self.no), ("Yes, add the icon", self.yes))

    def header(self):
        return self.title

    def summary(self):
        """All set (or why automatic sync isn't on), a line per account, then any note a step left."""
        results, state = self.steps.results, load_state()
        sync = results["sync"]
        lines = [ALL_SET] if sync.ok else [mark(sync), SYNC_REPAIR]
        lines.append(f"✓ EduSoft: {state.student_id}")
        lines.append(f"✓ Blackboard: {state.blackboard_username}" if state.blackboard_username
                     else f"– Blackboard: {SKIPPED}")
        lines.append(f"✓ Outlook: {state.outlook_account}" if state.outlook_account else f"– Outlook: {SKIPPED}")
        return lines + [note for result in results.values() if result.ok for note in result.notes]

    def yes(self):
        self.busy(self.no_button, self.yes_button)
        self.app.run(lambda: accounts.add_desktop_icon(self.app.tools), lambda result: self.finish(mark(result)))

    def no(self):
        self.finish("")

    def finish(self, notice):
        self.app.show(notice=notice)
        self.app.tools.open_site(load_state().server_url)


STEPS = (SitePage, EdusoftPage)
```

- [ ] **Step 8: Use the setup pages and remove the one-page form**

Replace the whole of `agent/sla_agent/window.py` with:

```python
"""The School-Life-Assistant window (spec 2026-10-01-accounts-window-design.md, 3; spec
2026-10-02-easy-install-design.md): the setup pages while this laptop isn't set up (setup_steps.py), then Accounts,
to see and change its accounts, with no terminal.

The screens only collect what the student types and show the answers; accounts.py checks and saves, each check on a
worker thread (window_parts.py). One window at a time: a second start brings the open one forward
(claim_single_window)."""

import logging
import tkinter as tk
from datetime import datetime
from tkinter import ttk

from sla_agent import __version__, accounts
from sla_agent.log import protect
from sla_agent.outlook_page import OutlookPage
from sla_agent.setup_steps import SetupSteps
from sla_agent.state import load_state
from sla_agent.window_parts import CHECKING, answer_line, field, heading, mark, run_in_background

log = logging.getLogger(__name__)

TITLE = "School-Life-Assistant"
MUTEX = "SchoolLifeAssistant-Window"
EDUSOFT_PAUSES = {"bad_credentials": "paused: wrong student ID or password",
                  "extra_verification": "paused: EduSoft asked for extra verification"}
BLACKBOARD_PAUSES = {"bad_credentials": "paused: wrong username or password",
                     "extra_verification": "paused: Blackboard asked for extra verification"}
SYNC_STATES = {"on": "on: every minute", "off": "off", "nowhere": "points to a program that no longer exists",
               "elsewhere": "runs another copy of School-Life-Assistant"}


def local_time(iso):
    return datetime.fromisoformat(iso).astimezone().strftime("%d/%m %H:%M")


# ---- the window ----------------------------------------------------------------------------


class App:
    """The window: the setup pages (setup_steps.SetupSteps) or Accounts (AccountsScreen), rebuilt after each save."""

    def __init__(self, root, tools, run=None, notice=""):
        self.root, self.tools = root, tools
        self.run = run or run_in_background(root)
        root.title(TITLE)
        root.minsize(560, 0)
        root.columnconfigure(0, weight=1)
        self.body = None
        self.screen = None
        self.show(notice)

    def show(self, notice=""):
        if self.body is not None:
            self.body.destroy()
        self.body = ttk.Frame(self.root, padding=16)
        self.body.grid(row=0, column=0, sticky="nsew")
        self.body.columnconfigure(1, weight=1)
        state = load_state()
        if state.server_url and state.student_id:
            self.screen = AccountsScreen(self, state, notice)
        else:
            self.screen = SetupSteps(self, state)


class AccountsScreen:
    """Set up: one row per account with Change (spec 3.2); Change opens that account's fields under its row. Repair
    and the Desktop icon's Add work at one press."""

    ROWS = ("site", "edusoft", "blackboard", "outlook", "sync", "desktop", "version")
    NAMES = {"site": "Website", "edusoft": "EduSoft", "blackboard": "Blackboard", "outlook": "Outlook",
             "sync": "Automatic sync", "desktop": "Desktop icon", "version": "Version"}

    def __init__(self, app, state, notice=""):
        self.app, self.state = app, state
        self.sync_state = accounts.sync_task_state(app.tools)
        self.desktop = accounts.desktop_icon_on(app.tools)
        frame = app.body
        heading(frame, "Accounts", 0)
        self.open_button = ttk.Button(frame, text="Open School-Life-Assistant",
                                      command=lambda: app.tools.open_site(state.server_url))
        self.open_button.grid(row=0, column=2, sticky="ne")
        self.notice = tk.StringVar(frame, notice)
        answer_line(frame, self.notice, 1)
        self.values, self.answers, self.buttons, self.boxes = {}, {}, {}, {}
        self.editor = None
        for index, row in enumerate(self.ROWS):
            box = ttk.Frame(frame)
            box.grid(row=2 + index, column=0, columnspan=3, sticky="ew", pady=(8, 0))
            box.columnconfigure(1, weight=1)
            ttk.Label(box, text=self.NAMES[row], width=16, font=("Segoe UI", 10, "bold")).grid(
                row=0, column=0, sticky="w")
            self.values[row] = tk.StringVar(box, self.describe(row))
            ttk.Label(box, textvariable=self.values[row]).grid(row=0, column=1, sticky="w")
            label = self.button_label(row)
            if label:
                self.buttons[row] = ttk.Button(box, text=label, command=lambda row=row: self.open(row))
                self.buttons[row].grid(row=0, column=2)
            self.answers[row] = tk.StringVar(box)
            answer_line(box, self.answers[row], 2)
            self.boxes[row] = box

    def describe(self, row):
        state = self.state
        if row == "site":
            last = state.last_result
            when = f"; last sync {last['status']} {local_time(last['at'])}" if last else "; never synced"
            return state.server_url + when
        if row == "edusoft":
            paused = state.paused
            return f"{state.student_id}: " + (EDUSOFT_PAUSES.get(paused, f"paused ({paused})") if paused else "on")
        if row == "blackboard":
            if not state.blackboard_username:
                return "not set up"
            paused = state.blackboard_paused
            return f"{state.blackboard_username}: " + (
                BLACKBOARD_PAUSES.get(paused, f"paused ({paused})") if paused else "on")
        if row == "outlook":
            return state.outlook_account or "not set up"
        if row == "version":
            return __version__ + (f", updated by itself on {local_time(state.updated_at)}" if state.updated_at else "")
        if row == "desktop":
            return "on" if self.desktop else "off"
        return SYNC_STATES[self.sync_state]

    def button_label(self, row):
        if row == "version":
            return None
        if row == "sync":
            return None if self.sync_state == "on" else "Repair"
        if row == "desktop":
            return None if self.desktop else "Add"
        if (row == "blackboard" and not self.state.blackboard_username) or (
                row == "outlook" and not self.state.outlook_account):
            return "Set up"
        return "Change"

    def open(self, row):
        if row in ("sync", "desktop"):  # Repair and Add: one press, no fields
            action = accounts.turn_on_sync if row == "sync" else accounts.add_desktop_icon
            self.buttons[row].state(["disabled"])
            self.answers[row].set(CHECKING)
            self.app.run(lambda: action(self.app.tools), lambda result: self.app.show(notice=mark(result)))
            return
        for button in self.buttons.values():
            button.state(["disabled"])
        self.editor = Editor(self, row)

    def closed(self):
        self.editor = None
        for button in self.buttons.values():
            button.state(["!disabled"])


class Editor:
    """One account's fields under its row, with Check and save and Cancel; Outlook's is the Outlook page."""

    FIELDS = {
        "site": (("Address", "address", False), ("Device key", "key", True)),
        "edusoft": (("Student ID", "student_id", False), ("Password", "password", True)),
        "blackboard": (("Username", "username", False), ("Password", "password", True)),
        "outlook": (),
    }

    def __init__(self, screen, row):
        self.screen, self.row = screen, row
        state = screen.state
        self.frame = ttk.Frame(screen.boxes[row], padding=(16, 4, 0, 4))
        self.frame.grid(row=1, column=0, columnspan=3, sticky="ew")
        self.frame.columnconfigure(1, weight=1)
        start = {"address": state.server_url or "", "student_id": state.student_id or "",
                 "username": state.blackboard_username or ""}
        self.values = {}
        for index, (label, name, secret) in enumerate(self.FIELDS[row]):
            self.values[name] = tk.StringVar(self.frame, start.get(name, ""))
            field(self.frame, label, self.values[name], index, secret=secret)
        if row == "outlook":
            self.values["outlook"] = tk.StringVar(self.frame, state.outlook_account or "")
            self.outlook = OutlookPage(screen.app, self.frame, self.values["outlook"], skippable=False)
            self.outlook.frame.grid(row=0, column=0, columnspan=3, sticky="ew")
        buttons = ttk.Frame(self.frame)
        buttons.grid(row=5, column=0, columnspan=3, sticky="e", pady=(4, 0))
        self.save_button = ttk.Button(buttons, text="Check and save", command=self.save)
        self.save_button.grid(row=0, column=0)
        self.cancel_button = ttk.Button(buttons, text="Cancel", command=self.cancel)
        self.cancel_button.grid(row=0, column=1, padx=(8, 0))

    def save(self):
        value = {name: variable.get() for name, variable in self.values.items()}
        protect(value.get("key"))
        protect(value.get("password"))
        if self.row == "outlook" and not value["outlook"]:
            self.screen.answers["outlook"].set("✗ Choose an account first.")
            return
        tools, state = self.screen.app.tools, load_state()
        work = {
            "site": lambda: accounts.change_site(state, value["address"], value["key"].strip(), tools),
            "edusoft": lambda: accounts.change_edusoft(state, value["student_id"].strip(), value["password"], tools),
            "blackboard": lambda: accounts.change_blackboard(state, value["username"].strip(), value["password"],
                                                             tools),
            "outlook": lambda: accounts.choose_outlook(state, value["outlook"], tools),
        }[self.row]
        self.screen.answers[self.row].set(CHECKING)
        self.save_button.state(["disabled"])
        self.cancel_button.state(["disabled"])
        self.screen.app.run(work, self.saved)

    def saved(self, result):
        if result.ok:
            self.screen.app.show(notice=mark(result))
            return
        self.screen.answers[self.row].set(mark(result))
        self.save_button.state(["!disabled"])
        self.cancel_button.state(["!disabled"])

    def cancel(self):
        self.frame.destroy()
        self.screen.answers[self.row].set("")
        self.screen.closed()


# ---- starting -----------------------------------------------------------------------------

_held = []  # the mutex handle, kept for the life of this process


def _first_window(name):
    """True when no other window holds the named mutex (Windows only; elsewhere always True)."""
    try:
        import win32api
        import win32event
        import winerror
    except ImportError:
        return True
    handle = win32event.CreateMutex(None, False, name)
    if win32api.GetLastError() == winerror.ERROR_ALREADY_EXISTS:
        return False
    _held.append(handle)
    return True


def _bring_forward(title):
    try:
        import win32con
        import win32gui

        found = win32gui.FindWindow(None, title)
        if found:
            win32gui.ShowWindow(found, win32con.SW_RESTORE)
            win32gui.SetForegroundWindow(found)
    except Exception as error:  # ImportError off Windows; pywin32's error when Windows refuses the focus change
        log.debug("Couldn't bring the open window forward (%s)", error.__class__.__name__)


def claim_single_window(first=None, bring_forward=None):
    """True when this is the only window; otherwise brings the open one to the front and returns False."""
    if (first or _first_window)(MUTEX):
        return True
    (bring_forward or _bring_forward)(TITLE)
    return False


def _sharp_on_high_dpi():
    try:
        import ctypes

        ctypes.windll.shcore.SetProcessDpiAwareness(1)
    except (AttributeError, OSError):  # not Windows, or an old one
        pass


def main(tools, link=None):
    """What `sla-agent window` runs (also the sla-agent: link, the shortcuts and School-Life-Assistant.cmd)."""
    if link:
        log.debug("Opened from %s", link)
    if not claim_single_window():
        return 0
    notes = accounts.add_window_links(tools)  # a laptop set up before the window existed
    _sharp_on_high_dpi()
    root = tk.Tk()
    root.report_callback_exception = lambda *exc: log.error("Accounts window error", exc_info=exc)
    App(root, tools, notice="\n".join(notes))
    root.mainloop()
    return 0
```

In `agent/sla_agent/window_parts.py`, delete the function `section`; only the old form used it.

In `agent/sla_agent/accounts.py`:
- Delete the dataclass `SetupForm`, from `@dataclass(frozen=True)` / `class SetupForm:` through its `outlook: str = ""` line.
- In `Tools`, delete the line `    find_open_outlook_accounts: Callable  # the same, asking only an Outlook that is already open`.
- Replace the function `outlook_accounts` with:

```python
def outlook_accounts(tools):
    """(the accounts in classic Outlook, None), or ([], what's wrong). It may start a hidden Outlook for a moment, so
    the Outlook page asks it only once someone has signed in to Outlook (outlook_reader.classic_outlook)."""
    try:
        found = tools.find_outlook_accounts()
    except AgentError as error:
        return [], str(error)
    if not found:
        return [], "Classic Outlook has no account yet."
    return list(found), None
```

- Replace the heading line `# ---- the first-time form ---------------------------------------------------------------` with `# ---- the setup pages ---------------------------------------------------------------`, and delete the function `first_setup` (with its docstring). Keep `_after_saving`, which `save_and_turn_on_sync` uses; `save_and_turn_on_sync` is then the file's last function.

In `agent/sla_agent/cli.py`:
- Delete the function `find_open_outlook_accounts` (with its docstring and inner function).
- In `tools()`, replace `find_outlook_accounts=find_outlook_accounts, find_open_outlook_accounts=find_open_outlook_accounts,` with `find_outlook_accounts=find_outlook_accounts,`.
- In the `from sla_agent.outlook_reader import (...)` block, delete the line `    running_outlook,`.
- In the `from sla_agent.errors import (...)` block, delete the line `    OutlookNotSetUp,`; nothing in `cli.py` uses it now.

In `agent/tests/accounts_fakes.py`:
- In `tools()`, replace

```python
            make_blackboard=lambda: self.blackboard, find_outlook_accounts=self._find,
            find_open_outlook_accounts=self._find_open, outlook_state=self._outlook_state,
```

with

```python
            make_blackboard=lambda: self.blackboard, find_outlook_accounts=self._find,
            outlook_state=self._outlook_state,
```

- Delete the method `_find_open`.
- Replace the whole `Fakes` docstring with:

```python
    """`found` is Outlook's account list (an exception instance is raised instead); `outlook` is what the registry
    says about Outlook (SIGNED_IN unless a test changes it; an exception instance is raised instead); `program` is
    what the scheduled task starts; `fail` maps a part ("task", "mail link", "window link", "shortcuts",
    "desktop icon", "start outlook") to the error it raises; `done` lists the parts that ran; `looks` lists each
    time Outlook itself was asked ("start"); `me` is the program this agent is: what turn_on_sync points things
    at."""
```

In `agent/tests/test_cli.py`, delete the test `test_the_windows_own_look_never_starts_outlook`.

Replace the whole of `agent/tests/test_window.py` with:

```python
"""The School-Life-Assistant window's Accounts screen, built for real but hidden, with fakes behind it
(accounts_fakes.Fakes). The setup pages have their own tests (test_setup_steps.py). Checks run at once instead of on a
thread (run_at_once)."""

import pytest

from agent.tests.accounts_fakes import BB_PASSWORD, BB_USER, ME, SERVER, STUDENT, Fakes, set_up
from sla_agent import __version__, accounts, credentials, launcher, outlook_page, setup_steps, window, window_parts
from sla_agent.errors import BadCredentials
from sla_agent.outlook_reader import MISSING
from sla_agent.state import save_state


@pytest.fixture
def fakes():
    return Fakes()


def open_window(root, fakes, run=window_parts.run_at_once):
    return window.App(root, fakes.tools(), run=run)


def disabled(button):
    return button.instate(["disabled"])


def test_a_new_laptop_gets_the_setup_pages(root, fakes):
    app = open_window(root, fakes)

    assert isinstance(app.screen, setup_steps.SetupSteps)


# ---- Accounts ---------------------------------------------------------------------


def test_accounts_shows_each_account_and_a_pause(root, fakes):
    state = set_up()
    state.paused = "bad_credentials"
    save_state(state)

    app = open_window(root, fakes)

    assert isinstance(app.screen, window.AccountsScreen)
    assert app.screen.values["site"].get() == f"{SERVER}; never synced"
    assert app.screen.values["edusoft"].get() == f"{STUDENT}: paused: wrong student ID or password"
    assert app.screen.values["blackboard"].get() == "not set up"
    assert app.screen.buttons["blackboard"].cget("text") == "Set up"
    assert app.screen.values["sync"].get() == "off"
    assert app.screen.buttons["sync"].cget("text") == "Repair"


def test_a_wrong_new_blackboard_password_keeps_the_old_login(root, fakes):
    state = set_up()
    accounts.change_blackboard(state, BB_USER, BB_PASSWORD, fakes.tools())
    app = open_window(root, fakes)

    app.screen.open("blackboard")

    assert all(disabled(button) for button in app.screen.buttons.values())
    editor = app.screen.editor
    assert editor.values["username"].get() == BB_USER
    fakes.blackboard.login_error = BadCredentials("rejected")
    editor.values["password"].set("wrong")
    editor.save()
    assert app.screen.answers["blackboard"].get() == "✗ Blackboard rejected the username or password. Nothing was saved."
    assert credentials.load_blackboard(BB_USER) == BB_PASSWORD

    editor.cancel()

    assert app.screen.editor is None
    assert not any(disabled(button) for button in app.screen.buttons.values())


def test_a_change_that_passes_goes_back_to_accounts_with_its_message(root, fakes):
    set_up()
    app = open_window(root, fakes)
    app.screen.open("edusoft")
    app.screen.editor.values["password"].set("new-pass")

    app.screen.editor.save()

    assert app.screen.editor is None
    assert app.screen.notice.get() == "✓ " + accounts.SAVED
    assert credentials.load_edusoft(STUDENT) == "new-pass"


def test_outlook_change_picks_from_the_accounts_found(root, fakes):
    set_up()
    app = open_window(root, fakes)
    app.screen.open("outlook")

    assert app.screen.editor.values["outlook"].get() == ME
    app.screen.editor.save()

    assert app.screen.values["outlook"].get() == ME


def test_accounts_outlook_set_up_guides_the_install_when_classic_outlook_is_missing(root, fakes):
    set_up()
    fakes.outlook = MISSING
    app = open_window(root, fakes)

    app.screen.open("outlook")

    editor = app.screen.editor
    assert editor.outlook.lines[0] == outlook_page.MISSING_INTRO
    assert outlook_page.LATER not in editor.outlook.lines  # Accounts has Cancel, not Skip
    editor.save()
    assert app.screen.answers["outlook"].get() == "✗ Choose an account first."


def test_repair_turns_sync_on_again(root, fakes, tmp_path):
    set_up()
    fakes.program = str(tmp_path / "moved" / "pythonw.exe")
    app = open_window(root, fakes)
    assert app.screen.values["sync"].get() == "points to a program that no longer exists"
    (tmp_path / "pythonw.exe").write_text("")
    fakes.program = str(tmp_path / "pythonw.exe")  # what the new task starts

    app.screen.open("sync")

    assert "task" in fakes.done
    assert app.screen.values["sync"].get() == "on: every minute"
    assert "sync" not in app.screen.buttons


def test_accounts_offers_the_desktop_icon_and_add_puts_it_there(root, fakes):
    set_up()
    app = open_window(root, fakes)
    assert app.screen.values["desktop"].get() == "off"

    app.screen.buttons["desktop"].invoke()

    assert "desktop icon" in fakes.done
    assert app.screen.notice.get() == "✓ " + accounts.DESKTOP_ICON_ADDED
    assert app.screen.values["desktop"].get() == "on"
    assert "desktop" not in app.screen.buttons


def test_a_desktop_icon_that_cannot_be_added_says_so(root, fakes):
    set_up()
    fakes.fail = {"desktop icon": RuntimeError("com_error")}
    app = open_window(root, fakes)

    app.screen.buttons["desktop"].invoke()

    assert app.screen.notice.get().startswith("✗ Couldn't add the Desktop icon (RuntimeError).")
    assert app.screen.values["desktop"].get() == "off"


# ---- one window at a time -------------------------------------------------------------


def test_a_second_start_brings_the_open_window_forward_instead():
    forward = []

    assert window.claim_single_window(first=lambda name: True, bring_forward=forward.append)
    assert not window.claim_single_window(first=lambda name: False, bring_forward=forward.append)
    assert forward == [window.TITLE]


def test_opening_on_a_laptop_set_up_before_the_window_adds_its_link_and_shortcuts(fakes, monkeypatch):
    set_up()
    opened = []

    class Root:
        def mainloop(self):
            pass

    monkeypatch.setattr(window, "claim_single_window", lambda: True)
    monkeypatch.setattr(window.tk, "Tk", Root)
    monkeypatch.setattr(window, "App", lambda root, tools, notice="": opened.append(notice))

    assert window.main(fakes.tools()) == 0

    assert {"window link", "mail link", "shortcuts"} <= set(fakes.done)
    assert opened == [""]


# ---- the built app ------------------------------------------------------------------------


def test_accounts_shows_the_version_and_when_it_last_updated_itself(root, fakes):
    state = set_up()
    state.updated_at = "2026-10-05T07:02:00+00:00"
    save_state(state)

    app = open_window(root, fakes)

    when = window.local_time("2026-10-05T07:02:00+00:00")
    assert app.screen.values["version"].get() == f"{__version__}, updated by itself on {when}"
    assert "version" not in app.screen.buttons


def test_before_any_update_accounts_shows_just_the_version(root, fakes):
    set_up()

    app = open_window(root, fakes)

    assert app.screen.values["version"].get() == __version__


def test_the_built_app_offers_repair_when_the_task_runs_another_copy(root, fakes, monkeypatch, tmp_path):
    set_up()
    monkeypatch.setattr(launcher, "frozen", lambda: True)
    (tmp_path / "pythonw.exe").write_text("")
    (tmp_path / "School-Life-Assistant.exe").write_text("")
    fakes.program = str(tmp_path / "pythonw.exe")  # set up from source before
    fakes.me = str(tmp_path / "School-Life-Assistant.exe")
    app = open_window(root, fakes)
    assert app.screen.values["sync"].get() == "runs another copy of School-Life-Assistant"
    fakes.program = fakes.me  # what the new task starts

    app.screen.open("sync")

    assert "task" in fakes.done
    assert app.screen.values["sync"].get() == "on: every minute"


# ---- the website as an app ---------------------------------------------------------------------


def test_accounts_has_a_button_that_opens_the_website_as_an_app(root, fakes):
    set_up()
    app = open_window(root, fakes)

    app.screen.open_button.invoke()

    assert fakes.opened == [SERVER]
```

- [ ] **Step 9: Run them to see them pass**

Run: `.\.venv\Scripts\python.exe -m pytest agent\tests\test_setup_steps.py agent\tests\test_window.py agent\tests\test_accounts.py agent\tests\test_cli.py -v`
Expected: PASS.

- [ ] **Step 10: The self-check loads the window too (Review Focus 5)**

In `agent/tests/test_selfcheck.py`, replace

```python
    assert [check.__name__ for check in selfcheck.CHECKS] == [
        "tk_with_its_files", "outlook_link", "credential_manager", "data_contract", "https_certificates"]
```

with

```python
    assert [check.__name__ for check in selfcheck.CHECKS] == [
        "tk_with_its_files", "outlook_link", "credential_manager", "data_contract", "https_certificates",
        "setup_window"]


def test_the_window_and_its_pages_load():
    selfcheck.setup_window()  # what the built app does: a module PyInstaller missed would fail here
```

Run: `.\.venv\Scripts\python.exe -m pytest agent\tests\test_selfcheck.py -v`
Expected: FAIL with `AttributeError: module 'sla_agent.selfcheck' has no attribute 'setup_window'`.

In `agent/sla_agent/selfcheck.py`, replace

```python
CHECKS = (tk_with_its_files, outlook_link, credential_manager, data_contract, https_certificates)
```

with

```python
def setup_window():
    import sla_agent.window  # noqa: F401  (the setup pages, the Outlook page and what they share come with it)


CHECKS = (tk_with_its_files, outlook_link, credential_manager, data_contract, https_certificates, setup_window)
```

Run: `.\.venv\Scripts\python.exe -m pytest agent\tests\test_selfcheck.py -v`
Expected: PASS.

- [ ] **Step 11: Run everything**

Run: `.\.venv\Scripts\python.exe -m pytest`
Expected: all pass, 0 failed.

- [ ] **Step 12: Commit**

```powershell
git add agent/sla_agent/accounts.py agent/sla_agent/setup_steps.py agent/sla_agent/window.py agent/sla_agent/window_parts.py agent/sla_agent/cli.py agent/sla_agent/selfcheck.py agent/tests/accounts_fakes.py agent/tests/test_accounts.py agent/tests/test_setup_steps.py agent/tests/test_window.py agent/tests/test_cli.py agent/tests/test_selfcheck.py
git commit -m @'
feat(agent): setup pages: step 1 connects to the website with a device-key guide, Paste and a copied key filled in; step 2 saves EduSoft and turns on sync; the last page asks about the Desktop icon

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
'@
```

---

### Task 4: Setup steps 3 and 4: Blackboard and Outlook, each with Skip

**Files:**
- Modify: `agent/sla_agent/setup_steps.py`
- Test: `agent/tests/test_setup_steps.py`

**Interfaces:**
- Consumes:
  - Task 3: `setup_steps.Page` and `SetupSteps.after`.
  - Task 2: `OutlookPage(app, parent, variable, skippable, changed)`, `.ready` and `.lines`.
  - `accounts.change_blackboard` and `accounts.choose_outlook`.
- Produces:
  - `setup_steps.BLACKBOARD_HINT`.
  - `BlackboardPage`, with `.skip_button` and `.next_button`.
  - `OutlookStep`, with `.outlook`, `.skip_button` and `.next_button`.
  - `STEPS = (SitePage, EdusoftPage, BlackboardPage, OutlookStep)`.

- [ ] **Step 1: Write the failing tests**

In `agent/tests/test_setup_steps.py`:

1. Replace `from agent.tests.accounts_fakes import KEY, PASSWORD, SERVER, STUDENT, Fakes` with `from agent.tests.accounts_fakes import BB_PASSWORD, BB_USER, KEY, ME, PASSWORD, SERVER, STUDENT, Fakes`.
2. Replace `from sla_agent import accounts, credentials, launcher, setup_steps, window, window_parts` with `from sla_agent import accounts, credentials, launcher, outlook_page, setup_steps, window, window_parts`.
3. After the line `from sla_agent.log import setup_logging` add `from sla_agent.outlook_reader import MISSING`.
4. Replace the helper `to_the_end` with:

```python
def to_the_end(steps):
    """Through every step, skipping what can be skipped: on to the last page."""
    through_step_2(steps)
    steps.page.skip_button.invoke()  # Blackboard
    steps.page.skip_button.invoke()  # Outlook
```

5. In `test_a_new_laptop_starts_at_step_1_with_its_guide`, replace `"Step 1 of 2 · Connect to the website"` with `"Step 1 of 4 · Connect to the website"`.
6. At the end of the file add:

```python
# ---- steps 3 and 4: Blackboard and Outlook ------------------------------------------------


def test_there_are_four_steps(root, fakes):
    assert setup_steps.STEPS == (setup_steps.SitePage, setup_steps.EdusoftPage, setup_steps.BlackboardPage,
                                 setup_steps.OutlookStep)


def test_after_edusoft_comes_blackboard_and_skip_saves_nothing(root, fakes):
    _, steps = start(root, fakes)
    through_step_2(steps)

    assert isinstance(steps.page, setup_steps.BlackboardPage)
    assert steps.page.header() == "Step 3 of 4 · Blackboard (optional)"

    steps.page.skip_button.invoke()

    assert isinstance(steps.page, setup_steps.OutlookStep)
    assert fakes.blackboard.logins == []
    assert load_state().blackboard_username is None


def test_a_blackboard_login_is_checked_and_saved(root, fakes):
    _, steps = start(root, fakes)
    through_step_2(steps)
    steps.values["bb_user"].set(BB_USER)
    steps.values["bb_password"].set(BB_PASSWORD)

    steps.page.next_button.invoke()

    assert credentials.load_blackboard(BB_USER) == BB_PASSWORD
    assert steps.values["bb_password"].get() == ""
    assert isinstance(steps.page, setup_steps.OutlookStep)


def test_a_wrong_blackboard_password_stays_on_the_page(root, fakes):
    fakes.blackboard.login_error = BadCredentials("rejected")
    _, steps = start(root, fakes)
    through_step_2(steps)
    steps.values["bb_user"].set(BB_USER)
    steps.values["bb_password"].set("wrong")

    steps.page.next_button.invoke()

    assert isinstance(steps.page, setup_steps.BlackboardPage)
    assert steps.page.answer.get() == "✗ Blackboard rejected the username or password. Nothing was saved."
    assert not disabled(steps.page.skip_button) and not disabled(steps.page.next_button)


def test_the_outlook_step_chooses_the_iu_account_and_next_saves_it(root, fakes):
    _, steps = start(root, fakes)
    through_step_2(steps)
    steps.page.skip_button.invoke()  # Blackboard

    assert steps.page.header() == "Step 4 of 4 · Outlook (optional)"
    assert steps.values["outlook"].get() == ME
    assert not disabled(steps.page.next_button)

    steps.page.next_button.invoke()

    assert load_state().outlook_account == ME
    assert isinstance(steps.page, setup_steps.DonePage)
    assert f"✓ Outlook: {ME}" in steps.page.lines


def test_without_classic_outlook_next_is_off_and_skip_goes_on(root, fakes):
    fakes.outlook = MISSING
    _, steps = start(root, fakes)
    through_step_2(steps)
    steps.page.skip_button.invoke()  # Blackboard

    assert steps.page.outlook.lines[0] == outlook_page.MISSING_INTRO
    assert outlook_page.LATER in steps.page.outlook.lines
    assert disabled(steps.page.next_button)

    steps.page.skip_button.invoke()

    assert isinstance(steps.page, setup_steps.DonePage)
    assert "– Outlook: skipped: set it up later in Accounts" in steps.page.lines


def test_skip_works_while_outlook_is_still_being_looked_for(root, fakes):  # Review Focus 4
    held = []
    app, steps = start(root, fakes)
    through_step_2(steps)
    app.run = lambda work, done: held.append((work, done))

    steps.page.skip_button.invoke()  # Blackboard: the Outlook step starts looking

    assert steps.page.outlook.lines == [outlook_page.LOOKING]
    assert disabled(steps.page.next_button) and not disabled(steps.page.skip_button)
    steps.page.skip_button.invoke()
    work, done = held.pop()
    done(window_parts.attempt(work))  # Outlook answers after the student moved on

    assert isinstance(steps.page, setup_steps.DonePage)


def test_the_last_page_lists_blackboard_and_outlook_once_set_up(root, fakes):
    _, steps = start(root, fakes)
    through_step_2(steps)
    steps.values["bb_user"].set(BB_USER)
    steps.values["bb_password"].set(BB_PASSWORD)
    steps.page.next_button.invoke()
    steps.page.next_button.invoke()  # Outlook: the IU account

    assert steps.page.lines == [setup_steps.ALL_SET, f"✓ EduSoft: {STUDENT}", f"✓ Blackboard: {BB_USER}",
                                f"✓ Outlook: {ME}"]
```

- [ ] **Step 2: Run them to see them fail**

Run: `.\.venv\Scripts\python.exe -m pytest agent\tests\test_setup_steps.py -v`
Expected: FAIL. `AttributeError: module 'sla_agent.setup_steps' has no attribute 'BlackboardPage'`, and the last-page tests stop on the first `skip_button`.

- [ ] **Step 3: Add the two steps**

In `agent/sla_agent/setup_steps.py`:

Replace the module docstring's first paragraph

```python
"""The setup pages (spec 2026-10-02-easy-install-design.md, 3): a laptop that isn't set up gets one page per step,
each with its own guide, instead of one long form. Step 1 connects to the website with a device key and saves
nothing; step 2 checks EduSoft, then saves both and turns on automatic sync; the last page asks about the Desktop
icon and opens School-Life-Assistant.
```

with

```python
"""The setup pages (spec 2026-10-02-easy-install-design.md, 3): a laptop that isn't set up gets one page per step,
each with its own guide, instead of one long form. Step 1 connects to the website with a device key and saves
nothing; step 2 checks EduSoft, then saves both and turns on automatic sync; steps 3 and 4 add Blackboard and
Outlook, or skip them; the last page asks about the Desktop icon and opens School-Life-Assistant.
```

After `from sla_agent.log import protect` add `from sla_agent.outlook_page import OutlookPage`.

After the line `EDUSOFT_HINT = …` add:

```python
BLACKBOARD_HINT = "The username and password you use on blackboard.hcmiu.edu.vn."
```

Between the classes `EdusoftPage` and `DonePage`, add:

```python
class BlackboardPage(Page):
    """Step 3 (optional): one Blackboard login attempt, saved when it passes; or Skip."""

    title = "Blackboard (optional)"

    def __init__(self, steps):
        super().__init__(steps)
        self.add_line(BLACKBOARD_HINT)
        self.add_field("Username", "bb_user")
        self.add_field("Password", "bb_password", secret=True)
        self.add_answer()
        self.skip_button, self.next_button = self.add_bar(
            ("Skip", lambda: steps.show(steps.after(BlackboardPage))), ("Next →", self.save))

    def save(self):
        username = self.steps.values["bb_user"].get().strip()
        password = self.steps.values["bb_password"].get()
        protect(password)
        self.busy(self.skip_button, self.next_button)
        self.app.run(lambda: accounts.change_blackboard(load_state(), username, password, self.app.tools),
                     self.saved)

    def saved(self, result):
        if not self.current():
            return
        if not result.ok:
            self.answer.set(mark(result))
            self.free(self.skip_button, self.next_button)
            return
        self.steps.values["bb_password"].set("")
        self.steps.results["blackboard"] = result
        self.steps.show(self.steps.after(BlackboardPage))


class OutlookStep(Page):
    """Step 4 (optional): the Outlook page (outlook_page.py) with Skip. Next, on only once an account is chosen,
    saves it (accounts.choose_outlook)."""

    title = "Outlook (optional)"

    def __init__(self, steps):
        super().__init__(steps)
        self.next_button = None  # the Outlook page says it changed while it is being made
        self.outlook = OutlookPage(self.app, self.frame, steps.values["outlook"], skippable=True,
                                   changed=self.changed)
        self.outlook.frame.grid(row=self.next_row(), column=0, columnspan=3, sticky="ew")
        self.add_answer()
        self.skip_button, self.next_button = self.add_bar(
            ("Skip", lambda: steps.show(steps.after(OutlookStep))), ("Next →", self.save))
        self.changed()

    def changed(self):
        if self.next_button is not None:
            self.next_button.state(["!disabled" if self.outlook.ready else "disabled"])

    def save(self):
        address = self.steps.values["outlook"].get()
        self.busy(self.skip_button, self.next_button)
        self.app.run(lambda: accounts.choose_outlook(load_state(), address, self.app.tools), self.saved)

    def saved(self, result):
        if not self.current():
            return
        if not result.ok:
            self.answer.set(mark(result))
            self.free(self.skip_button)
            self.changed()
            return
        self.steps.results["outlook"] = result
        self.steps.show(self.steps.after(OutlookStep))
```

Replace

```python
STEPS = (SitePage, EdusoftPage)
```

with

```python
STEPS = (SitePage, EdusoftPage, BlackboardPage, OutlookStep)
```

- [ ] **Step 4: Run them to see them pass, then everything**

Run: `.\.venv\Scripts\python.exe -m pytest agent\tests\test_setup_steps.py -v`
Expected: PASS.

Run: `.\.venv\Scripts\python.exe -m pytest`
Expected: all pass, 0 failed.

- [ ] **Step 5: Commit**

```powershell
git add agent/sla_agent/setup_steps.py agent/tests/test_setup_steps.py
git commit -m @'
feat(agent): setup steps 3 and 4: Blackboard and Outlook, each with Skip

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
'@
```

---

### Task 5: The download page, in English and Vietnamese, on GitHub Pages

**Files:**
- Create: `pages/index.html`, `pages/style.css`
- Create: `.github/workflows/pages.yml`
- Test: `deploy/tests/test_download_page.py` (already inside pytest's `testpaths`)

**Interfaces:**
- Consumes: nothing from the agent.
- Produces:
  - The page at `https://nguyenkhangvy.github.io/School-Life-Assistant/`, once published (Task 7).
  - `<main data-lang="en">` and `<main data-lang="vi" hidden>`. Each has one `a.download`, an `ol.steps` with three items, and a `dl.questions` with three questions.
  - Screenshots go in `pages/img/` (Task 7).

- [ ] **Step 1: Write the failing tests**

Create `deploy/tests/test_download_page.py`:

```python
"""The download page (pages/, published by .github/workflows/pages.yml; spec 2026-10-02-easy-install-design.md, 2):
both languages say the same things and download the newest release, and the page holds nothing it can't show."""

from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PAGES = ROOT / "pages"
DOWNLOAD = "https://github.com/nguyenkhangvy/School-Life-Assistant/releases/latest/download/School-Life-Assistant.exe"


class Reader(HTMLParser):
    """Per language (each <main data-lang>): whether it starts hidden, its download links, how many steps and
    questions it has. And every image the page shows."""

    def __init__(self):
        super().__init__()
        self.lang = None
        self.in_steps = False
        self.sections = {}
        self.images = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "main" and "data-lang" in attrs:
            self.lang = attrs["data-lang"]
            self.sections[self.lang] = {"hidden": "hidden" in attrs, "downloads": [], "steps": 0, "questions": 0}
        section = self.sections.get(self.lang)
        if tag == "img":
            self.images.append(attrs.get("src", ""))
        if tag == "ol" and "steps" in (attrs.get("class") or "").split():
            self.in_steps = True
        if section is None:
            return
        if tag == "a" and "download" in (attrs.get("class") or "").split():
            section["downloads"].append(attrs.get("href"))
        if tag == "li" and self.in_steps:
            section["steps"] += 1
        if tag == "dt":
            section["questions"] += 1

    def handle_endtag(self, tag):
        if tag == "main":
            self.lang = None
        if tag == "ol":
            self.in_steps = False


def read():
    reader = Reader()
    reader.feed((PAGES / "index.html").read_text(encoding="utf-8"))
    return reader


def test_the_page_is_in_english_and_vietnamese_and_shows_english_without_javascript():
    sections = read().sections

    assert set(sections) == {"en", "vi"}
    assert not sections["en"]["hidden"] and sections["vi"]["hidden"]


def test_both_languages_download_the_newest_release_and_say_the_same_things():
    for lang, section in read().sections.items():
        assert section["downloads"] == [DOWNLOAD], lang
        assert section["steps"] == 3, lang
        assert section["questions"] == 3, lang


def test_every_image_the_page_shows_is_there():
    for source in read().images:
        assert (PAGES / source).is_file(), source


def test_the_page_never_names_the_websites_address():
    """The website will move (it isn't hosted yet): the app's step 1 names it, the download page never does."""
    text = (PAGES / "index.html").read_text(encoding="utf-8")

    for address in ("onrender.com", "duckdns.org", "localhost"):
        assert address not in text


def test_its_stylesheet_is_there():
    assert (PAGES / "style.css").is_file()


def test_only_the_school_life_assistant_repository_publishes_it():
    workflow = (ROOT / ".github" / "workflows" / "pages.yml").read_text(encoding="utf-8")

    assert "if: github.repository == 'nguyenkhangvy/School-Life-Assistant'" in workflow
    assert "path: pages" in workflow
```

- [ ] **Step 2: Run them to see them fail**

Run: `.\.venv\Scripts\python.exe -m pytest deploy\tests\test_download_page.py -v`
Expected: FAIL with `FileNotFoundError: … pages\index.html` (and `pages.yml`).

- [ ] **Step 3: Write the page, its stylesheet and its workflow**

Create `pages/index.html`:

```html
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>School-Life-Assistant for Windows</title>
  <meta name="description" content="Your IU timetable, exams, tuition bills, Blackboard and mail on one calendar.">
  <link rel="stylesheet" href="style.css">
</head>
<body>
  <!-- The download page (spec 2026-10-02-easy-install-design.md, 2). Both languages say the same things:
       deploy/tests/test_download_page.py checks it. Without JavaScript the English text shows. -->
  <nav class="languages" aria-label="Language" hidden>
    <button type="button" data-show="en">English</button>
    <button type="button" data-show="vi">Tiếng Việt</button>
  </nav>

  <main data-lang="en" lang="en">
    <h1>School-Life-Assistant</h1>
    <p class="tagline">Your IU timetable, exams, tuition bills, Blackboard and mail on one calendar.</p>
    <p><a class="download" href="https://github.com/nguyenkhangvy/School-Life-Assistant/releases/latest/download/School-Life-Assistant.exe">Download for Windows</a></p>
    <p class="muted">Free. Windows 10 and 11. It updates itself.</p>

    <h2>You'll need</h2>
    <ul>
      <li>A Windows 10 or 11 laptop.</li>
      <li>Your EduSoft student ID and password.</li>
      <li>If you like: your Blackboard login, and Outlook (classic) with your IU email.</li>
    </ul>
    <p>The app shows you how to connect it to your School-Life-Assistant account.</p>

    <h2>Install it in three steps</h2>
    <ol class="steps">
      <li><strong>If your browser warns about the download, keep it.</strong> In Edge: press <b>…</b> next to the
        file, then <b>Keep</b>, then <b>Show more</b>, then <b>Keep anyway</b>.</li>
      <li><strong>Open the downloaded file.</strong> If Windows says “Windows protected your PC”, press
        <b>More info</b>, then <b>Run anyway</b>. It needs no administrator rights.</li>
      <li><strong>The School-Life-Assistant window opens</strong> and walks you through the rest: a device key from
        the website, your EduSoft login, then Blackboard and Outlook if you want them, and an icon on your Desktop if
        you like.</li>
    </ol>

    <h2>Questions</h2>
    <dl class="questions">
      <dt>Are my passwords safe?</dt>
      <dd>They stay on your laptop, in Windows Credential Manager: the School-Life-Assistant website never sees them.
        Your emails are sorted on your laptop, and their text is never sent. The code is public on
        <a href="https://github.com/nguyenkhangvy/School-Life-Assistant">GitHub</a>.</dd>
      <dt>Do I need to update it?</dt>
      <dd>No. It installs new versions by itself.</dd>
      <dt>On your phone?</dt>
      <dd>School-Life-Assistant runs on Windows laptops: open this page on your laptop.</dd>
    </dl>
  </main>

  <main data-lang="vi" lang="vi" hidden>
    <h1>School-Life-Assistant</h1>
    <p class="tagline">Thời khóa biểu, lịch thi, học phí, Blackboard và email IU của bạn trên cùng một lịch.</p>
    <p><a class="download" href="https://github.com/nguyenkhangvy/School-Life-Assistant/releases/latest/download/School-Life-Assistant.exe">Tải về cho Windows</a></p>
    <p class="muted">Miễn phí. Windows 10 và 11. Tự cập nhật.</p>

    <h2>Bạn cần có</h2>
    <ul>
      <li>Một laptop chạy Windows 10 hoặc 11.</li>
      <li>Mã số sinh viên và mật khẩu EduSoft của bạn.</li>
      <li>Nếu muốn: tài khoản Blackboard, và Outlook (classic) với email IU của bạn.</li>
    </ul>
    <p>Ứng dụng sẽ hướng dẫn bạn kết nối với tài khoản School-Life-Assistant của mình.</p>

    <h2>Cài đặt trong ba bước</h2>
    <ol class="steps">
      <li><strong>Nếu trình duyệt cảnh báo về tệp tải về, hãy giữ lại tệp.</strong> Trong Edge: nhấn <b>…</b> cạnh
        tên tệp, rồi <b>Keep</b> (Giữ lại), rồi <b>Show more</b>, rồi <b>Keep anyway</b>.</li>
      <li><strong>Mở tệp vừa tải về.</strong> Nếu Windows báo “Windows protected your PC” (Windows đã bảo vệ PC của
        bạn), nhấn <b>More info</b> (Thông tin thêm), rồi <b>Run anyway</b> (Vẫn chạy). Không cần quyền quản trị
        (administrator).</li>
      <li><strong>Cửa sổ School-Life-Assistant sẽ mở ra</strong> và hướng dẫn bạn phần còn lại: mã thiết bị
        (device key) từ trang web, tài khoản EduSoft, rồi Blackboard và Outlook nếu bạn muốn, và biểu tượng trên màn
        hình nền (Desktop) nếu bạn thích.</li>
    </ol>

    <h2>Câu hỏi thường gặp</h2>
    <dl class="questions">
      <dt>Mật khẩu của tôi có an toàn không?</dt>
      <dd>Mật khẩu chỉ nằm trên laptop của bạn, trong Windows Credential Manager: trang web School-Life-Assistant
        không bao giờ thấy chúng. Email được phân loại ngay trên laptop, và nội dung email không bao giờ được gửi đi.
        Mã nguồn công khai trên <a href="https://github.com/nguyenkhangvy/School-Life-Assistant">GitHub</a>.</dd>
      <dt>Tôi có cần tự cập nhật không?</dt>
      <dd>Không. Ứng dụng tự cài phiên bản mới.</dd>
      <dt>Bạn đang dùng điện thoại?</dt>
      <dd>School-Life-Assistant chạy trên laptop Windows: hãy mở trang này trên laptop của bạn.</dd>
    </dl>
  </main>

  <script>
    // English or Vietnamese: the visitor's last choice, else the browser's language.
    (function () {
      var sections = document.querySelectorAll("main[data-lang]");
      var buttons = document.querySelectorAll("[data-show]");

      function show(lang) {
        sections.forEach(function (section) { section.hidden = section.dataset.lang !== lang; });
        buttons.forEach(function (button) {
          button.setAttribute("aria-pressed", String(button.dataset.show === lang));
        });
        document.documentElement.lang = lang;
      }

      var saved = null;
      try { saved = localStorage.getItem("lang"); } catch (error) { /* storage blocked: nothing remembered */ }
      var browser = (navigator.language || "").toLowerCase().indexOf("vi") === 0 ? "vi" : "en";
      show(saved === "en" || saved === "vi" ? saved : browser);
      document.querySelector(".languages").hidden = false;
      buttons.forEach(function (button) {
        button.addEventListener("click", function () {
          show(button.dataset.show);
          try { localStorage.setItem("lang", button.dataset.show); } catch (error) { /* not remembered */ }
        });
      });
    })();
  </script>
</body>
</html>
```

Create `pages/style.css`:

```css
/* The download page (index.html): readable on a laptop and a phone, light and dark. */
:root {
  --text: #1f2933;
  --muted: #5f6b76;
  --background: #ffffff;
  --accent: #0b5cad;
  --accent-text: #ffffff;
  --line: #d9dee3;
  font-family: "Segoe UI", system-ui, -apple-system, sans-serif;
  line-height: 1.55;
}

@media (prefers-color-scheme: dark) {
  :root {
    --text: #e6e9ec;
    --muted: #a3adb7;
    --background: #15191d;
    --accent: #4c9be8;
    --accent-text: #0b1218;
    --line: #2c333a;
  }
}

/* An author's display rule would otherwise beat the hidden attribute. */
[hidden] { display: none !important; }

body {
  margin: 0 auto;
  max-width: 42rem;
  padding: 1.5rem 1rem 3rem;
  color: var(--text);
  background: var(--background);
}

h1 { font-size: 2rem; margin: 0.5rem 0 0.25rem; }
h2 { font-size: 1.25rem; margin-top: 2rem; }
a { color: var(--accent); }
.tagline { font-size: 1.125rem; margin-top: 0; }
.muted { color: var(--muted); font-size: 0.9375rem; }

.download {
  display: inline-block;
  padding: 0.75rem 1.5rem;
  border-radius: 0.5rem;
  background: var(--accent);
  color: var(--accent-text);
  font-size: 1.125rem;
  font-weight: 600;
  text-decoration: none;
}
.download:hover, .download:focus-visible { filter: brightness(1.1); }

.steps li { margin-bottom: 0.75rem; }
.steps img {
  display: block;
  max-width: 100%;
  margin-top: 0.5rem;
  border: 1px solid var(--line);
  border-radius: 0.375rem;
}

.questions dt { font-weight: 600; margin-top: 1rem; }
.questions dd { margin: 0.25rem 0 0; }

.languages { display: flex; justify-content: flex-end; gap: 0.5rem; }
.languages button {
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 0.25rem 0.75rem;
  background: transparent;
  color: var(--text);
  font: inherit;
  cursor: pointer;
}
.languages button[aria-pressed="true"] { border-color: var(--accent); color: var(--accent); font-weight: 600; }
```

Create `.github/workflows/pages.yml`:

```yaml
# The download page (pages/; README, "The download page"), published on GitHub Pages at
# https://nguyenkhangvy.github.io/School-Life-Assistant/ whenever a merge to main changes it.
name: download page

on:
  push:
    branches: [main]
    paths: ["pages/**", ".github/workflows/pages.yml"]
  workflow_dispatch:

permissions:
  contents: read
  pages: write
  id-token: write

# One publish at a time; a newer one waits instead of cancelling one halfway.
concurrency:
  group: pages
  cancel-in-progress: false

jobs:
  publish:
    # main is also pushed to the dsa and ooad repositories, which have no download page.
    if: github.repository == 'nguyenkhangvy/School-Life-Assistant'
    runs-on: ubuntu-latest
    environment:
      name: github-pages
      url: ${{ steps.deployment.outputs.page_url }}

    steps:
      - uses: actions/checkout@v5

      - uses: actions/configure-pages@v6

      - uses: actions/upload-pages-artifact@v5
        with:
          path: pages

      - id: deployment
        uses: actions/deploy-pages@v5
```

(These are the newest major versions as of 2026-10-02: configure-pages 6.0.0, upload-pages-artifact 5.0.0, deploy-pages 5.0.1. `checkout@v5` matches the other workflows.)

- [ ] **Step 4: Run them to see them pass, and look at the page**

Run: `.\.venv\Scripts\python.exe -m pytest deploy\tests\test_download_page.py -v`
Expected: PASS.

Run: `Start-Process pages\index.html`. The page opens in the default browser. Check that:
- **English | Tiếng Việt** switches the text, and a reload keeps the choice.
- The big button points to the GitHub link: hover it and read the status bar. Don't download.
- The page reads well at phone width (DevTools' device mode) and with Windows in dark mode.

- [ ] **Step 5: Commit**

```powershell
git add pages/index.html pages/style.css .github/workflows/pages.yml deploy/tests/test_download_page.py
git commit -m @'
feat(pages): the download page on GitHub Pages, in English and Vietnamese, with the steps through Windows' warnings

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
'@
```

---

### Task 6: The website and the README point to the download page

**Files:**
- Modify: `web/src/main/resources/templates/school/devices.html`
- Test: `web/src/test/java/vn/edu/hcmiu/sla/school/pages/DevicesPageTest.java`
- Modify: `README.md` ("Install on your laptop (students)"; a new "The download page" under "Making a release")

**Interfaces:**
- Consumes: the page address `https://nguyenkhangvy.github.io/School-Life-Assistant/` (Task 5).
- Produces: nothing other tasks use.

- [ ] **Step 1: Write the failing test**

In `DevicesPageTest.java`:

After the `DOWNLOAD` constant add:

```java
    static final String GUIDE = "https://nguyenkhangvy.github.io/School-Life-Assistant/";
```

In `aNewDeviceKeyIsShownOnceAndWorks`, replace

```java
        assertThat(html).contains("open School-Life-Assistant on the laptop", "Start menu")
                .doesNotContain("School-Life-Assistant.cmd");
```

with

```java
        assertThat(html).contains("go back to the School-Life-Assistant window on your laptop", "Paste")
                .doesNotContain("School-Life-Assistant.cmd");
```

Replace the test `thePageOffersTheNewestWindowsDownloadAndSaysWhatWindowsWillAsk` with:

```java
    @Test
    void thePageOffersTheNewestWindowsDownloadItsGuideAndSaysWhatWindowsWillAsk() throws Exception {
        assertThat(devicesPage()).contains("href=\"" + DOWNLOAD + "\"", "Download School-Life-Assistant for Windows",
                "href=\"" + GUIDE + "\"", "Step-by-step guide", "Windows protected your PC", "More info", "Run anyway");
    }
```

- [ ] **Step 2: Run it to see it fail**

Run: `cd web; .\mvnw.cmd -B -q test "-Dtest=DevicesPageTest"; cd ..`
Expected: FAIL. Both tests fail: the page has no "go back to the School-Life-Assistant window on your laptop" and no `GUIDE` link.

- [ ] **Step 3: Change the Devices page**

In `web/src/main/resources/templates/school/devices.html`, replace

```html
      <p><strong>Copy it now. It won't be shown again.</strong> Then open School-Life-Assistant on the laptop
         (from the Start menu, or by double-clicking the file you downloaded) and paste it when asked.</p>
```

with (keep the asserted words on one line; Thymeleaf keeps line breaks):

```html
      <p><strong>Copy it now. It won't be shown again.</strong> Press <strong>Copy</strong>,
         then go back to the School-Life-Assistant window on your laptop: the key is filled in by itself
         (or press <strong>Paste</strong>).</p>
```

and replace

```html
    <p><a class="button" href="https://github.com/nguyenkhangvy/School-Life-Assistant/releases/latest/download/School-Life-Assistant.exe">Download School-Life-Assistant for Windows</a></p>
```

with

```html
    <p><a class="button" href="https://github.com/nguyenkhangvy/School-Life-Assistant/releases/latest/download/School-Life-Assistant.exe">Download School-Life-Assistant for Windows</a>
       <a href="https://nguyenkhangvy.github.io/School-Life-Assistant/">Step-by-step guide</a></p>
```

- [ ] **Step 4: Run it to see it pass**

Run: `cd web; .\mvnw.cmd -B -q test "-Dtest=DevicesPageTest"; cd ..`
Expected: PASS (`BUILD SUCCESS`).

- [ ] **Step 5: The README**

In `README.md`, replace the whole section from `## Install on your laptop (students)` down to (not including) the `---` line after it with:

```markdown
## Install on your laptop (students)

1. Open the download page, https://nguyenkhangvy.github.io/School-Life-Assistant/ (English or Tiếng Việt), and press **Download for Windows**. School → Devices on the website has the same button.
2. Open the downloaded `School-Life-Assistant.exe`. If the browser warns about it, keep it; if Windows says “Windows protected your PC”, press **More info**, then **Run anyway**. It needs no administrator rights.
3. The School-Life-Assistant window walks you through four steps:
   1. Connect to the website. It shows how to get a device key from School → Devices, and fills the key in when you copy it.
   2. EduSoft.
   3. Blackboard, if you want it.
   4. Outlook (classic), if you want it. If Outlook (classic) isn't on your laptop, it shows how to install it.

   At the end it asks whether to put an icon on your Desktop, then opens School-Life-Assistant as an app.

That's all. It syncs by itself every minute, and it installs new versions by itself within a day of their release.
- **School-Life-Assistant**, in the Start menu (and on the Desktop if you chose the icon), opens it again: the website, in its own window.
- **School-Life-Assistant Accounts**, in the Start menu, changes your accounts. It can also add the Desktop icon later.

```

In `README.md`, at the end of "Making a release": after the paragraph that starts `To build on your own laptop:` and before the `---` line under it, add:

```markdown
### The download page

`pages/` is the download page, https://nguyenkhangvy.github.io/School-Life-Assistant/, in English and Vietnamese. GitHub Actions (`download page`) publishes it whenever a merge to `main` changes `pages/`, or when started by hand from the Actions tab. Once, before the first publish: on GitHub, **Settings → Pages → Source: GitHub Actions**. Until then the workflow fails and says Pages isn't enabled. Screenshots go in `pages/img/`; `pytest` checks that every image the page shows is there and that both languages say the same things.

```

- [ ] **Step 6: Commit**

```powershell
git add web/src/main/resources/templates/school/devices.html web/src/test/java/vn/edu/hcmiu/sla/school/pages/DevicesPageTest.java README.md
git commit -m @'
docs: the Devices page and the README point to the download page; the new key fills itself in

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
'@
```

---

### Task 7: Version 0.3.0, the release, and the by-hand test

**Files:**
- Modify: `agent/sla_agent/__init__.py`
- Modify: `docs/superpowers/specs/2026-10-02-easy-install-design.md` (Status line)
- Later: `pages/img/*.png` and `<img>` tags in `pages/index.html` (Step 7)

**Interfaces:**
- Consumes: everything above.
- Produces: release v0.3.0 (published only with the student's go-ahead).

- [ ] **Step 1: Raise the version and mark the spec built**

In `agent/sla_agent/__init__.py`, replace `__version__ = "0.2.2"` with `__version__ = "0.3.0"`.

In `docs/superpowers/specs/2026-10-02-easy-install-design.md`, replace `**Status:** Draft, waiting for review` with `**Status:** Built (see docs/superpowers/plans/2026-10-02-easy-install.md)`.

- [ ] **Step 2: Run every test**

Run: `.\.venv\Scripts\python.exe -m pytest`
Expected: all pass, 0 failed.

Run: `cd web; .\mvnw.cmd -B test; cd ..`
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Build the app and the setup, and start them**

Run: `.\.venv\Scripts\python.exe -m pip install -r agent\packaging\requirements.txt`, then `.\.venv\Scripts\python.exe agent\packaging\build.py`, then `.\agent\packaging\smoke-test.ps1`.
Expected: the last line says `The app and the setup of School-Life-Assistant 0.3.0 work.` The self-check now also loads the window and its pages.

(`build/` and `dist/` are in `.gitignore`.)

- [ ] **Step 4: Commit**

```powershell
git add agent/sla_agent/__init__.py docs/superpowers/specs/2026-10-02-easy-install-design.md
git commit -m @'
chore(agent): version 0.3.0; the easy install spec is built

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
'@
```

- [ ] **Step 5: With the student's go-ahead: pull request, merge, Pages, release**

These are visible to others, so ask the student before each one:
1. Push `easy-install` and open a pull request. Merge it once CI passes, including the Windows `exe` job.
2. On GitHub, **Settings → Pages → Source: GitHub Actions**. Then run the `download page` workflow by hand from the Actions tab, or let the merge run it. Open https://nguyenkhangvy.github.io/School-Life-Assistant/.
3. Tag the release (README, "Making a release"): `git switch main; git pull; git tag v0.3.0; git push origin v0.3.0`. Wait for the `release` workflow, then upload `School-Life-Assistant.exe` to virustotal.com.

- [ ] **Step 6: The by-hand test**

Use a second Windows user, or a classmate's laptop, so the student's own setup isn't touched. Start the website on the laptop first (`cd web; .\mvnw.cmd spring-boot:run`); a second Windows user on the same laptop reaches it at `http://localhost:5000`.
1. Open the download page. Switch to Tiếng Việt and back, then press **Download for Windows** in Edge.
2. Go through Edge's warning and "Windows protected your PC". Take three screenshots (Win+Shift+S): the download warning, the warning after **More info**, and the window's step 1. On a Windows set to Vietnamese, note the button names shown and correct the Vietnamese page if they differ.
3. In the window, step 1:
   - Press **Change** and type `http://localhost:5000`, then press Enter.
   - Press **Open the website**, then create an account and add a device.
   - Press **Copy** on the Devices page and come back to the window. The key should fill in and show ✓.
4. Step 2: enter EduSoft. Step 3: **Skip**. Step 4 should list the IU account if Outlook (classic) is signed in. Otherwise check that the page shows install guide A or sign-in guide B.
   - Press **Install Office** and sign in on microsoft365.com with an IU email. Check that the buttons are still called **Install apps** and **Microsoft 365 apps**.
   - If Microsoft renamed them, change `MISSING_STEPS` in `outlook_page.py` (and its test) to the names on screen, in a small follow-up commit.
5. All set: press **No, thanks**. No icon should appear on the Desktop; Accounts says "Desktop icon: off". Press **Add**: the icon appears.
6. Optional: delete the icon, then delete `update_checked_at` from `%LOCALAPPDATA%\SchoolLifeAssistant\state.json` after a later release. The icon must stay deleted.

- [ ] **Step 7: Add the screenshots to the page**

Save the three screenshots as `pages/img/download-warning.png`, `pages/img/windows-protected.png` and `pages/img/setup-step-1.png`. In `pages/index.html`, add to each step's `<li>`, just before `</li>`:
- **English page:** `<img src="img/download-warning.png" alt="Edge's download warning, with … and Keep">`, `<img src="img/windows-protected.png" alt="Windows protected your PC, after More info: Run anyway">` and `<img src="img/setup-step-1.png" alt="The School-Life-Assistant window, step 1">`.
- **Vietnamese page:** the same images with `alt="Cảnh báo tải về của Edge, với … và Keep"`, `alt="Windows protected your PC, sau khi nhấn More info: Run anyway"` and `alt="Cửa sổ School-Life-Assistant, bước 1"`.

Run: `.\.venv\Scripts\python.exe -m pytest deploy\tests\test_download_page.py -v`
Expected: PASS. Every image the page shows is there.

```powershell
git add pages/img pages/index.html
git commit -m @'
docs(pages): screenshots for the three install steps, in both languages

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
'@
```
