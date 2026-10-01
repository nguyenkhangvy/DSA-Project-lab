# Accounts window Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A School-Life-Assistant window on the laptop replaces the terminal for setting up the agent and changing its accounts, and the website gets an Accounts page that opens it.

**Architecture:** A new `accounts.py` holds the check-and-save logic (no screen) and is used by both `sla-agent setup` and a new Tkinter window (`window.py`, started by `sla-agent window`). A `sla-agent:` link type, Desktop and Start menu shortcuts, and a double-clickable `School-Life-Assistant.cmd` open the window. The website adds `/school/accounts` (status per system, laptops, a `sla-agent:accounts` link) and points its "run `sla-agent setup`" messages there.

**Tech Stack:** Python 3.12 (Tkinter 8.6, pywin32, keyring), pytest; Spring Boot 4.1.1, Thymeleaf, MockMvc.

**Spec:** `docs/superpowers/specs/2026-10-01-accounts-window-design.md`

## Global Constraints

- Passwords and the device key go only to Windows Credential Manager (`credentials.py`); each is passed to `log.protect()` as soon as it is read; never to state.json, a log line, the screen after saving, or the website.
- A login is checked once before it is saved; a failed check saves nothing and keeps the old login.
- Messages reuse `sla-agent setup`'s words: "EduSoft rejected the student ID or password. Nothing was saved.", "The web app rejected this device key. Create a new one on the Devices page. Nothing was saved.", "Couldn't reach the web app: {error} Nothing was saved.", "EduSoft asked for extra verification (CAPTCHA or code), so automatic sync can't be used. Nothing was saved. You can still use `sla-agent import` with pages saved from your browser.", "Couldn't check your EduSoft login: {error} Nothing was saved; try again later.", "Saved. Your password is in Windows Credential Manager, not in any file.", "Blackboard username and password are both needed. Nothing was saved.", "Blackboard rejected the username or password. Nothing was saved.", "Blackboard asked for extra verification, so automatic Blackboard sync can't be used. Nothing was saved.", "Couldn't check your Blackboard login: {error} Nothing was saved; try again later.", "Blackboard saved. Its password is in Windows Credential Manager too.".
- Window title `School-Life-Assistant`; mutex `SchoolLifeAssistant-Window`; link `sla-agent:accounts`; registry key `HKEY_CURRENT_USER\Software\Classes\sla-agent`, command `"<pythonw>" -m sla_agent window "%1"`; shortcut file `School-Life-Assistant.lnk` on the Desktop and in the Start menu Programs folder, arguments `-m sla_agent window`; default address `http://localhost:5000`.
- Tk is touched only from its own thread; checks run through the window's `run(work, done)`.
- Tests never touch the real Credential Manager, registry, Desktop or Start menu (fakes in `agent/tests/conftest.py`).
- No new dependency (Tkinter ships with Python; pywin32 is already required).
- Website: route `GET /school/accounts`, School tab "Accounts" after Devices; the page has no password field and no form.
- Python tests: `.venv/Scripts/python.exe -m pytest` (pyproject adds `-q`; the last line reads "N passed"). Java tests: `cd web && ./mvnw -B test` (expect `Tests run: N, Failures: 0, Errors: 0`).
- Every commit message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

- **A device key pasted with spaces or a line break around it** (copied from the Devices page): trimmed and accepted (Task 4 pins it).
- **An address typed with a trailing slash** (`https://sla.example.com/`): saved without it, so the key is found again at the next sync (Task 2 pins it).
- **A Desktop or Start menu path with Vietnamese letters** (OneDrive's `Máy tính` on the student's laptop): the shortcuts are made there (Task 1 pins it).
- **The Outlook account list arriving after the form was saved and replaced by Accounts**: ignored, no error (Task 4 pins it).
- **An unexpected error inside a check** (a bug, not a login problem): shown as "Something went wrong (…). Nothing was saved.", logged with secrets scrubbed, and the window keeps working (Task 4 pins it).

## Decided while planning (2026-10-01)

