"""The setup (installer.py) on a fake laptop: unpacking writes a tiny app folder, and the self-check and the folder
renames can be made to fail. Nothing is started and no message box appears."""

import os

import pytest

from sla_agent import __version__, installer
from sla_agent.state import agent_home


class FakeMachine:
    """`version` is the app this setup carries; `failing` names folders whose app fails its self-check; `busy` is how
    many times renaming app\\ fails as "in use" before it works; `broken` names a folder that never renames."""

    def __init__(self, version="0.2.0", failing=(), busy=0, broken=None, claimed=True, busy_new=0):
        self.version, self.failing, self.busy, self.broken = version, set(failing), busy, broken
        self.busy_new = busy_new  # how many times renaming app.new fails, e.g. while the antivirus scans it
        self.claimed = claimed  # False: another setup is already running
        self.started, self.told, self.waited, self.checked = [], [], [], []
        self.now = 0.0

    def machine(self):
        return installer.Machine(unpack=self.unpack, self_check=self.self_check, wait_for_exit=self.wait_for_exit,
                                 start=self.started.append, tell=self.told.append, claim=lambda: self.claimed,
                                 sleep=self.sleep,
                                 rename=self.rename, clock=lambda: self.now)

    def unpack(self, folder):
        put_app(folder, self.version)

    def self_check(self, exe, version):
        self.checked.append((exe.parent.name, version))
        return exe.parent.name not in self.failing

    def wait_for_exit(self, process_id, seconds):
        self.waited.append(process_id)

    def sleep(self, seconds):
        self.now += seconds

    def rename(self, source, target):
        if source.name == "app" and self.busy:
            self.busy -= 1
            raise PermissionError("The process cannot access the file because it is being used by another process")
        if source.name == "app.new" and self.busy_new:
            self.busy_new -= 1
            raise PermissionError("The process cannot access the file because it is being used by another process")
        if source.name == self.broken:
            raise PermissionError("Access is denied")
        os.rename(source, target)


def put_app(folder, version):
    folder.mkdir(parents=True)
    (folder / installer.EXE).write_text(f"app {version}", encoding="utf-8")
    (folder / installer.VERSION_FILE).write_text(version, encoding="utf-8")


def app_version(home):
    return (home / "app" / installer.EXE).read_text(encoding="utf-8")


def leftovers(home):
    return sorted(path.name for path in home.iterdir() if path.name in ("app.new", "app.old"))


def window(home):
    return [str(home / "app" / installer.EXE), "window"]


@pytest.fixture
def home(tmp_path):
    folder = tmp_path / "Nguyễn Văn An" / "AppData" / "Local" / "SchoolLifeAssistant"
    folder.mkdir(parents=True)
    return folder


# ---- installing (double-clicked) -------------------------------------------------------


def test_a_first_install_unpacks_checks_swaps_in_and_opens_the_window(home):
    fake = FakeMachine()

    assert installer.install(home, "0.2.0", fake.machine()) == 0

    assert app_version(home) == "app 0.2.0"
    assert fake.checked == [("app.new", "0.2.0")]
    assert fake.started == [window(home)]
    assert leftovers(home) == []


def test_a_newer_setup_replaces_the_installed_app(home):
    put_app(home / "app", "0.1.0")

    assert installer.install(home, "0.2.0", FakeMachine().machine()) == 0

    assert app_version(home) == "app 0.2.0"
    assert leftovers(home) == []


def test_an_old_download_opens_the_newer_installed_app_instead(home):
    put_app(home / "app", "0.3.0")
    fake = FakeMachine(version="0.2.0")

    assert installer.install(home, "0.2.0", fake.machine()) == 0

    assert app_version(home) == "app 0.3.0"
    assert fake.checked == []
    assert fake.started == [window(home)]


def test_the_same_version_double_clicked_again_opens_at_once_even_while_a_sync_runs(home):
    put_app(home / "app", "0.2.0")
    fake = FakeMachine(busy=10**9)  # the scheduled run is syncing from app\

    assert installer.install(home, "0.2.0", fake.machine()) == 0

    assert fake.checked == [("app", "0.2.0")]
    assert fake.started == [window(home)]
    assert fake.told == []
    assert fake.now == 0


def test_the_same_version_that_fails_its_self_check_is_installed_again(home):
    put_app(home / "app", "0.2.0")
    fake = FakeMachine(failing={"app"})

    assert installer.install(home, "0.2.0", fake.machine()) == 0

    assert fake.checked == [("app", "0.2.0"), ("app.new", "0.2.0")]
    assert app_version(home) == "app 0.2.0"
    assert leftovers(home) == []


def test_a_new_app_that_fails_its_self_check_changes_nothing(home):
    put_app(home / "app", "0.1.0")
    fake = FakeMachine(failing={"app.new"})

    assert installer.install(home, "0.2.0", fake.machine()) == 1

    assert app_version(home) == "app 0.1.0"
    assert leftovers(home) == []
    assert fake.told == [installer.DID_NOT_START]
    assert fake.started == []