- `accounts.py`'s names differ from spec 4.1's sketch, same behaviour: `check_site` + `change_site` (for `connect_site(..., save=)`), `save_site_and_edusoft` (the first setup's joint save), `change_edusoft` and `change_blackboard` (for `save_edusoft` / `save_blackboard`, names `credentials.py` already uses for storing passwords). Each takes a `Tools` bundle instead of separate makers.
- `cli.py` already imports `outlook_reader.accounts` (Outlook's account list); it is imported as `outlook_addresses` so the new `accounts` module keeps its name.
- `turn_on_sync()` registers both link types and makes the shortcuts on every call, so `sla-agent setup`, `sla-agent schedule` and the window's Repair all fix a moved project folder in one go (the `sla-mail:` command also points at the old Python after a move).
- An address is saved without surrounding spaces or a trailing `/`.
- The status card on Overview shows an "Open Accounts →" link while something is paused; the system lines say "Change it in Accounts."

## Files

- Create `agent/sla_agent/accounts.py` — check and save the website connection, EduSoft, Blackboard, Outlook; turn on sync. No screen.
- Create `agent/sla_agent/window.py` — the Tkinter window: first-time form, Accounts rows, one window at a time.
- Create `agent/sla_agent/shortcuts.py` — Desktop and Start menu shortcuts.
- Modify `agent/sla_agent/mail_link.py` — a second link type, `sla-agent:`.
- Modify `agent/sla_agent/cli.py` — `setup`, `schedule`, `forget` use `accounts.py`; new `window` command; messages point to Accounts.
- Modify `agent/sla_agent/sync.py` — pause messages point to Accounts.
- Create `School-Life-Assistant.cmd` — double-click starter in the project root.
- Tests: `agent/tests/conftest.py` (fake Windows shell), `agent/tests/accounts_fakes.py` (new), `test_mail_link.py`, `test_shortcuts.py` (new), `test_accounts.py` (new), `test_window.py` (new), `test_cli.py`, `test_sync.py`.
- Create `web/src/main/java/vn/edu/hcmiu/sla/school/pages/AccountsController.java`, `web/src/main/resources/templates/school/accounts.html`.
- Modify `SyncStatus.java`, `templates/school/fragments.html`, `courses.html`, `mailbox.html`, `devices.html`.
- Tests: `AccountsPageTest.java` (new), `SyncStatusTest.java`, `SchoolPagesTest.java`, `MailboxPageTest.java`, `DevicesPageTest.java`.
- Modify `README.md`, the spec's Status line.

---

### Task 1: The `sla-agent:` link type, the shortcuts and the double-click starter

**Files:**
- Modify: `agent/sla_agent/mail_link.py`
- Create: `agent/sla_agent/shortcuts.py`, `School-Life-Assistant.cmd`
- Modify: `agent/tests/conftest.py`
- Test: `agent/tests/test_mail_link.py`, `agent/tests/test_shortcuts.py` (new)

**Interfaces:**
- Produces: `mail_link.register_window(python_exe)`, `mail_link.unregister_window()`, `mail_link.window_command(python_exe) -> str`; `shortcuts.make(python_exe, folder)`, `shortcuts.remove()`, `shortcuts.NAME = "School-Life-Assistant.lnk"`; test fixture attribute `isolated_agent.shell` (a `FakeShell` whose `root` is a folder holding `OneDrive/Máy tính` (Desktop) and `Start Menu/Programs`).

- [ ] **Step 1: Write the failing tests**

In `agent/tests/conftest.py`, add after `MemoryRegistry` (and `from pathlib import Path` at the top):

```python
class FakeShortcut:
    def __init__(self, path):
        self.path = path
        self.TargetPath = self.Arguments = self.WorkingDirectory = self.Description = ""

    def Save(self):
        Path(self.path).write_text(f"{self.TargetPath} {self.Arguments}\n{self.WorkingDirectory}", encoding="utf-8")


class FakeShell:
    """Stands in for Windows' WScript.Shell: a shortcut becomes a small text file in the test's own folders. The
    Desktop has Vietnamese letters in its path, like OneDrive's "Máy tính" on the student's laptop."""

    FOLDERS = {"Desktop": "OneDrive/Máy tính", "Programs": "Start Menu/Programs"}

    def __init__(self, root):
        self.root = root

    def SpecialFolders(self, name):
        folder = self.root / self.FOLDERS[name]
        folder.mkdir(parents=True, exist_ok=True)
        return str(folder)

    def CreateShortcut(self, path):
        return FakeShortcut(path)
```

and change the `isolated_agent` fixture to:

```python
@pytest.fixture(autouse=True)
def isolated_agent(tmp_path, monkeypatch):
    """Every agent test gets a fake keyring, a fake registry, a fake Windows shell and its own agent folder:
    tests never touch the real Credential Manager, registry, Desktop, Start menu or %LOCALAPPDATA%."""
    from sla_agent import mail_link, shortcuts

    fake = MemoryKeyring()
    previous = keyring.get_keyring()
    keyring.set_keyring(fake)
    registry = MemoryRegistry()
    monkeypatch.setattr(mail_link, "_winreg", lambda: registry)
    shell = FakeShell(tmp_path / "windows")
    monkeypatch.setattr(shortcuts, "_shell", lambda: shell)
    monkeypatch.setenv("SLA_AGENT_HOME", str(tmp_path / "agent-home"))
    fake.registry = registry
    fake.shell = shell
    yield fake
    keyring.set_keyring(previous)
```

Append to `agent/tests/test_mail_link.py`:

```python
WINDOW_ROOT = r"Software\Classes\sla-agent"


def test_register_window_adds_the_accounts_link_type_beside_sla_mail(isolated_agent):
    mail_link.register(r"C:\Python\pythonw.exe")
    mail_link.register_window(r"C:\Python\pythonw.exe")

    keys = isolated_agent.registry.keys
    assert keys[("HKCU", WINDOW_ROOT)] == {"": "URL:School-Life-Assistant accounts", "URL Protocol": ""}
    assert keys[("HKCU", WINDOW_ROOT + r"\shell\open\command")] == {
        "": r'"C:\Python\pythonw.exe" -m sla_agent window "%1"'}
    assert ("HKCU", ROOT) in keys


def test_unregister_window_leaves_sla_mail_and_is_fine_when_it_is_gone(isolated_agent):
    mail_link.register("pythonw.exe")
    mail_link.register_window("pythonw.exe")

    mail_link.unregister_window()
    mail_link.unregister_window()

    paths = [path for _, path in isolated_agent.registry.keys]
    assert not [path for path in paths if "sla-agent" in path]
    assert ROOT in paths
```

Create `agent/tests/test_shortcuts.py`:

```python
"""The Desktop and Start menu shortcuts, made in a fake Windows shell (conftest.py): tests never touch the real ones."""

from pathlib import Path

from sla_agent import shortcuts

PYTHONW = r"C:\IU_SCHOOL\p\.venv\Scripts\pythonw.exe"


def made(isolated_agent):
    return sorted(isolated_agent.shell.root.rglob("*.lnk"))


def test_make_puts_a_shortcut_on_the_desktop_and_in_the_start_menu(isolated_agent, tmp_path):
    shortcuts.make(PYTHONW, tmp_path / "home")

    desktop = isolated_agent.shell.root / "OneDrive" / "Máy tính" / "School-Life-Assistant.lnk"
    start_menu = isolated_agent.shell.root / "Start Menu" / "Programs" / "School-Life-Assistant.lnk"
    assert made(isolated_agent) == sorted([desktop, start_menu])
    for shortcut in (desktop, start_menu):
        assert shortcut.read_text(encoding="utf-8").splitlines() == [
            f"{PYTHONW} -m sla_agent window", str(tmp_path / "home")]


def test_make_again_replaces_them_and_remove_is_fine_when_they_are_gone(isolated_agent, tmp_path):
    shortcuts.make(r"C:\IU SCHOOL\old\pythonw.exe", tmp_path)
    shortcuts.make(PYTHONW, tmp_path)

    assert all(path.read_text(encoding="utf-8").startswith(PYTHONW) for path in made(isolated_agent))

    shortcuts.remove()
    shortcuts.remove()

    assert made(isolated_agent) == []


def test_the_project_folder_has_a_double_click_starter():
    starter = Path(__file__).resolve().parents[2] / "School-Life-Assistant.cmd"

    text = starter.read_text(encoding="utf-8")

    assert r'start "" "%~dp0.venv\Scripts\pythonw.exe" -m sla_agent window' in text
```

- [ ] **Step 2: Run them to see them fail**

Run: `.venv/Scripts/python.exe -m pytest agent/tests/test_mail_link.py agent/tests/test_shortcuts.py`
Expected: FAIL — every test errors in the `isolated_agent` fixture with `ImportError: cannot import name 'shortcuts' from 'sla_agent'`.

- [ ] **Step 3: The link type, the shortcuts and the starter**

Replace `agent/sla_agent/mail_link.py` with:

```python
r"""The sla-mail: and sla-agent: link types, registered for this Windows user only
(HKEY_CURRENT_USER\Software\Classes), so no admin rights are needed.

- sla-mail: Mailbox's "Open in Outlook" button. A link runs `sla-agent open-mail "sla-mail:<Outlook ID>"`, which
  only shows that email (see outlook_reader.open_email).
- sla-agent: the website's "Open Accounts on this laptop" button. A link runs
  `sla-agent window "sla-agent:accounts"`, which only opens the School-Life-Assistant window."""

KEY = r"Software\Classes\sla-mail"
WINDOW_KEY = r"Software\Classes\sla-agent"
SUBKEYS = (r"\shell\open\command", r"\shell\open", r"\shell", "")  # deepest first, for deleting


def _winreg():
    import winreg

    return winreg


def command(python_exe):
    return f'"{python_exe}" -m sla_agent open-mail "%1"'


def window_command(python_exe):
    return f'"{python_exe}" -m sla_agent window "%1"'


def _register(key_path, description, command_line):
    registry = _winreg()
    with registry.CreateKey(registry.HKEY_CURRENT_USER, key_path) as key:
        registry.SetValueEx(key, "", 0, registry.REG_SZ, description)
        registry.SetValueEx(key, "URL Protocol", 0, registry.REG_SZ, "")
    with registry.CreateKey(registry.HKEY_CURRENT_USER, key_path + r"\shell\open\command") as key:
        registry.SetValueEx(key, "", 0, registry.REG_SZ, command_line)


def _unregister(key_path):
    registry = _winreg()
    for subkey in SUBKEYS:
        try:
            registry.DeleteKey(registry.HKEY_CURRENT_USER, key_path + subkey)
        except FileNotFoundError:
            pass


def register(python_exe):
    _register(KEY, "URL:School-Life-Assistant email", command(python_exe))


def unregister():
    _unregister(KEY)


def register_window(python_exe):
    _register(WINDOW_KEY, "URL:School-Life-Assistant accounts", window_command(python_exe))


def unregister_window():
    _unregister(WINDOW_KEY)
```

Create `agent/sla_agent/shortcuts.py`:

```python
"""The "School-Life-Assistant" shortcuts on the Desktop and in the Start menu: they open the accounts window
(spec 2026-10-01-accounts-window-design.md, 4.3).

Made with Windows' own WScript.Shell (through pywin32), for this Windows user only. Tests replace `_shell`."""

from pathlib import Path

NAME = "School-Life-Assistant.lnk"
ARGUMENTS = "-m sla_agent window"
DESCRIPTION = "Enter and change your School-Life-Assistant accounts"


def _shell():
    import win32com.client

    return win32com.client.Dispatch("WScript.Shell")


def _places(shell):
    """This user's Desktop and Start menu Programs folders (the Desktop may be OneDrive's, with Vietnamese letters)."""
    return [Path(shell.SpecialFolders("Desktop")), Path(shell.SpecialFolders("Programs"))]


def make(python_exe, folder):
    """Make, or replace, both shortcuts: `python_exe -m sla_agent window`, started in `folder`."""
    shell = _shell()
    for place in _places(shell):
        shortcut = shell.CreateShortcut(str(place / NAME))
        shortcut.TargetPath = str(python_exe)
        shortcut.Arguments = ARGUMENTS
        shortcut.WorkingDirectory = str(folder)
        shortcut.Description = DESCRIPTION
        shortcut.Save()


def remove():
    for place in _places(_shell()):
        (place / NAME).unlink(missing_ok=True)
```

Create `School-Life-Assistant.cmd` in the project root (the folder holding `README.md`):

```bat
@echo off
rem Opens the School-Life-Assistant window (docs/superpowers/specs/2026-10-01-accounts-window-design.md).
rem Double-click it in File Explorer; the window opens without a console.
if not exist "%~dp0.venv\Scripts\pythonw.exe" (
  echo sla-agent isn't installed in this folder yet. Follow "The laptop agent" in README.md first.
  pause
  exit /b 1
)
start "" "%~dp0.venv\Scripts\pythonw.exe" -m sla_agent window
```

- [ ] **Step 4: Run every Python test**

Run: `.venv/Scripts/python.exe -m pytest`
Expected: PASS (all tests, including the 5 new ones).

- [ ] **Step 5: Commit**

```bash
git add agent/sla_agent/mail_link.py agent/sla_agent/shortcuts.py School-Life-Assistant.cmd agent/tests/conftest.py agent/tests/test_mail_link.py agent/tests/test_shortcuts.py
git commit -m "feat(agent): sla-agent: link type, Desktop and Start menu shortcuts, and a double-click starter

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `accounts.py`, the check-and-save logic

**Files:**
- Create: `agent/sla_agent/accounts.py`
- Create: `agent/tests/accounts_fakes.py`, `agent/tests/test_accounts.py`

**Interfaces:**
- Consumes: Task 1's `mail_link.register`, `mail_link.register_window`, `shortcuts.make` (only through `Tools`).
- Produces (all in `sla_agent.accounts`):
  - `Result(ok: bool, message: str, notes: tuple = ())` (frozen dataclass)
  - `Tools(make_server, make_edusoft, make_blackboard, find_outlook_accounts, python, install_task, task_program, register_mail_link, register_window_link, make_shortcuts)` (dataclass of callables)
  - `SetupForm(address, key, student_id, password, bb_user="", bb_password="", outlook="")` (frozen dataclass)
  - `check_site(address, key, tools) -> Result`, `check_edusoft(student_id, password, tools) -> Result`
  - `save_site_and_edusoft(state, address, key, student_id, password) -> Result`
  - `change_site(state, address, key, tools) -> Result`, `change_edusoft(state, student_id, password, tools) -> Result`, `change_blackboard(state, username, password, tools) -> Result`
  - `outlook_accounts(tools) -> (list[str], str | None)`, `choose_outlook(state, address, tools) -> Result`
  - `turn_on_sync(tools) -> Result`, `sync_task_state(tools) -> "on" | "off" | "nowhere"`
  - `first_setup(state, form, tools) -> dict[str, Result]` with keys in order among `site`, `edusoft`, `blackboard`, `outlook`, `sync`
  - constants `SAVED`, `BLACKBOARD_SAVED`, `SYNC_ON`
- Produces (tests): `agent/tests/accounts_fakes.py` with `SERVER, KEY, STUDENT, PASSWORD, BB_USER, BB_PASSWORD, ME, PYTHONW`, `class Fakes` (`.server`, `.edusoft`, `.blackboard`, `.found`, `.program`, `.fail`, `.done`, `.servers`, `.tools()`), and `set_up() -> State`.

- [ ] **Step 1: Write the failing tests**

Create `agent/tests/accounts_fakes.py`:

```python
"""Fakes for everything accounts.py talks to (accounts.Tools), shared by test_accounts.py and test_window.py."""

from agent.tests.fakes import FakeBlackboard, FakeEduSoft, FakeServer
from sla_agent import accounts, credentials
from sla_agent.state import State, load_state, save_state

SERVER = "https://sla.example.com"
KEY = "sla_device-key-0123456789"
STUDENT = "ITITIU20001"
PASSWORD = "s3cret-pass"
BB_USER = "ititiu20001"
BB_PASSWORD = "bb-s3cret"
ME = "ititiu20001@student.hcmiu.edu.vn"
PYTHONW = r"C:\IU_SCHOOL\p\.venv\Scripts\pythonw.exe"


class Fakes:
    """`found` is Outlook's account list (an exception instance is raised instead); `program` is what the scheduled
    task starts; `fail` maps a part ("task", "mail link", "window link", "shortcuts") to the error it raises;
    `done` lists the parts that ran."""

    def __init__(self):
        self.server = FakeServer()
        self.edusoft = FakeEduSoft()
        self.blackboard = FakeBlackboard()
        self.found = [ME]
        self.program = None
        self.fail = {}
        self.done = []
        self.servers = []

    def tools(self):
        return accounts.Tools(
            make_server=self._make_server, make_edusoft=lambda: self.edusoft,
            make_blackboard=lambda: self.blackboard, find_outlook_accounts=self._find, python=lambda: PYTHONW,
            install_task=self._part("task"), task_program=lambda: self.program,
            register_mail_link=self._part("mail link"), register_window_link=self._part("window link"),
            make_shortcuts=self._part("shortcuts"))

    def _make_server(self, address, key):
        self.servers.append((address, key))
        return self.server

    def _find(self):
        if isinstance(self.found, Exception):
            raise self.found
        return self.found

    def _part(self, name):
        def run(*args, **kwargs):
            if name in self.fail:
                raise self.fail[name]
            self.done.append(name)

        return run


def set_up():
    """A laptop already set up with the website and EduSoft only."""
    credentials.save_edusoft(STUDENT, PASSWORD)
    credentials.save_device_key(SERVER, KEY)
    save_state(State(server_url=SERVER, student_id=STUDENT))
    return load_state()
```

Create `agent/tests/test_accounts.py`:

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
from sla_agent import accounts, credentials
from sla_agent.accounts import SetupForm
from sla_agent.errors import BadCredentials, DeviceKeyRejected, ExtraVerification, OutlookNotSetUp, ServerError
from sla_agent.log import setup_logging
from sla_agent.scheduler import SchedulerError
from sla_agent.state import State, load_state, save_state


@pytest.fixture
def fakes():
    return Fakes()


def form(**changes):
    values = dict(address=SERVER, key=KEY, student_id=STUDENT, password=PASSWORD)
    values.update(changes)
    return SetupForm(**values)


EVERYTHING = dict(bb_user=BB_USER, bb_password=BB_PASSWORD, outlook=ME)


# ---- first setup ------------------------------------------------------------------


def test_a_first_setup_checks_then_saves_everything_and_turns_on_sync(fakes):
    results = accounts.first_setup(State(), form(**EVERYTHING), fakes.tools())

    assert list(results) == ["site", "edusoft", "blackboard", "outlook", "sync"]
    assert all(result.ok for result in results.values())
    assert results["edusoft"].message == accounts.SAVED
    assert credentials.load_device_key(SERVER) == KEY
    assert credentials.load_edusoft(STUDENT) == PASSWORD
    assert credentials.load_blackboard(BB_USER) == BB_PASSWORD
    state = load_state()
    assert (state.server_url, state.student_id, state.blackboard_username, state.outlook_account) == (
        SERVER, STUDENT, BB_USER, ME)
    assert {"task", "mail link", "window link", "shortcuts"} <= set(fakes.done)


@pytest.mark.parametrize("break_it, step", [
    (lambda fakes: setattr(fakes.server, "check_error", DeviceKeyRejected("rejected")), "site"),
    (lambda fakes: setattr(fakes.server, "check_error", ServerError("down")), "site"),
    (lambda fakes: setattr(fakes.edusoft, "login_error", BadCredentials("rejected")), "edusoft"),
    (lambda fakes: setattr(fakes.edusoft, "login_error", ExtraVerification("captcha")), "edusoft"),
], ids=["key-rejected", "site-down", "wrong-password", "captcha"])
def test_a_first_setup_saves_nothing_unless_the_site_and_edusoft_pass(fakes, isolated_agent, break_it, step):
    break_it(fakes)

    results = accounts.first_setup(State(), form(**EVERYTHING), fakes.tools())

    assert list(results)[-1] == step
    assert not results[step].ok
    assert "Nothing was saved" in results[step].message
    assert isolated_agent.entries == {}
    assert load_state() == State()
    assert fakes.done == []


def test_a_first_setup_refuses_a_plain_http_address_without_contacting_it(fakes):
    results = accounts.first_setup(State(), form(address="http://sla.example.com"), fakes.tools())

    assert not results["site"].ok
    assert "https://" in results["site"].message
    assert fakes.servers == []


def test_a_rejected_blackboard_login_keeps_edusoft_saved(fakes):
    fakes.blackboard.login_error = BadCredentials("rejected")

    results = accounts.first_setup(State(), form(bb_user=BB_USER, bb_password="wrong"), fakes.tools())

    assert results["edusoft"].ok
    assert results["blackboard"].message == "Blackboard rejected the username or password. Nothing was saved."
    assert credentials.load_edusoft(STUDENT) == PASSWORD
    assert load_state().blackboard_username is None
    assert results["sync"].ok


def test_half_a_blackboard_login_is_refused(fakes):
    results = accounts.first_setup(State(), form(bb_user=BB_USER), fakes.tools())

    assert results["blackboard"].message == "Blackboard username and password are both needed. Nothing was saved."
    assert fakes.blackboard.logins == []


# ---- changes ----------------------------------------------------------------------


def test_changing_the_student_id_forgets_the_old_password(fakes):
    state = set_up()

    result = accounts.change_edusoft(state, "ITITIU20002", "new-pass", fakes.tools())

    assert result == accounts.Result(True, accounts.SAVED)
    assert credentials.load_edusoft(STUDENT) is None
    assert credentials.load_edusoft("ITITIU20002") == "new-pass"
    assert load_state().student_id == "ITITIU20002"


def test_a_wrong_new_login_keeps_the_old_one(fakes):
    state = set_up()
    accounts.change_blackboard(state, BB_USER, BB_PASSWORD, fakes.tools())
    fakes.edusoft.login_error = BadCredentials("rejected")
    fakes.blackboard.login_error = BadCredentials("rejected")
    fakes.server.check_error = DeviceKeyRejected("rejected")

    assert not accounts.change_edusoft(state, STUDENT, "wrong", fakes.tools()).ok
    assert not accounts.change_blackboard(state, BB_USER, "wrong", fakes.tools()).ok
    assert not accounts.change_site(state, "https://new.example.com", "sla_other-key-0000", fakes.tools()).ok

    assert credentials.load_edusoft(STUDENT) == PASSWORD
    assert credentials.load_blackboard(BB_USER) == BB_PASSWORD
    assert credentials.load_device_key(SERVER) == KEY
    assert load_state() == state


def test_a_saved_login_clears_its_pause(fakes):
    state = set_up()
    state.paused = state.blackboard_paused = "bad_credentials"
    save_state(state)

    accounts.change_edusoft(state, STUDENT, "new-pass", fakes.tools())
    accounts.change_blackboard(state, BB_USER, BB_PASSWORD, fakes.tools())

    assert (load_state().paused, load_state().blackboard_paused) == (None, None)


def test_changing_the_website_moves_the_key(fakes):
    state = set_up()

    result = accounts.change_site(state, "https://new.example.com", "sla_new-key-0123456789", fakes.tools())

    assert result.ok
    assert credentials.load_device_key(SERVER) is None
    assert credentials.load_device_key("https://new.example.com") == "sla_new-key-0123456789"
    assert load_state().server_url == "https://new.example.com"


def test_an_address_with_a_trailing_slash_is_saved_without_it(fakes):
    state = set_up()

    assert accounts.change_site(state, " https://new.example.com/ ", KEY, fakes.tools()).ok

    assert fakes.servers[-1] == ("https://new.example.com", KEY)
    assert load_state().server_url == "https://new.example.com"
    assert credentials.load_device_key("https://new.example.com") == KEY


# ---- Outlook ----------------------------------------------------------------------


def test_outlook_accounts_lists_them_or_says_what_is_wrong(fakes):
    assert accounts.outlook_accounts(fakes.tools()) == ([ME], None)
    fakes.found = []
    assert accounts.outlook_accounts(fakes.tools()) == ([], "Classic Outlook has no account yet.")
    fakes.found = OutlookNotSetUp("Classic Outlook isn't set up on this laptop.")
    assert accounts.outlook_accounts(fakes.tools()) == ([], "Classic Outlook isn't set up on this laptop.")


def test_choosing_outlook_saves_it_and_adds_the_mail_link(fakes):
    state = set_up()

    result = accounts.choose_outlook(state, ME, fakes.tools())

    assert result.ok
    assert "never the text" in result.message
    assert load_state().outlook_account == ME
    assert fakes.done == ["mail link"]


def test_choosing_outlook_still_saves_it_when_the_link_fails(fakes):
    state = set_up()
    fakes.fail = {"mail link": OSError("denied")}

    result = accounts.choose_outlook(state, ME, fakes.tools())

    assert result.ok
    assert "sla-mail:" in result.notes[0]
    assert load_state().outlook_account == ME


# ---- automatic sync ---------------------------------------------------------------


def test_turn_on_sync_reports_a_failed_link_or_shortcut_but_still_makes_the_task(fakes):
    fakes.fail = {"window link": OSError("denied"), "shortcuts": RuntimeError("com_error")}

    result = accounts.turn_on_sync(fakes.tools())

    assert result == accounts.Result(True, accounts.SYNC_ON, result.notes)
    assert len(result.notes) == 2
    assert {"task", "mail link"} <= set(fakes.done)


def test_turn_on_sync_fails_when_the_task_cannot_be_made(fakes):
    fakes.fail = {"task": SchedulerError("Couldn't create the scheduled task: Access is denied.")}

    result = accounts.turn_on_sync(fakes.tools())

    assert not result.ok
    assert "Access is denied" in result.message
    assert {"mail link", "window link", "shortcuts"} <= set(fakes.done)


def test_sync_task_state(fakes, tmp_path):
    assert accounts.sync_task_state(fakes.tools()) == "off"
    fakes.program = str(tmp_path / "moved" / "pythonw.exe")
    assert accounts.sync_task_state(fakes.tools()) == "nowhere"
    (tmp_path / "pythonw.exe").write_text("")
    fakes.program = str(tmp_path / "pythonw.exe")
    assert accounts.sync_task_state(fakes.tools()) == "on"


# ---- secrets ----------------------------------------------------------------------


def test_no_password_or_key_reaches_the_log_file(fakes, tmp_path):
    log_path = setup_logging(tmp_path / "logs")
    fakes.fail = {"shortcuts": RuntimeError(f"failed for {PASSWORD} {BB_PASSWORD} {KEY}")}

    accounts.first_setup(State(), form(**EVERYTHING), fakes.tools())

    for handler in logging.getLogger().handlers[:]:
        if str(tmp_path) in getattr(handler, "baseFilename", ""):
            handler.flush()
            logging.getLogger().removeHandler(handler)
            handler.close()
    text = log_path.read_text(encoding="utf-8")
    assert "***" in text
    assert PASSWORD not in text and BB_PASSWORD not in text and KEY not in text
```

- [ ] **Step 2: Run them to see them fail**

Run: `.venv/Scripts/python.exe -m pytest agent/tests/test_accounts.py`
Expected: FAIL — collection error `ImportError: cannot import name 'accounts' from 'sla_agent'`.

- [ ] **Step 3: Write `accounts.py`**

Create `agent/sla_agent/accounts.py`:

```python
"""Checking and saving this laptop's accounts: the website connection, EduSoft, Blackboard and Outlook, and turning
on automatic sync (spec 2026-10-01-accounts-window-design.md, 4.1).

`sla-agent setup` (terminal) and the School-Life-Assistant window both use it. Nothing here prints, asks or draws:
each step returns a Result whose message the caller shows, in the words `sla-agent setup` has always used. A login
is checked once before it is saved, and one that fails changes nothing. Passwords and the device key go only to
Windows Credential Manager (credentials.py)."""

import logging
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

from sla_agent import credentials
from sla_agent.errors import AgentError, BadCredentials, DeviceKeyRejected, ExtraVerification, ServerError
from sla_agent.log import protect
from sla_agent.scheduler import SchedulerError, current_user
from sla_agent.server_client import check_server_url
from sla_agent.state import agent_home, save_state

log = logging.getLogger(__name__)

SAVED = "Saved. Your password is in Windows Credential Manager, not in any file."
BLACKBOARD_SAVED = "Blackboard saved. Its password is in Windows Credential Manager too."
SYNC_ON = "Automatic sync is on: this laptop checks in every minute while you're logged in."


@dataclass(frozen=True)
class Result:
    ok: bool
    message: str
    notes: tuple = ()  # more lines that don't change `ok`, e.g. a shortcut that couldn't be made


@dataclass
class Tools:
    """What this module talks to. `sla-agent` passes the real ones (cli.tools()); tests pass fakes."""

    make_server: Callable  # (address, device key) -> ServerClient
    make_edusoft: Callable  # () -> EduSoftClient
    make_blackboard: Callable  # () -> BlackboardClient
    find_outlook_accounts: Callable  # () -> [account address]; raises AgentError
    python: Callable  # () -> the windowless Python the task, the links and the shortcuts start
    install_task: Callable  # (python, user, folder=) ; raises SchedulerError
    task_program: Callable  # () -> the program the scheduled task starts, or None
    register_mail_link: Callable  # (python)
    register_window_link: Callable  # (python)
    make_shortcuts: Callable  # (python, folder)


@dataclass(frozen=True)
class SetupForm:
    """The first-time form. Blackboard and Outlook are optional: empty means skipped."""

    address: str
    key: str
    student_id: str
    password: str
    bb_user: str = ""
    bb_password: str = ""
    outlook: str = ""


def _clean(address):
    """Saved without surrounding spaces or a trailing /, so the device key is found under the same address."""
    return address.strip().rstrip("/")


# ---- checks (save nothing) -------------------------------------------------------


def check_site(address, key, tools):
    """Whether the website at `address` accepts this device key."""
    protect(key)
    address = _clean(address)
    try:
        check_server_url(address)
    except ValueError as error:
        return Result(False, str(error))
    if not key:
        return Result(False, "Paste the device key from the Devices page. Nothing was saved.")
    try:
        tools.make_server(address, key).check()
    except DeviceKeyRejected:
        return Result(False, "The web app rejected this device key. Create a new one on the Devices page. "
                             "Nothing was saved.")
    except ServerError as error:
        return Result(False, f"Couldn't reach the web app: {error} Nothing was saved.")
    return Result(True, "The web app accepted this device key.")


def check_edusoft(student_id, password, tools):
    """One EduSoft login attempt."""
    protect(password)
    if not (student_id and password):
        return Result(False, "Enter your student ID and password. Nothing was saved.")
    try:
        tools.make_edusoft().login(student_id, password)
    except BadCredentials:
        return Result(False, "EduSoft rejected the student ID or password. Nothing was saved.")
    except ExtraVerification:
        return Result(False, "EduSoft asked for extra verification (CAPTCHA or code), so automatic sync can't be "
                             "used. Nothing was saved. You can still use `sla-agent import` with pages saved from "
                             "your browser.")
    except AgentError as error:
        return Result(False, f"Couldn't check your EduSoft login: {error} Nothing was saved; try again later.")
    return Result(True, "EduSoft accepted your login.")


# ---- saving -------------------------------------------------------------------------


def _keep_site(state, address, key):
    address = _clean(address)
    if state.server_url and state.server_url != address:
        credentials.forget(None, state.server_url)
    credentials.save_device_key(address, key)
    state.server_url = address


def _keep_edusoft(state, student_id, password):
    if state.student_id and state.student_id != student_id:
        credentials.forget(state.student_id, None)
    credentials.save_edusoft(student_id, password)
    state.student_id, state.paused = student_id, None


def save_site_and_edusoft(state, address, key, student_id, password):
    """Save a checked website connection and EduSoft login together (a first setup)."""
    _keep_site(state, address, key)
    _keep_edusoft(state, student_id, password)
    save_state(state)
    return Result(True, SAVED)


def change_site(state, address, key, tools):
    """Check, then save, a new website address or device key."""
    checked = check_site(address, key, tools)
    if not checked.ok:
        return checked
    _keep_site(state, address, key)
    save_state(state)
    return Result(True, f"Saved. This laptop now syncs with {state.server_url}.")


def change_edusoft(state, student_id, password, tools):
    """Check, then save, the EduSoft login; a new student ID forgets the old one's password."""
    checked = check_edusoft(student_id, password, tools)
    if not checked.ok:
        return checked
    _keep_edusoft(state, student_id, password)
    save_state(state)
    return Result(True, SAVED)


def change_blackboard(state, username, password, tools):
    """Check, then save, the Blackboard login (one attempt)."""
    protect(password)
    if not (username and password):
        return Result(False, "Blackboard username and password are both needed. Nothing was saved.")
    blackboard = tools.make_blackboard()
    try:
        blackboard.login(username, password)
    except BadCredentials:
        return Result(False, "Blackboard rejected the username or password. Nothing was saved.")
    except ExtraVerification:
        return Result(False, "Blackboard asked for extra verification, so automatic Blackboard sync can't be "
                             "used. Nothing was saved.")
    except AgentError as error:
        return Result(False, f"Couldn't check your Blackboard login: {error} Nothing was saved; try again later.")
    finally:
        blackboard.logout()
    if state.blackboard_username and state.blackboard_username != username:
        credentials.forget(None, None, state.blackboard_username)
    credentials.save_blackboard(username, password)
    state.blackboard_username, state.blackboard_paused = username, None
    save_state(state)
    return Result(True, BLACKBOARD_SAVED)


def outlook_accounts(tools):
    """(the accounts in classic Outlook, None), or ([], what's wrong)."""
    try:
        found = tools.find_outlook_accounts()
    except AgentError as error:
        return [], str(error)
    if not found:
        return [], "Classic Outlook has no account yet."
    return list(found), None


def choose_outlook(state, address, tools):
    """Read this account's Inbox at each sync, and add the sla-mail: link type."""
    state.outlook_account = address
    save_state(state)
    on = (f"Outlook is on: each sync reads the Inbox of {address}, sorts it on this laptop and uploads only the "
          "results, never the text.")
    try:
        tools.register_mail_link(tools.python())
    except (ImportError, OSError) as error:  # not Windows, or the registry refused
        return Result(True, on, (f"Couldn't add the sla-mail: link type ({error}). Mailbox's \"Open in Outlook\" "
                                 "won't open emails on this laptop; use \"Outlook on the web\" instead.",))
    return Result(True, on)


# ---- automatic sync -----------------------------------------------------------------


def turn_on_sync(tools):
    """The sla-agent: and sla-mail: link types, the shortcuts and the scheduled task, all pointing at this folder's
    Python (so this also repairs them after the project folder moved). `ok` says whether the task was made; a link
    or shortcut that fails becomes a note, and nothing saved is undone."""
    python = tools.python()
    notes = []
    for register, name in ((tools.register_window_link, "sla-agent:"), (tools.register_mail_link, "sla-mail:")):
        try:
            register(python)
        except (ImportError, OSError) as error:  # not Windows, or the registry refused
            notes.append(f"Couldn't add the {name} link type ({error}).")
    try:
        tools.make_shortcuts(python, agent_home())
    except Exception as error:  # pywin32 raises its own com_error, not an OSError
        log.warning("Couldn't make the shortcuts: %s", error)
        notes.append(f"Couldn't make the Desktop and Start menu shortcuts ({error.__class__.__name__}). "
                     "School-Life-Assistant.cmd in the project folder opens this window too.")
    try:
        tools.install_task(python, current_user(), folder=agent_home())
    except SchedulerError as error:
        return Result(False, str(error), tuple(notes))
    return Result(True, SYNC_ON, tuple(notes))


def sync_task_state(tools):
    """"on", "off" (no task) or "nowhere" (the task starts a Python that no longer exists: the folder moved)."""
    program = tools.task_program()
    if not program:
        return "off"
    return "on" if Path(program).exists() else "nowhere"


# ---- the first-time form ---------------------------------------------------------------


def first_setup(state, form, tools):
    """Check and save a first setup in the order of spec 3.1: the website and EduSoft, saved together only when
    both pass; then Blackboard and Outlook when filled in; then automatic sync. Returns {step: Result} for the steps
    that ran, in order: site, edusoft, blackboard, outlook, sync."""
    results = {"site": check_site(form.address, form.key, tools)}
    if not results["site"].ok:
        return results
    results["edusoft"] = check_edusoft(form.student_id, form.password, tools)
    if not results["edusoft"].ok:
        return results
    results["edusoft"] = save_site_and_edusoft(state, form.address, form.key, form.student_id, form.password)
    if form.bb_user or form.bb_password:
        results["blackboard"] = change_blackboard(state, form.bb_user, form.bb_password, tools)
    if form.outlook:
        results["outlook"] = choose_outlook(state, form.outlook, tools)
    results["sync"] = turn_on_sync(tools)
    return results
```

- [ ] **Step 4: Run every Python test**

Run: `.venv/Scripts/python.exe -m pytest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent/sla_agent/accounts.py agent/tests/accounts_fakes.py agent/tests/test_accounts.py
git commit -m "feat(agent): accounts.py checks and saves the laptop's accounts, for setup and the window

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: `sla-agent setup`, `schedule` and `forget` use `accounts.py`

**Files:**
- Modify: `agent/sla_agent/cli.py`
- Test: `agent/tests/test_cli.py`

**Interfaces:**
- Consumes: Task 2's `accounts.*`; Task 1's `mail_link.register_window`, `mail_link.unregister_window`, `shortcuts.make`, `shortcuts.remove`.
- Produces: `cli.tools() -> accounts.Tools` (reads `make_server`, `make_edusoft`, `make_blackboard`, `find_outlook_accounts`, `windowless_python`, `install_task`, `task_program` from `cli`'s module globals at call time, so tests that monkeypatch them keep working).

- [ ] **Step 1: Write the failing tests**

Append to `agent/tests/test_cli.py`:

```python
# ---- the window's link type and shortcuts --------------------------------------------

WINDOW_COMMAND = ("HKCU", r"Software\Classes\sla-agent\shell\open\command")


def test_setup_adds_the_window_link_type_and_the_shortcuts(world, isolated_agent, capsys):
    world.answer_setup()

    assert cli.main(["setup"]) == 0

    assert isolated_agent.registry.keys[WINDOW_COMMAND][""].endswith('-m sla_agent window "%1"')
    assert [path.name for path in isolated_agent.shell.root.rglob("*.lnk")] == ["School-Life-Assistant.lnk"] * 2
    assert "every minute" in capsys.readouterr().out


def test_setup_says_when_the_shortcuts_could_not_be_made_but_keeps_the_rest(world, monkeypatch, capsys):
    from sla_agent import shortcuts

    def blocked(*args):
        raise RuntimeError("blocked")

    monkeypatch.setattr(shortcuts, "make", blocked)
    world.answer_setup()

    assert cli.main(["setup"]) == 0

    assert "Couldn't make the Desktop and Start menu shortcuts" in capsys.readouterr().out
    assert world.tasks == ["installed"]


def test_schedule_also_points_the_links_and_shortcuts_at_this_python(world, isolated_agent, monkeypatch):
    configure()
    python = r"C:\IU_SCHOOL\p\.venv\Scripts\pythonw.exe"
    monkeypatch.setattr(cli, "windowless_python", lambda: python)

    assert cli.main(["schedule"]) == 0

    keys = isolated_agent.registry.keys
    assert keys[WINDOW_COMMAND][""] == f'"{python}" -m sla_agent window "%1"'
    assert keys[("HKCU", r"Software\Classes\sla-mail\shell\open\command")][""] == f'"{python}" -m sla_agent open-mail "%1"'
    assert all(path.read_text(encoding="utf-8").startswith(f"{python} -m sla_agent window")
               for path in isolated_agent.shell.root.rglob("*.lnk"))


def test_forget_removes_the_window_link_type_and_the_shortcuts(world, isolated_agent, tmp_path):
    from sla_agent import mail_link, shortcuts

    configure()
    mail_link.register_window("pythonw.exe")
    shortcuts.make("pythonw.exe", tmp_path)

    assert cli.main(["forget"]) == 0

    assert not [path for _, path in isolated_agent.registry.keys if "sla-agent" in path]
    assert not list(isolated_agent.shell.root.rglob("*.lnk"))
```

- [ ] **Step 2: Run them to see them fail**

Run: `.venv/Scripts/python.exe -m pytest agent/tests/test_cli.py -k "window_link or shortcuts or points_the_links"`
Expected: FAIL — `KeyError` on the `sla-agent` registry key (setup and schedule make no link or shortcut yet), the "Couldn't make the Desktop and Start menu shortcuts" line is missing, and `forget` leaves the link and the shortcuts.

- [ ] **Step 3: Use `accounts.py` in `cli.py`**

In `agent/sla_agent/cli.py`:

1. Imports: `from sla_agent import accounts, credentials, mail_link, shortcuts` (replacing `from sla_agent import credentials, mail_link`); in the `outlook_reader` import, `accounts,` becomes `accounts as outlook_addresses,`; remove `SchedulerError,` and `current_user,` from the `scheduler` import (no longer used here).
2. `find_outlook_accounts` becomes:

```python
def find_outlook_accounts():
    return with_time_limit(lambda: outlook_addresses(open_outlook()))
```

3. Add after `find_outlook_accounts`:

```python
def tools():
    """The real things accounts.py talks to. It reads this module's makers when called, so tests that replace them
    (World) replace them for accounts.py too."""
    return accounts.Tools(
        make_server=make_server, make_edusoft=make_edusoft, make_blackboard=make_blackboard,
        find_outlook_accounts=find_outlook_accounts, python=windowless_python, install_task=install_task,
        task_program=task_program, register_mail_link=mail_link.register,
        register_window_link=mail_link.register_window, make_shortcuts=shortcuts.make)
```

4. Replace `_setup_blackboard` with:

```python
def _setup_blackboard(state, username=None):
    """Ask for (or use) the Blackboard login, check it once, save it. Returns an exit code."""
    username = username or ask(f"Blackboard username [{state.blackboard_username or ''}]: ").strip() \
        or state.blackboard_username or ""
    password = ask_secret("Blackboard password (not shown): ")
    protect(password)
    if not (username and password):
        say("Blackboard username and password are both needed. Nothing was saved.")
        return 1
    say("Checking your Blackboard login (one attempt)...")
    result = accounts.change_blackboard(state, username, password, tools())
    say(result.message)
    return 0 if result.ok else 1
```

5. Replace `_setup_outlook` with (the questions stay as they are):

```python
def _setup_outlook(state):
    """Choose the Outlook account whose Inbox each sync reads, and add the sla-mail: link type."""
    say("Looking for classic Outlook on this laptop...")
    found, problem = accounts.outlook_accounts(tools())
    if problem:
        say(f"{problem} {OUTLOOK_HOW_TO}")
        return 1
    if len(found) == 1:
        if ask(f"Read the Inbox of {found[0]}? [Y/n]: ").strip().lower() not in ("", "y", "yes"):
            say("Nothing was changed.")
            return 1
        address = found[0]
    else:
        for number, candidate in enumerate(found, 1):
            say(f"  {number}. {candidate}")
        answer = ask("Which account's Inbox should be read? Number: ").strip()
        if not answer.isdigit() or not 1 <= int(answer) <= len(found):
            say("Nothing was changed.")
            return 1
        address = found[int(answer) - 1]
    result = accounts.choose_outlook(state, address, tools())
    for note in result.notes:
        say(note)
    say(result.message)
    return 0
```

6. In `cmd_setup`, replace everything from `say("Checking the device key with the web app...")` to the end of the function with:

```python
    say("Checking the device key with the web app...")
    site = accounts.check_site(server_url, device_key, tools())
    if not site.ok:
        say(site.message)
        return 1
    say("Checking your EduSoft login (one attempt)...")
    edusoft = accounts.check_edusoft(student_id, password, tools())
    if not edusoft.ok:
        say(edusoft.message)
        return 1
    say(accounts.save_site_and_edusoft(state, server_url, device_key, student_id, password).message)

    blackboard_user = ask(f"Blackboard username (press Enter to skip) [{state.blackboard_username or ''}]: ").strip()
    if blackboard_user and _setup_blackboard(state, blackboard_user) != 0:
        say("EduSoft is saved; set up Blackboard later with `sla-agent setup --blackboard`.")

    if not args.no_schedule:
        sync = accounts.turn_on_sync(tools())
        for note in sync.notes:
            say(note)
        if not sync.ok:
            say(f"{sync.message} You can still sync by hand with `sla-agent sync-now`.")
            return 1
        say(sync.message)
    say("Run `sla-agent sync-now` to sync right away.")
    return 0
```

7. Replace `cmd_schedule` with:

```python
def cmd_schedule(args):
    state = load_state()
    if not state.server_url:
        say(NOT_SET_UP)
        return 1
    sync = accounts.turn_on_sync(tools())
    for note in sync.notes:
        say(note)
    if not sync.ok:
        say(sync.message)
        return 1
    say(f"The sync task now runs {windowless_python()} every minute.")
    return 0
```

8. In `cmd_forget`, replace the `try: mail_link.unregister() ...` block and the closing message with:

```python
    for unregister in (mail_link.unregister, mail_link.unregister_window):
        try:
            unregister()
        except (ImportError, OSError):  # not Windows, or already gone
            pass
    try:
        shortcuts.remove()
    except Exception:  # not Windows (ImportError) or pywin32's com_error: nothing to remove
        pass
    (agent_home() / "state.json").unlink(missing_ok=True)
    say("Removed your saved EduSoft password, the device key, the scheduled task, the sla-mail: and sla-agent: "
        "link types and the shortcuts from this laptop.")
    return 0
```

- [ ] **Step 4: Run every Python test**

Run: `.venv/Scripts/python.exe -m pytest`
Expected: PASS — the existing setup, Blackboard, Outlook, schedule and forget tests pass unchanged.

- [ ] **Step 5: Commit**

```bash
git add agent/sla_agent/cli.py agent/tests/test_cli.py
git commit -m "refactor(agent): sla-agent setup, schedule and forget go through accounts.py; they also make the window's link and shortcuts

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: The window and `sla-agent window`

**Files:**
- Create: `agent/sla_agent/window.py`
- Modify: `agent/sla_agent/cli.py`
- Test: `agent/tests/test_window.py` (new), `agent/tests/test_cli.py`

**Interfaces:**
- Consumes: Task 2's `accounts.*` and `accounts_fakes`; Task 3's `cli.tools()`.
- Produces: `window.App(root, tools, run=None)` with `.screen` (a `SetupScreen` or `AccountsScreen`) and `.show(notice="")`; `window.run_at_once(work, done)`, `window.run_in_background(root)`, `window.attempt(work)`; `SetupScreen.values` / `.answers` (dicts of `tk.StringVar`), `.save()`, `.save_button`, `.outlook` (`OutlookPicker` with `.box`, `.note`, `.refresh()`); `AccountsScreen.values` / `.answers` / `.buttons`, `.notice`, `.open(row)`, `.editor` (`Editor` with `.values`, `.save()`, `.cancel()`); `window.claim_single_window(first=None, bring_forward=None) -> bool`; `window.main(tools, link=None) -> int`; constants `TITLE`, `NO_OUTLOOK`, `CHECKING`, `DONE`. The `sla-agent window [LINK]` command.

- [ ] **Step 1: Write the failing tests**

Create `agent/tests/test_window.py`:

```python
"""The School-Life-Assistant window, built for real but hidden, with fakes behind it (accounts_fakes.Fakes). Checks
run at once instead of on a thread (run_at_once), except where a test holds them to look at the screen meanwhile."""

import logging
import tkinter as tk

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
from sla_agent import accounts, credentials, window
from sla_agent.errors import BadCredentials, OutlookNotSetUp
from sla_agent.log import setup_logging
from sla_agent.state import save_state


@pytest.fixture
def root():
    try:
        root = tk.Tk()
    except tk.TclError as error:  # a machine without a desktop
        pytest.skip(f"Tk can't start here: {error}")
    root.withdraw()
    yield root
    root.destroy()


@pytest.fixture
def fakes():
    return Fakes()


def open_window(root, fakes, run=window.run_at_once):
    return window.App(root, fakes.tools(), run=run)


def fill(screen, **values):
    for name, value in values.items():
        screen.values[name].set(value)


FIRST_TIME = dict(address=SERVER, key=KEY, student_id=STUDENT, password=PASSWORD)


def disabled(button):
    return button.instate(["disabled"])


# ---- first time -------------------------------------------------------------------


def test_a_new_laptop_gets_the_first_time_form(root, fakes):
    app = open_window(root, fakes)

    assert isinstance(app.screen, window.SetupScreen)
    assert app.screen.values["address"].get() == "http://localhost:5000"
    assert tuple(app.screen.outlook.box.cget("values")) == (window.NO_OUTLOOK, ME)
    assert app.screen.values["outlook"].get() == window.NO_OUTLOOK


def test_the_first_time_form_saves_and_switches_to_accounts(root, fakes):
    app = open_window(root, fakes)
    fill(app.screen, bb_user=BB_USER, bb_password=BB_PASSWORD, outlook=ME, **FIRST_TIME)

    app.screen.save()

    assert isinstance(app.screen, window.AccountsScreen)
    assert app.screen.notice.get() == window.DONE
    assert app.screen.values["edusoft"].get() == f"{STUDENT}: on"
    assert app.screen.values["blackboard"].get() == f"{BB_USER}: on"
    assert app.screen.values["outlook"].get() == ME
    assert credentials.load_edusoft(STUDENT) == PASSWORD


def test_a_wrong_password_keeps_the_form_filled_in_and_saves_nothing(root, fakes, isolated_agent):
    fakes.edusoft.login_error = BadCredentials("rejected")
    app = open_window(root, fakes)
    fill(app.screen, **FIRST_TIME)

    app.screen.save()

    assert isinstance(app.screen, window.SetupScreen)
    assert app.screen.answers["site"].get() == "✓ The web app accepted this device key."
    assert app.screen.answers["edusoft"].get() == "✗ EduSoft rejected the student ID or password. Nothing was saved."
    assert app.screen.values["password"].get() == PASSWORD
    assert not disabled(app.screen.save_button)
    assert isolated_agent.entries == {}


def test_a_pasted_key_with_spaces_or_a_line_break_is_trimmed(root, fakes):
    app = open_window(root, fakes)
    fill(app.screen, **dict(FIRST_TIME, key=f"  {KEY}\n"))

    app.screen.save()

    assert fakes.servers == [(SERVER, KEY)]
    assert credentials.load_device_key(SERVER) == KEY


def test_while_checking_the_button_is_off_and_a_late_outlook_list_is_ignored(root, fakes):
    held = []
    app = open_window(root, fakes, run=lambda work, done: held.append((work, done)))
    outlook_lookup = held.pop()
    fill(app.screen, **FIRST_TIME)

    app.screen.save()

    assert disabled(app.screen.save_button)
    assert app.screen.answers["sync"].get() == window.CHECKING
    work, done = held.pop()
    done(window.attempt(work))
    assert isinstance(app.screen, window.AccountsScreen)
    work, done = outlook_lookup
    done(window.attempt(work))  # the form it was for is gone: nothing happens
    assert isinstance(app.screen, window.AccountsScreen)


def test_something_unexpected_is_shown_not_raised(root, fakes):
    fakes.server.check_error = RuntimeError("a bug")  # not a ServerError: nothing expects it
    app = open_window(root, fakes)
    fill(app.screen, **FIRST_TIME)

    app.screen.save()

    assert app.screen.answers["sync"].get() == "✗ Something went wrong (RuntimeError). Nothing was saved."
    assert not disabled(app.screen.save_button)


def test_without_outlook_the_form_says_so_and_refresh_looks_again(root, fakes):
    fakes.found = OutlookNotSetUp("Classic Outlook isn't set up on this laptop.")
    app = open_window(root, fakes)

    assert app.screen.outlook.note.get().startswith("Classic Outlook isn't set up on this laptop.")
    assert tuple(app.screen.outlook.box.cget("values")) == (window.NO_OUTLOOK,)

    fakes.found = [ME]
    app.screen.outlook.refresh()

    assert tuple(app.screen.outlook.box.cget("values")) == (window.NO_OUTLOOK, ME)
    assert app.screen.outlook.note.get() == ""


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


def test_repair_turns_sync_on_again(root, fakes, tmp_path):
    set_up()
    fakes.program = str(tmp_path / "moved" / "pythonw.exe")
    app = open_window(root, fakes)
    assert app.screen.values["sync"].get() == "points to a Python that no longer exists"
    (tmp_path / "pythonw.exe").write_text("")
    fakes.program = str(tmp_path / "pythonw.exe")  # what the new task starts

    app.screen.open("sync")

    assert "task" in fakes.done
    assert app.screen.values["sync"].get() == "on: every minute"
    assert "sync" not in app.screen.buttons


def test_the_window_never_logs_a_password(root, fakes, tmp_path):
    log_path = setup_logging(tmp_path / "logs")
    fakes.blackboard.login_error = RuntimeError(f"a bug with {BB_PASSWORD}")
    app = open_window(root, fakes)
    fill(app.screen, bb_user=BB_USER, bb_password=BB_PASSWORD, **FIRST_TIME)

    app.screen.save()

    for handler in logging.getLogger().handlers[:]:
        if str(tmp_path) in getattr(handler, "baseFilename", ""):
            handler.flush()
            logging.getLogger().removeHandler(handler)
            handler.close()
    text = log_path.read_text(encoding="utf-8")
    assert "RuntimeError" in text
    assert BB_PASSWORD not in text and PASSWORD not in text and KEY not in text


# ---- one window at a time -------------------------------------------------------------


def test_a_second_start_brings_the_open_window_forward_instead():
    forward = []

    assert window.claim_single_window(first=lambda name: True, bring_forward=forward.append)
    assert not window.claim_single_window(first=lambda name: False, bring_forward=forward.append)
    assert forward == [window.TITLE]
```

Append to `agent/tests/test_cli.py`:

```python
def test_window_opens_the_accounts_window(world, monkeypatch):
    from sla_agent import window

    opened = []
    monkeypatch.setattr(window, "main", lambda tools, link=None: opened.append(link) or 0)

    assert cli.main(["window", "sla-agent:accounts"]) == 0

    assert opened == ["sla-agent:accounts"]


def test_window_without_tkinter_points_to_the_terminal(world, monkeypatch, capsys):
    import sys

    import sla_agent

    monkeypatch.delitem(sys.modules, "sla_agent.window", raising=False)
    monkeypatch.delattr(sla_agent, "window", raising=False)
    monkeypatch.setitem(sys.modules, "tkinter", None)

    assert cli.main(["window"]) == 1

    assert "sla-agent setup" in capsys.readouterr().out
```

- [ ] **Step 2: Run them to see them fail**

Run: `.venv/Scripts/python.exe -m pytest agent/tests/test_window.py agent/tests/test_cli.py -k "window"`
Expected: FAIL — `ImportError: cannot import name 'window' from 'sla_agent'` for `test_window.py`, and `argparse` exiting with "invalid choice: 'window'" for the CLI tests.

- [ ] **Step 3: Write `window.py` and the command**

Create `agent/sla_agent/window.py`:

```python
"""The School-Life-Assistant window (spec 2026-10-01-accounts-window-design.md, 3): set up this laptop the first
time, then see and change its accounts, with no terminal.

The screens only collect what the student types and show the answers; accounts.py checks and saves. Each check runs
on a worker thread so the window never freezes, and its answer comes back through Tk's event loop, since Tk may only
be used from its own thread (tests pass `run=run_at_once`). One window at a time: a second start brings the open one
forward (claim_single_window)."""

import logging
import queue
import threading
import tkinter as tk
import webbrowser
from datetime import datetime
from tkinter import ttk

from sla_agent import accounts
from sla_agent.accounts import Result, SetupForm
from sla_agent.log import protect
from sla_agent.state import load_state

log = logging.getLogger(__name__)

TITLE = "School-Life-Assistant"
MUTEX = "SchoolLifeAssistant-Window"
DEFAULT_ADDRESS = "http://localhost:5000"
NO_OUTLOOK = "Don't read Outlook"
LOOKING = "Looking for classic Outlook…"
OUTLOOK_HELP = "Open Outlook (classic), sign in, wait for \"All folders are up to date\", then press Refresh."
CHECKING = "Checking…"
DONE = "Done. This laptop checks in every minute; everything syncs every 30 minutes."
EDUSOFT_PAUSES = {"bad_credentials": "paused: wrong student ID or password",
                  "extra_verification": "paused: EduSoft asked for extra verification"}
BLACKBOARD_PAUSES = {"bad_credentials": "paused: wrong username or password",
                     "extra_verification": "paused: Blackboard asked for extra verification"}
SYNC_STATES = {"on": "on: every minute", "off": "off", "nowhere": "points to a Python that no longer exists"}
SECRETS = ("key", "password", "bb_password")


# ---- running checks ---------------------------------------------------------------------


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


# ---- small pieces --------------------------------------------------------------------------


def mark(result):
    return ("✓ " if result.ok else "✗ ") + "\n".join((result.message, *result.notes))


def heading(parent, text, row):
    ttk.Label(parent, text=text, font=("Segoe UI", 14, "bold")).grid(
        row=row, column=0, columnspan=3, sticky="w", pady=(0, 8))


def section(parent, text, row):
    ttk.Label(parent, text=text, font=("Segoe UI", 10, "bold")).grid(
        row=row, column=0, columnspan=3, sticky="w", pady=(12, 2))


def field(parent, label, variable, row, secret=False):
    ttk.Label(parent, text=label).grid(row=row, column=0, sticky="w", padx=(0, 8), pady=2)
    ttk.Entry(parent, textvariable=variable, show="•" if secret else "", width=40).grid(
        row=row, column=1, sticky="ew", pady=2)


def answer_line(parent, variable, row):
    ttk.Label(parent, textvariable=variable, wraplength=500, justify="left").grid(
        row=row, column=0, columnspan=3, sticky="w")


def local_time(iso):
    return datetime.fromisoformat(iso).astimezone().strftime("%d/%m %H:%M")


# ---- the window ----------------------------------------------------------------------------


class App:
    """The window: the first-time form (SetupScreen) or Accounts (AccountsScreen), rebuilt after each save."""

    def __init__(self, root, tools, run=None):
        self.root, self.tools = root, tools
        self.run = run or run_in_background(root)
        root.title(TITLE)
        root.minsize(560, 0)
        root.columnconfigure(0, weight=1)
        self.body = None
        self.screen = None
        self.show()

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
            self.screen = SetupScreen(self, state)


class OutlookPicker:
    """A dropdown of the accounts in classic Outlook, looked for in the background, with Refresh."""

    def __init__(self, app, frame, variable, row, allow_none):
        self.app, self.variable, self.allow_none = app, variable, allow_none
        ttk.Label(frame, text="Account").grid(row=row, column=0, sticky="w", padx=(0, 8))
        self.box = ttk.Combobox(frame, textvariable=variable, state="readonly", width=38)
        self.box.grid(row=row, column=1, sticky="ew")
        self.refresh_button = ttk.Button(frame, text="Refresh", command=self.refresh)
        self.refresh_button.grid(row=row, column=2, padx=(8, 0))
        self.note = tk.StringVar(frame)
        answer_line(frame, self.note, row + 1)
        self.refresh()

    def refresh(self):
        self.note.set(LOOKING)
        self.refresh_button.state(["disabled"])
        self.app.run(lambda: accounts.outlook_accounts(self.app.tools), self.found)

    def found(self, answer):
        if not self.box.winfo_exists():  # the form was saved and replaced meanwhile
            return
        self.refresh_button.state(["!disabled"])
        found, problem = ([], answer.message) if isinstance(answer, Result) else answer
        choices = ([NO_OUTLOOK] if self.allow_none else []) + found
        self.box["values"] = choices
        if self.variable.get() not in choices:
            self.variable.set(choices[0] if choices else "")
        self.note.set(f"{problem} {OUTLOOK_HELP}" if problem else "")


class SetupScreen:
    """First time: one form for the website, EduSoft, Blackboard (optional) and Outlook (optional) (spec 3.1)."""

    def __init__(self, app, state):
        self.app = app
        frame = app.body
        names = ("address", "key", "student_id", "password", "bb_user", "bb_password", "outlook")
        self.values = {name: tk.StringVar(frame) for name in names}
        self.values["address"].set(state.server_url or DEFAULT_ADDRESS)
        self.values["student_id"].set(state.student_id or "")
        self.values["outlook"].set(NO_OUTLOOK)
        self.answers = {name: tk.StringVar(frame) for name in ("site", "edusoft", "blackboard", "outlook", "sync")}

        heading(frame, "Set up this laptop", 0)
        section(frame, "Website", 1)
        field(frame, "Address", self.values["address"], 2)
        field(frame, "Device key", self.values["key"], 3, secret=True)
        ttk.Button(frame, text="Get a key", command=self.get_key).grid(row=3, column=2, padx=(8, 0))
        answer_line(frame, self.answers["site"], 4)
        section(frame, "EduSoft", 5)
        field(frame, "Student ID", self.values["student_id"], 6)
        field(frame, "Password", self.values["password"], 7, secret=True)
        answer_line(frame, self.answers["edusoft"], 8)
        section(frame, "Blackboard (optional)", 9)
        field(frame, "Username", self.values["bb_user"], 10)
        field(frame, "Password", self.values["bb_password"], 11, secret=True)
        answer_line(frame, self.answers["blackboard"], 12)
        section(frame, "Outlook (optional)", 13)
        self.outlook = OutlookPicker(app, frame, self.values["outlook"], 14, allow_none=True)
        answer_line(frame, self.answers["outlook"], 16)
        self.save_button = ttk.Button(frame, text="Check and save", command=self.save)
        self.save_button.grid(row=17, column=0, columnspan=3, sticky="e", pady=(16, 0))
        answer_line(frame, self.answers["sync"], 18)

    def get_key(self):
        webbrowser.open(self.values["address"].get().strip().rstrip("/") + "/school/devices")

    def form(self):
        value = {name: variable.get() for name, variable in self.values.items()}
        for secret in SECRETS:
            protect(value[secret])
        outlook = "" if value["outlook"] == NO_OUTLOOK else value["outlook"]
        return SetupForm(address=value["address"].strip(), key=value["key"].strip(),
                         student_id=value["student_id"].strip(), password=value["password"],
                         bb_user=value["bb_user"].strip(), bb_password=value["bb_password"], outlook=outlook)

    def save(self):
        form = self.form()
        for answer in self.answers.values():
            answer.set("")
        self.answers["sync"].set(CHECKING)
        self.save_button.state(["disabled"])
        self.app.run(lambda: accounts.first_setup(load_state(), form, self.app.tools), self.saved)

    def saved(self, results):
        self.save_button.state(["!disabled"])
        if isinstance(results, Result):  # something unexpected went wrong
            self.answers["sync"].set(mark(results))
            return
        self.answers["sync"].set("")
        for step, result in results.items():
            self.answers[step].set(mark(result))
        if not results.get("edusoft", Result(False, "")).ok:
            return  # nothing saved: the form stays filled in to correct
        for secret in SECRETS:
            self.values[secret].set("")
        lines = [DONE] if results["sync"].ok else []
        lines += [mark(result) for step, result in results.items()
                  if step in ("blackboard", "outlook", "sync") and (not result.ok or result.notes)]
        self.app.show(notice="\n".join(lines))


class AccountsScreen:
    """Set up: one row per account with Change (spec 3.2); Change opens that account's fields under its row."""

    ROWS = ("site", "edusoft", "blackboard", "outlook", "sync")
    NAMES = {"site": "Website", "edusoft": "EduSoft", "blackboard": "Blackboard", "outlook": "Outlook",
             "sync": "Automatic sync"}

    def __init__(self, app, state, notice=""):
        self.app, self.state = app, state
        self.sync_state = accounts.sync_task_state(app.tools)
        frame = app.body
        heading(frame, "Accounts", 0)
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
        return SYNC_STATES[self.sync_state]

    def button_label(self, row):
        if row == "sync":
            return None if self.sync_state == "on" else "Repair"
        if (row == "blackboard" and not self.state.blackboard_username) or (
                row == "outlook" and not self.state.outlook_account):
            return "Set up"
        return "Change"

    def open(self, row):
        if row == "sync":
            self.buttons["sync"].state(["disabled"])
            self.answers["sync"].set(CHECKING)
            self.app.run(lambda: accounts.turn_on_sync(self.app.tools),
                         lambda result: self.app.show(notice=mark(result)))
            return
        for button in self.buttons.values():
            button.state(["disabled"])
        self.editor = Editor(self, row)

    def closed(self):
        self.editor = None
        for button in self.buttons.values():
            button.state(["!disabled"])


class Editor:
    """One account's fields under its row, with Check and save and Cancel."""

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
            OutlookPicker(screen.app, self.frame, self.values["outlook"], 0, allow_none=False)
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
    _sharp_on_high_dpi()
    root = tk.Tk()
    root.report_callback_exception = lambda *exc: log.error("Accounts window error", exc_info=exc)
    App(root, tools)
    root.mainloop()
    return 0
```

In `agent/sla_agent/cli.py` add the command (after `cmd_schedule`):

```python
def cmd_window(args):
    try:
        from sla_agent import window
    except ModuleNotFoundError as error:
        if error.name not in ("tkinter", "_tkinter"):
            raise
        say("This Python has no Tkinter. Use `sla-agent setup` in the terminal instead.")
        return 1
    return window.main(tools(), link=args.link)
```

register it in `COMMANDS` as `"window": cmd_window,` and in `main` after the `schedule` parser:

```python
    window = commands.add_parser("window", help="open the School-Life-Assistant window to set up or change accounts")
    window.add_argument("link", nargs="?", help="the sla-agent: link that opened it")
```

and add to the module docstring's command list:

```
    sla-agent window                 open the School-Life-Assistant window: set up or change your accounts
```

- [ ] **Step 4: Run every Python test**

Run: `.venv/Scripts/python.exe -m pytest`
Expected: PASS.

- [ ] **Step 5: Start it for real (main session)**

Run (an empty agent folder, so the real state isn't touched; nothing is saved):

```bash
SLA_AGENT_HOME="$(mktemp -d)" .venv/Scripts/python.exe - <<'PY'
import os, subprocess, sys, time
first = subprocess.Popen([sys.executable, "-m", "sla_agent", "window"])
time.sleep(5)
started = time.monotonic()
second = subprocess.run([sys.executable, "-m", "sla_agent", "window"], timeout=30)
print("second start:", second.returncode, f"{time.monotonic() - started:.1f} s;", "first still open:", first.poll() is None)
first.kill()
log = open(os.path.join(os.environ["SLA_AGENT_HOME"], "agent.log"), encoding="utf-8").read()
print("ERROR lines in agent.log:", log.count(" ERROR "))
PY
```

Expected: `second start: 0`, under 3 s, `first still open: True`, and `ERROR lines in agent.log: 0`. Record the output in the ledger. (The student sees the window itself in Task 7.)

- [ ] **Step 6: Commit**

```bash
git add agent/sla_agent/window.py agent/sla_agent/cli.py agent/tests/test_window.py agent/tests/test_cli.py
git commit -m "feat(agent): the School-Life-Assistant window: first-time form, Accounts with Change, one window at a time

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: The agent's messages point to Accounts

**Files:**
- Modify: `agent/sla_agent/sync.py`, `agent/sla_agent/cli.py`
- Test: `agent/tests/test_sync.py`, `agent/tests/test_cli.py`

**Interfaces:**
- Consumes: nothing new.
- Produces: the new wording below (Task 6 uses the same "in Accounts" phrasing on the website).

- [ ] **Step 1: Change the tests to the new wording**

In `agent/tests/test_sync.py`, the three assertions `assert "sla-agent setup" in outcome.message` (in the tests around lines 90 and 484) and `assert "sla-agent setup --blackboard" in outcome.message` (around line 237) become `assert "in Accounts" in outcome.message`.

In `agent/tests/test_cli.py`:
- `test_sync_now_while_paused_explains_how_to_fix_it`: `assert "sla-agent setup" in ...` → `assert "in Accounts" in capsys.readouterr().out`
- `test_status_shows_a_pause_and_what_to_do`: `assert "sla-agent setup" in out` → `assert "in Accounts" in out`
- the Blackboard status test asserting `"sla-agent setup --blackboard" in out` → `assert "in Accounts" in out`
- `test_run_without_setup_says_so` and `test_setup_blackboard_alone_needs_edusoft_setup_first` keep `"sla-agent setup"` (the not-set-up message still names it).

Append:

```python
def test_status_says_where_to_set_up_blackboard_and_outlook(world, capsys):
    configure()

    cli.main(["status"])

    out = capsys.readouterr().out
    assert "Blackboard:  not set up (set it up in Accounts" in out
    assert "Outlook:     not set up (set it up in Accounts" in out


def test_not_set_up_mentions_the_window(world, capsys):
    assert cli.main(["status"]) == 1
    assert "School-Life-Assistant.cmd" in capsys.readouterr().out
```

- [ ] **Step 2: Run them to see them fail**

Run: `.venv/Scripts/python.exe -m pytest agent/tests/test_sync.py agent/tests/test_cli.py`
Expected: FAIL — the changed and new assertions (the messages still say `sla-agent setup`).

- [ ] **Step 3: The new wording**

`agent/sla_agent/sync.py`:

```python
PAUSE_MESSAGES = {
    "bad_credentials": "EduSoft rejected your student ID or password. EduSoft sync is paused; "
                       "enter them again in Accounts (open School-Life-Assistant on this laptop).",
    "extra_verification": "EduSoft asked for extra verification (CAPTCHA or code). EduSoft sync is "
                          "paused; save the pages from your browser and use `sla-agent import`.",
}
BLACKBOARD_PAUSE_MESSAGES = {
    "bad_credentials": "Blackboard rejected your username or password. Blackboard sync is paused; "
                       "enter them again in Accounts (open School-Life-Assistant on this laptop).",
    "extra_verification": "Blackboard asked for extra verification (CAPTCHA, code or Microsoft sign-in). "
                          "Blackboard sync is paused.",
}
```

`agent/sla_agent/cli.py`:

```python
NOT_SET_UP = ("sla-agent isn't set up yet. Open School-Life-Assistant (double-click School-Life-Assistant.cmd in "
              "the project folder), or run `sla-agent setup`.")
```

In `_sync`, the `DeviceKeyRejected` line becomes:

```python
        say(f"{error} Paste a new key in Accounts (open School-Life-Assistant), or run `sla-agent setup`.")
```

In `cmd_status`:

```python
    if not state.blackboard_username:
        say("Blackboard:  not set up (set it up in Accounts: open School-Life-Assistant)")
    elif state.blackboard_paused:
        say(f"Blackboard:  PAUSED ({state.blackboard_paused}). Change it in Accounts (open School-Life-Assistant).")
    else:
        say("Blackboard:  on")
    if state.outlook_account:
        say(f"Outlook:     on ({state.outlook_account})")
    else:
        say("Outlook:     not set up (set it up in Accounts: open School-Life-Assistant)")
    program = task_program()
    if program and not Path(program).exists():
        say("Sync task:   points to a Python that no longer exists (the project folder moved?). "
            "Run `sla-agent schedule`, or press Repair in Accounts.")
```

and in the module docstring, `sla-agent run                    what the scheduled task calls every 15 minutes` becomes `... every minute`.

- [ ] **Step 4: Run every Python test**

Run: `.venv/Scripts/python.exe -m pytest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent/sla_agent/sync.py agent/sla_agent/cli.py agent/tests/test_sync.py agent/tests/test_cli.py
git commit -m "feat(agent): pause and status messages point to Accounts instead of terminal commands

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: The website's Accounts page and "Change it in Accounts"

**Files:**
- Create: `web/src/main/java/vn/edu/hcmiu/sla/school/pages/AccountsController.java`, `web/src/main/resources/templates/school/accounts.html`
- Modify: `web/src/main/java/vn/edu/hcmiu/sla/school/pages/SyncStatus.java`, `web/src/main/resources/templates/school/fragments.html`, `courses.html`, `mailbox.html`, `devices.html`
- Test: `web/src/test/java/vn/edu/hcmiu/sla/school/pages/AccountsPageTest.java` (new), `SyncStatusTest.java`, `SchoolPagesTest.java`, `MailboxPageTest.java`, `DevicesPageTest.java`

**Interfaces:**
- Consumes: `SyncStatus.systemLines`, `SchoolSyncRunRepository.recentRuns(userId)`, `DeviceKeys.active(userId)`, `@schoolFormat.when(...)`.
- Produces: `SyncStatus.accountLines(List<RunInfo> runs, LocalDateTime now) -> List<SystemLine>` (every system in `SYSTEMS` order; state `"none"` with text "not set up" for Blackboard and Outlook, "never synced" for EduSoft and IUPay when no run has them); `GET /school/accounts` → template `school/accounts`; subnav name `accounts`.

- [ ] **Step 1: Write the failing tests**

Create `web/src/test/java/vn/edu/hcmiu/sla/school/pages/AccountsPageTest.java`:

```java
package vn.edu.hcmiu.sla.school.pages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.Map;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.hcmiu.sla.auth.AppUser;
import vn.edu.hcmiu.sla.school.SchoolTestData;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRun;
import vn.edu.hcmiu.sla.school.sync.DeviceKeys;

/** School → Accounts: what each system's sync says, the laptops, and the link that opens the window on the laptop. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AccountsPageTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    EntityManager db;

    @Autowired
    DeviceKeys deviceKeys;

    SchoolTestData data;
    AppUser an;

    @BeforeEach
    void anAccount() {
        data = new SchoolTestData(db);
        an = data.user("an@example.com");
    }

    String page() throws Exception {
        return mvc.perform(get("/school/accounts").with(user(an))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    static String main(String html) {
        return html.substring(html.indexOf("<main"), html.indexOf("</main>"));
    }

    @Test
    void theAccountsPageOpensTheWindowOnTheLaptopAndAsksForNothing() throws Exception {
        deviceKeys.create(an.id(), "My laptop", LocalDateTime.of(2026, 9, 1, 0, 0));

        String html = main(page());

        assertThat(html).contains("href=\"sla-agent:accounts\"", "Open Accounts on this laptop", "My laptop",
                "Start menu");
        assertThat(html).doesNotContain("type=\"password\"").doesNotContain("<form").doesNotContain("<input");
    }

    @Test
    void eachSystemShowsItsStateOrThatItIsNotSetUp() throws Exception {
        deviceKeys.create(an.id(), "My laptop", LocalDateTime.of(2026, 9, 1, 0, 0));
        SchoolSyncRun run = new SchoolSyncRun(an.id(), null, "scheduled", LocalDateTime.of(2026, 9, 28, 1, 0));
        run.finish(SchoolSyncRun.PARTIAL, LocalDateTime.of(2026, 9, 28, 1, 5), null, null);
        run.setSections(Map.of("timetable", Map.of("status", "ok"), "exams", Map.of("status", "ok"),
                "iupay", Map.of("status", "ok"),
                "blackboard", Map.of("status", "failed", "error_code", "bad_credentials", "error_message", "no")));
        db.persist(run);
        db.flush();

        String html = main(page());

        assertThat(html).contains("EduSoft:", "IUPay:", "synced",
                "Blackboard:", "paused: wrong username or password. Change it in Accounts.",
                "Outlook:", "not set up");
    }

    @Test
    void withoutALaptopThePageSaysWhereToStart() throws Exception {
        String html = main(page());

        assertThat(html).contains("Devices", "School-Life-Assistant.cmd").doesNotContain("sla-agent:accounts");
    }

    @Test
    void theAccountsTabComesAfterDevicesAndIsMarkedCurrent() throws Exception {
        String html = page();

        int devices = html.indexOf(">Devices</a>");
        int accounts = html.indexOf(">Accounts</a>");
        assertThat(devices).isPositive();
        assertThat(accounts).isGreaterThan(devices);
        assertThat(html.substring(html.lastIndexOf("<a", accounts), accounts))
                .contains("href=\"/school/accounts\"", "aria-current=\"page\"");
    }

    @Test
    void theAccountsPageNeedsLogin() throws Exception {
        mvc.perform(get("/school/accounts")).andExpect(redirectedUrl("/auth/login"));
    }
}
```

In `SyncStatusTest.java`:
- `wrongPasswordPausesAndSaysHowToFixIt`: `contains("sla-agent setup")` → `contains("in Accounts")`
- `oneLinePerSystemFromTheLatestRunThatIncludedIt`: `lines.get(1).text()).contains("sla-agent setup --blackboard")` → `lines.get(1).text()).isEqualTo("paused: wrong username or password. Change it in Accounts.")`
- `blackboardFailureHeadlineNamesBlackboard`: `contains("sla-agent setup --blackboard")` → `contains("in Accounts")`
- `noDeviceYetPointsToTheDevicesPage`: add `assertThat(result.detail()).contains("open School-Life-Assistant");`
- `laptopThatNeverCheckedIn`: add `assertThat(status(null, null, true, null, null).laptopWarning()).contains("School-Life-Assistant");`
- add:

```java
    @Test
    void accountLinesNameEverySystemEvenWithoutARun() {
        List<SystemLine> lines = SyncStatus.accountLines(List.of(run("success", "07:00", "07:05", null, edu())), NOW);

        assertThat(lines).extracting(SystemLine::name, SystemLine::state, SystemLine::text).containsExactly(
                tuple("EduSoft", "ok", "synced at 14:05"), tuple("IUPay", "none", "never synced"),
                tuple("Blackboard", "none", "not set up"), tuple("Outlook", "none", "not set up"));
    }
```

In `SchoolPagesTest.java`:
- `schoolHomeShowsALinePerSystem`: `contains("EduSoft:", "IUPay:", "Blackboard:", "sla-agent setup --blackboard")` → `contains("EduSoft:", "IUPay:", "Blackboard:", "Change it in Accounts.", "Open Accounts →")`
- add:

```java
    @Test
    void theStatusCardLinksToAccountsOnlyWhileSomethingIsPaused() throws Exception {
        deviceKeys.create(an.id(), "My laptop", LocalDateTime.of(2026, 9, 1, 0, 0));
        finishedRun(an, "scheduled", SchoolSyncRun.SUCCESS);

        assertThat(page("/school")).doesNotContain("Open Accounts →");
    }

    @Test
    void withoutBlackboardCoursesThePagePointsToAccounts() throws Exception {
        assertThat(page("/school/courses")).contains("Set up Blackboard in <a href=\"/school/accounts\">Accounts</a>");
    }
```

In `MailboxPageTest.java`, `beforeOutlookIsConnectedThePageSaysHow`: `.contains("sla-agent setup --outlook")` → `.contains("then choose your account in <a href=\"/school/accounts\">Accounts</a>")`.

In `DevicesPageTest.java`, `aNewDeviceKeyIsShownOnceAndWorks`: after `assertThat(key).isNotNull();` add `assertThat(html).contains("open School-Life-Assistant on the laptop");`.

- [ ] **Step 2: Run them to see them fail**

Run: `cd web && ./mvnw -B test "-Dtest=AccountsPageTest,SyncStatusTest,SchoolPagesTest,MailboxPageTest,DevicesPageTest"`
Expected: FAIL — test compilation stops at `SyncStatusTest` ("cannot find symbol … accountLines"). (Past that, `/school/accounts` would be 404 and the pages would still show the old wording.)

- [ ] **Step 3: The page and the wording**

`SyncStatus.java`, the changed texts:

```java
    static final Map<String, Problem> PROBLEMS = Map.of(
            "bad_credentials", new Problem("paused", "Paused: EduSoft rejected your student ID or password",
                    "Enter them again in Accounts (open School-Life-Assistant on your laptop)."),
```
(the rest of `PROBLEMS` unchanged)

```java
            "bad_credentials", new Problem("paused", "Paused: Blackboard rejected your username or password",
                    "Enter them again in Accounts (open School-Life-Assistant on your laptop)."),
```
(in `BLACKBOARD_PROBLEMS`)

```java
            "bad_credentials", new Problem("failed", "Sync failed: IUPay didn't recognise your student ID",
                    "Check the student ID in Accounts (open School-Life-Assistant on your laptop)."),
```
(in `IUPAY_PROBLEMS`)

```java
    static final Map<String, String> IUPAY_HINTS = Map.of(
            "extra_verification", "now asks for a captcha; tuition shows the last bills known.",
            "bad_credentials", "didn't recognise your student ID; check it in Accounts.");
```

```java
    static final Map<String, String> PAUSE_HINTS = Map.of(
            "EduSoft/bad_credentials", "paused: wrong student ID or password. Change it in Accounts.",
            "Blackboard/bad_credentials", "paused: wrong username or password. Change it in Accounts.",
            "EduSoft/extra_verification", "paused: asked for extra verification (CAPTCHA or code).",
            "Blackboard/extra_verification",
            "paused: asked for extra verification (CAPTCHA, code or Microsoft sign-in).");
```

In `laptopWarning`: `"Your laptop hasn't checked in yet. Open School-Life-Assistant on it to set it up."`; in `describe`'s no-device status: `"Add your laptop on the Devices page, then open School-Life-Assistant on it."`.

Add after `systemLines`:

```java
    static final List<String> OPTIONAL_SYSTEMS = List.of("Blackboard", "Outlook");

    /** Every system, for the Accounts page: its status-card line, or "not set up" / "never synced" without one. */
    public static List<SystemLine> accountLines(List<RunInfo> runs, LocalDateTime now) {
        List<SystemLine> lines = systemLines(runs, now);
        List<SystemLine> all = new ArrayList<>();
        for (SystemParts system : SYSTEMS) {
            all.add(lines.stream().filter(line -> line.name().equals(system.name())).findFirst()
                    .orElse(new SystemLine(system.name(), "none",
                            OPTIONAL_SYSTEMS.contains(system.name()) ? "not set up" : "never synced")));
        }
        return all;
    }
```

Create `AccountsController.java`:

```java
package vn.edu.hcmiu.sla.school.pages;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import vn.edu.hcmiu.sla.auth.AppUser;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRunRepository;
import vn.edu.hcmiu.sla.school.pages.SyncStatus.RunInfo;
import vn.edu.hcmiu.sla.school.sync.DeviceKeys;

/**
 * School → Accounts (docs/superpowers/specs/2026-10-01-accounts-window-design.md, 5.1): what each system's sync says
 * and the laptops, with a link that opens the School-Life-Assistant window on the laptop, where accounts are entered
 * and changed. The site never asks for or shows a password, student ID or username.
 */
@Controller
public class AccountsController {

    private final Clock clock;
    private final DeviceKeys deviceKeys;
    private final SchoolSyncRunRepository runs;

    public AccountsController(Clock clock, DeviceKeys deviceKeys, SchoolSyncRunRepository runs) {
        this.clock = clock;
        this.deviceKeys = deviceKeys;
        this.runs = runs;
    }

    @GetMapping("/school/accounts")
    String accounts(@AuthenticationPrincipal AppUser user, Model model) {
        LocalDateTime now = LocalDateTime.now(clock);
        model.addAttribute("accountLines", SyncStatus.accountLines(
                runs.recentRuns(user.id()).stream().map(RunInfo::of).toList(), now));
        model.addAttribute("devices", deviceKeys.active(user.id()));
        return "school/accounts";
    }
}
```

Create `web/src/main/resources/templates/school/accounts.html`:

```html
<!doctype html>
<html xmlns:th="http://www.thymeleaf.org" th:replace="~{layout :: page(~{::title}, ~{::main})}">
<head>
  <title>Accounts · School-Life-Assistant</title>
</head>
<body>
<main>
  <h1>Accounts</h1>
  <nav th:replace="~{school/fragments :: subnav('accounts')}"></nav>
  <p>Your EduSoft, Blackboard and Outlook accounts are entered and changed on your laptop, in the
     School-Life-Assistant window. Passwords are only ever entered there, never on this website.</p>

  <section class="card">
    <h2>Syncing</h2>
    <ul class="system-lines">
      <li th:each="line : ${accountLines}" th:class="|system-${line.state}|"><strong th:text="|${line.name}:|">EduSoft:</strong>
        <th:block th:text="${line.text}">synced at 14:05</th:block></li>
    </ul>
  </section>

  <section class="card" th:unless="${devices.isEmpty()}">
    <h2>Change your accounts</h2>
    <p><a class="button" href="sla-agent:accounts">Open Accounts on this laptop</a></p>
    <p class="muted">Nothing opened? Open School-Life-Assistant from the Start menu on the laptop where you set it
      up.</p>
    <h2>Your laptops</h2>
    <ul class="device-list">
      <li class="device" th:each="device : ${devices}">
        <div>
          <strong th:text="${device.name}">My laptop</strong>
          <span class="muted" th:text="|Last check-in: ${@schoolFormat.when(device.lastSeenAt)}|">Last check-in: never</span>
        </div>
      </li>
    </ul>
  </section>

  <section class="card" th:if="${devices.isEmpty()}">
    <h2>No laptop yet</h2>
    <p>Add your laptop on the <a th:href="@{/school/devices}">Devices</a> page, install sla-agent, then double-click
      <code>School-Life-Assistant.cmd</code> in the project folder.</p>
  </section>
</main>
</body>
</html>
```

`fragments.html`: in the subnav, after the Devices link add

```html
  <a th:href="@{/school/accounts}" th:attr="aria-current=${current == 'accounts' ? 'page' : null}">Accounts</a>
```

and in the comment above it add `accounts` to the list of page names; in `status-card`, after the `</ul>` of the system lines add

```html
  <p class="status-detail" th:if="${status.state == 'paused' or !systemLines.?[state == 'paused'].isEmpty()}">
    <a th:href="@{/school/accounts}">Open Accounts →</a></p>
```

`courses.html`, the empty-courses card:

```html
  <section class="card" th:if="${courses.isEmpty()}"><p>No Blackboard courses yet.
    Set up Blackboard in <a th:href="@{/school/accounts}">Accounts</a> on your laptop; your courses appear after the
    next sync.</p></section>
```

(`Set up Blackboard in <a …>Accounts</a>` stays on one source line: the test looks for it.)

`mailbox.html`, the not-connected card (keep `then choose your account in` and the link on one source line):

```html
  <section class="card" th:if="${status == null}">
    <p>Outlook isn't connected yet. On your laptop, open Outlook (classic) and sign in,
      then choose your account in <a th:href="@{/school/accounts}">Accounts</a>.</p>
  </section>
```

`devices.html`, the new-key card's sentence:

```html
      <p><strong>Copy it now. It won't be shown again.</strong> Then open School-Life-Assistant on the laptop
         (double-click <code>School-Life-Assistant.cmd</code> the first time) and paste it when asked.</p>
```

Keep "open School-Life-Assistant on the laptop" on one source line (the test looks for it).

- [ ] **Step 4: Run every Java test**

Run: `cd web && ./mvnw -B test`
Expected: `Tests run: N, Failures: 0, Errors: 0`, BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add web/src
git commit -m "feat(web): School → Accounts opens the laptop's window; status, Courses, Mailbox and Devices point to Accounts

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: README, the student's check, and the spec built (main session)

**Files:**
- Modify: `README.md`, `docs/superpowers/specs/2026-10-01-accounts-window-design.md`

- [ ] **Step 1: Every test**

Run: `.venv/Scripts/python.exe -m pytest` → PASS. Run: `cd web && ./mvnw -B test` → `Failures: 0, Errors: 0`.

- [ ] **Step 2: README**

In "The laptop agent (School sync)":
- Step 2 ("Get a device key") stays.
- Step 3 becomes: "**Set it up:** double-click `School-Life-Assistant.cmd` in the project folder. A window opens: the web app address is filled in (`http://localhost:5000`); paste the device key, enter your EduSoft student ID and password, and if you like your Blackboard login and the Outlook account to read, then press **Check and save**. Each login is checked once, and nothing is saved if one is wrong. It then turns on automatic sync (every minute while you're logged in to Windows; a full sync every 30 minutes, or soon after you press "Sync now") and adds School-Life-Assistant to your Desktop and Start menu."
- Add after step 3: "**Change an account later:** open School-Life-Assistant from the Start menu, or press **Open Accounts on this laptop** on School → Accounts, then press **Change** next to the account. A wrong new password changes nothing. Prefer the terminal? `sla-agent setup` still works."
- The command table gets a first row: `| Open the School-Life-Assistant window | sla-agent window |`.
- In the Mailbox paragraph, "then run `sla-agent setup --outlook`" becomes "then choose your account in Accounts (or run `sla-agent setup --outlook`)".

- [ ] **Step 3: The student tries it**

With the site running the new code (the student restarts their `mvn spring-boot:run`), run `.venv/Scripts/sla-agent.exe schedule` once (it makes the shortcuts and the `sla-agent:` link for the student's real laptop) and check the Desktop and Start menu have "School-Life-Assistant". Then ask the student to:
1. double-click `School-Life-Assistant.cmd` (Accounts opens, showing their student ID, Blackboard, Outlook, "Automatic sync: on: every minute");
2. press Change on Blackboard, enter a wrong password, press Check and save: the row says Blackboard rejected it; then Cancel, and press Sync now on the website to see Blackboard still syncs;
3. open School → Accounts on the website and press **Open Accounts on this laptop** (Edge asks once to open it): the open window comes to the front instead of a second one;
4. close it and open it from the Start menu.

Wait for their answer and fix anything reported.

- [ ] **Step 4: Mark the spec built and commit**

The spec's `**Status:**` becomes `Built (see docs/superpowers/plans/2026-10-01-accounts-window.md)`.

```bash
git add README.md docs/superpowers/specs/2026-10-01-accounts-window-design.md
git commit -m "docs: the accounts window in the README; the spec is built

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