def test_an_app_in_use_is_tried_again_until_it_is_free(home):
    put_app(home / "app", "0.1.0")
    fake = FakeMachine(busy=2)

    assert installer.install(home, "0.2.0", fake.machine()) == 0

    assert app_version(home) == "app 0.2.0"
    assert fake.now == 2 * installer.RETRY_EVERY


def test_an_app_in_use_for_too_long_is_left_as_it_was_without_leftovers(home):
    put_app(home / "app", "0.1.0")
    fake = FakeMachine(busy=10**9)

    assert installer.install(home, "0.2.0", fake.machine()) == 1

    assert app_version(home) == "app 0.1.0"
    assert leftovers(home) == []
    assert fake.told == [installer.STILL_RUNNING]
    assert fake.now >= installer.INSTALL_WAIT


def test_when_the_new_app_cannot_move_in_the_old_one_is_put_back(home):
    put_app(home / "app", "0.1.0")

    assert installer.install(home, "0.2.0", FakeMachine(broken="app.new").machine()) == 1

    assert app_version(home) == "app 0.1.0"
    assert leftovers(home) == []


def test_leftovers_of_an_interrupted_setup_are_cleared_first(home):
    for name in ("app.new", "app.old"):
        (home / name).mkdir()
        (home / name / "half-unpacked.dll").write_bytes(b"MZ")

    assert installer.install(home, "0.2.0", FakeMachine().machine()) == 0

    assert app_version(home) == "app 0.2.0"
    assert leftovers(home) == []


# ---- updating (started by the agent) --------------------------------------------------


def test_an_update_waits_for_the_agent_then_installs_with_no_window(home):
    put_app(home / "app", "0.2.0")
    fake = FakeMachine(version="0.3.0")

    assert installer.install(home, "0.3.0", fake.machine(), update_of=4321) == 0

    assert app_version(home) == "app 0.3.0"
    assert fake.waited == [4321]
    assert fake.started == []
    assert fake.told == []


def test_an_update_keeps_trying_for_longer_and_never_shows_a_message(home):
    put_app(home / "app", "0.2.0")
    fake = FakeMachine(version="0.3.0", busy=10**9)  # the window stays open

    assert installer.install(home, "0.3.0", fake.machine(), update_of=4321) == 1

    assert app_version(home) == "app 0.2.0"
    assert leftovers(home) == []
    assert fake.told == []
    assert fake.now >= installer.UPDATE_WAIT


def test_a_broken_new_version_is_never_installed_by_an_update(home):
    put_app(home / "app", "0.2.0")
    fake = FakeMachine(version="0.3.0", failing={"app.new"})

    assert installer.install(home, "0.3.0", fake.machine(), update_of=4321) == 1

    assert app_version(home) == "app 0.2.0"
    assert fake.told == []


# ---- the setup's command line ---------------------------------------------------------


def test_main_installs_into_the_agents_folder_and_logs_it(monkeypatch, tmp_path):
    monkeypatch.chdir(tmp_path)
    fake = FakeMachine(version=__version__)

    assert installer.main([], machine=fake.machine()) == 0

    home = agent_home()
    assert app_version(home) == f"app {__version__}"
    assert fake.started == [window(home)]
    assert f"Installed {__version__}" in (home / "setup.log").read_text(encoding="utf-8")


def test_main_reads_update_mode_from_the_command_line(monkeypatch, tmp_path):
    monkeypatch.chdir(tmp_path)
    fake = FakeMachine(version=__version__)

    assert installer.main(["--update", "4321"], machine=fake.machine()) == 0

    assert fake.waited == [4321]
    assert fake.started == []


def test_an_unexpected_error_is_logged_and_shown(monkeypatch, tmp_path):
    monkeypatch.chdir(tmp_path)
    fake = FakeMachine(version=__version__)
    machine = fake.machine()

    def disk_full(folder):
        raise OSError("No space left on device")

    machine.unpack = disk_full

    assert installer.main([], machine=machine) == 1

    assert fake.told == [installer.WENT_WRONG]
    assert "No space left on device" in (agent_home() / "setup.log").read_text(encoding="utf-8")


# ---- one setup at a time (review I4) -----------------------------------------------------------


def test_a_second_setup_started_meanwhile_says_so_and_changes_nothing(monkeypatch, tmp_path):
    monkeypatch.chdir(tmp_path)
    fake = FakeMachine(version=__version__, claimed=False)

    assert installer.main([], machine=fake.machine()) == 1

    assert fake.told == [installer.BUSY]
    assert not (agent_home() / "app").exists()


def test_an_update_while_another_setup_runs_gives_up_quietly(monkeypatch, tmp_path):
    monkeypatch.chdir(tmp_path)
    fake = FakeMachine(version=__version__, claimed=False)

    assert installer.main(["--update", "4321"], machine=fake.machine()) == 1

    assert fake.told == []
    assert fake.waited == []


# ---- new files held for a moment (review M1) ---------------------------------------------------


def test_new_files_the_antivirus_holds_for_a_moment_are_waited_for(home):
    put_app(home / "app", "0.1.0")
    fake = FakeMachine(busy_new=2)

    assert installer.install(home, "0.2.0", fake.machine()) == 0

    assert app_version(home) == "app 0.2.0"
    assert leftovers(home) == []
    assert fake.told == []
