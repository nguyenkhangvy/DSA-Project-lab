from datetime import date, datetime, timedelta, timezone

import pytest
from sla_contract.schema import Exams, Timetable, Tuition

from agent.tests.fakes import FakeBlackboard, FakeEduSoft, FakeServer
from sla_agent import cli, credentials
from sla_agent.errors import BadCredentials, DeviceKeyRejected, ExtraVerification
from sla_agent.state import State, agent_home, load_state, save_state

SERVER = "https://sla.example.com"
KEY = "sla_device-key-0123456789"
STUDENT = "ITITIU20001"
PASSWORD = "s3cret-pass"

PARSERS = {
    "timetable": lambda html: Timetable(term_code="20261", courses=[]),
    "exams": lambda html: Exams(term_code="20261", exams=[]),
    "tuition": lambda html: Tuition(term_code="20261", amount_due=1, amount_paid=0, balance=1,
                                    due_date=date(2026, 10, 15)),
}


class World:
    """Everything the commands talk to, replaced by fakes."""

    def __init__(self, monkeypatch):
        self.edusoft = FakeEduSoft()
        self.server = FakeServer()
        self.answers = []
        self.secret_answers = []
        self.tasks = []
        self.servers_made = []
        monkeypatch.setattr(cli, "ask", lambda prompt: self.answers.pop(0))
        monkeypatch.setattr(cli, "ask_secret", lambda prompt: self.secret_answers.pop(0))
        monkeypatch.setattr(cli, "make_edusoft", lambda: self.edusoft)
        monkeypatch.setattr(cli, "make_server", self._make_server)
        monkeypatch.setattr(cli, "install_task", lambda *args, **kwargs: self.tasks.append("installed"))
        monkeypatch.setattr(cli, "remove_task", lambda *args, **kwargs: self.tasks.append("removed"))
        monkeypatch.setattr(cli, "PARSERS", PARSERS)
        self.blackboard = FakeBlackboard()
        monkeypatch.setattr(cli, "make_blackboard", lambda: self.blackboard)

    def _make_server(self, url, key):
        self.servers_made.append((url, key))
        return self.server

    def answer_setup(self, server=SERVER, key=KEY, student=STUDENT, password=PASSWORD,
                     bb_user="", bb_password=None):
        self.answers = [server, student, bb_user]
        self.secret_answers = [key, password] + ([bb_password] if bb_user else [])


@pytest.fixture
def world(monkeypatch):
    return World(monkeypatch)


def configure(paused=None, last_attempt_at=None):
    credentials.save_edusoft(STUDENT, PASSWORD)
    credentials.save_device_key(SERVER, KEY)
    save_state(State(server_url=SERVER, student_id=STUDENT, paused=paused, last_attempt_at=last_attempt_at))


# ---- setup -------------------------------------------------------------------


def test_setup_checks_both_logins_then_saves_secrets_and_schedules(world, isolated_agent, capsys):
    world.answer_setup()

    assert cli.main(["setup"]) == 0

    assert world.edusoft.logins == [(STUDENT, PASSWORD)]
    assert world.server.checks == 1
    assert credentials.load_edusoft(STUDENT) == PASSWORD
    assert credentials.load_device_key(SERVER) == KEY
    assert load_state() == State(server_url=SERVER, student_id=STUDENT)
    assert world.tasks == ["installed"]
    assert PASSWORD not in capsys.readouterr().out


def test_setup_with_a_wrong_password_saves_nothing(world, isolated_agent, capsys):
    world.answer_setup()
    world.edusoft.login_error = BadCredentials("rejected")

    assert cli.main(["setup"]) == 1

    assert len(world.edusoft.logins) == 1
    assert isolated_agent.entries == {}
    assert not (agent_home() / "state.json").exists()
    assert world.tasks == []
    assert "Nothing was saved" in capsys.readouterr().out


def test_setup_with_a_rejected_device_key_never_tries_edusoft(world, isolated_agent):
    world.answer_setup()
    world.server.check_error = DeviceKeyRejected("rejected")

    assert cli.main(["setup"]) == 1

    assert world.edusoft.logins == []
    assert isolated_agent.entries == {}


def test_setup_refuses_a_plain_http_web_app_address(world, isolated_agent, capsys):
    world.answer_setup(server="http://sla.example.com")

    assert cli.main(["setup"]) == 1

    assert world.servers_made == []
    assert "https://" in capsys.readouterr().out


def test_setup_when_edusoft_asks_for_extra_verification_saves_nothing(world, isolated_agent):
    world.answer_setup()
    world.edusoft.login_error = ExtraVerification("captcha")

    assert cli.main(["setup"]) == 1

    assert isolated_agent.entries == {}


def test_setup_again_clears_a_pause(world):
    configure(paused="bad_credentials")
    world.answer_setup()

    assert cli.main(["setup", "--no-schedule"]) == 0

    assert load_state().paused is None
    assert world.tasks == []


# ---- run (what the scheduled task calls) ---------------------------------------


def test_run_without_setup_says_so(world, capsys):
    assert cli.main(["run"]) == 1
    assert "sla-agent setup" in capsys.readouterr().out


def test_run_does_nothing_when_no_sync_is_due(world):
    configure()
    world.server.due, world.server.reason = False, "not_due"

    assert cli.main(["run"]) == 0

    assert (world.server.checks, world.server.starts, world.edusoft.logins) == (1, [], [])


@pytest.mark.parametrize("reason, trigger", [("interval", "scheduled"), ("never", "scheduled"),
                                             ("requested", "manual")])
def test_run_syncs_when_due(world, reason, trigger):
    configure()
    world.server.reason = reason

    assert cli.main(["run"]) == 0

    assert world.server.starts == [trigger]
    assert world.server.finishes[0][1].overall_status() == "success"
    assert load_state().last_result["status"] == "success"


def test_run_while_paused_still_checks_in_but_never_logs_in(world):
    configure(paused="bad_credentials")

    cli.main(["run"])

    assert world.server.checks == 1
    assert (world.server.starts, world.edusoft.logins) == ([], [])


# ---- sync-now ----------------------------------------------------------------


def test_sync_now_syncs_immediately_as_manual(world):
    configure()
    world.server.due = False

    assert cli.main(["sync-now"]) == 0

    assert world.server.starts == ["manual"]


def test_sync_now_refuses_within_5_minutes_of_the_last_attempt(world, capsys):
    configure(last_attempt_at=(datetime.now(timezone.utc) - timedelta(minutes=2)).isoformat())

    assert cli.main(["sync-now"]) == 1

    assert world.server.starts == []
    assert "5 minutes" in capsys.readouterr().out


def test_sync_now_while_paused_explains_how_to_fix_it(world, capsys):
    configure(paused="bad_credentials")

    assert cli.main(["sync-now"]) == 1

    assert world.edusoft.logins == []
    assert "sla-agent setup" in capsys.readouterr().out


# ---- fetch --save-html ---------------------------------------------------------


def test_fetch_saves_the_pages_locally_with_a_privacy_warning(world, tmp_path, capsys):
    configure()
    folder = tmp_path / "pages"

    assert cli.main(["fetch", "--save-html", str(folder)]) == 0

    saved = sorted(p.name for p in folder.iterdir())
    assert saved == [
        "exams-final.html", "exams-midterm.html", "home.html", "registration.html",
        "timetable-semester.html", "timetable-weekly.html", "tuition-report.json",
    ]
    assert (folder / "timetable-semester.html").read_text(encoding="utf-8") == "<html>timetable semester</html>"
    assert all(PASSWORD not in p.read_text(encoding="utf-8") for p in folder.iterdir())
    assert "personal" in capsys.readouterr().out
    assert world.server.starts == []


def test_fetch_with_a_wrong_password_pauses(world, tmp_path):
    configure()
    world.edusoft.login_error = BadCredentials("rejected")

    assert cli.main(["fetch", "--save-html", str(tmp_path / "pages")]) == 1

    assert load_state().paused == "bad_credentials"


# ---- import --------------------------------------------------------------------


def test_import_uploads_the_parts_found_in_a_saved_folder_even_while_paused(world, tmp_path):
    configure(paused="extra_verification")
    (tmp_path / "exams-final.html").write_text("<html>final</html>", encoding="utf-8")
    (tmp_path / "exams-midterm.html").write_text("<html>midterm</html>", encoding="utf-8")

    assert cli.main(["import", str(tmp_path), "--term", "20261"]) == 0

    assert world.server.starts == ["import"]
    [(_, result)] = world.server.finishes
    assert list(result.sections()) == ["exams"]
    assert world.edusoft.logins == []


def test_import_reads_a_saved_tuition_report(world, tmp_path):
    configure()
    (tmp_path / "tuition-report.json").write_text('{"pagesArray": []}', encoding="utf-8")

    assert cli.main(["import", str(tmp_path), "--term", "20261"]) == 0

    [(_, result)] = world.server.finishes
    assert list(result.sections()) == ["tuition"]


def test_import_of_exams_needs_to_know_the_semester(world, tmp_path, capsys):
    configure()
    (tmp_path / "exams-final.html").write_text("<html>final</html>", encoding="utf-8")

    assert cli.main(["import", str(tmp_path)]) == 1

    assert world.server.starts == []
    assert "--term" in capsys.readouterr().out


def test_import_of_an_empty_folder_says_nothing_was_found(world, tmp_path, capsys):
    configure()

    assert cli.main(["import", str(tmp_path)]) == 1

    assert "No saved EduSoft pages" in capsys.readouterr().out


# ---- status and forget -----------------------------------------------------------


def test_status_shows_a_pause_and_what_to_do(world, capsys):
    configure(paused="bad_credentials")

    assert cli.main(["status"]) == 0

    out = capsys.readouterr().out
    assert STUDENT in out
    assert "sla-agent setup" in out


def test_forget_removes_secrets_state_and_the_task(world, isolated_agent):
    configure()

    assert cli.main(["forget"]) == 0

    assert isolated_agent.entries == {}
    assert not (agent_home() / "state.json").exists()
    assert world.tasks == ["removed"]


BB_USER, BB_PASSWORD = "bbuser", "bb-s3cret"


def test_setup_can_add_blackboard_after_edusoft(world, isolated_agent):
    world.answer_setup(bb_user=BB_USER, bb_password=BB_PASSWORD)

    assert cli.main(["setup", "--no-schedule"]) == 0

    assert world.blackboard.logins == [(BB_USER, BB_PASSWORD)]
    assert world.blackboard.logouts == 1
    assert credentials.load_blackboard(BB_USER) == BB_PASSWORD
    assert load_state().blackboard_username == BB_USER


def test_setup_blackboard_alone_needs_edusoft_setup_first(world, capsys):
    assert cli.main(["setup", "--blackboard"]) == 1
    assert "sla-agent setup" in capsys.readouterr().out


def test_setup_blackboard_alone_saves_only_blackboard_and_clears_its_pause(world, isolated_agent):
    configure()
    state = load_state()
    state.blackboard_paused = "bad_credentials"
    save_state(state)
    world.answers = [BB_USER]
    world.secret_answers = [BB_PASSWORD]

    assert cli.main(["setup", "--blackboard"]) == 0

    assert world.edusoft.logins == []
    assert credentials.load_blackboard(BB_USER) == BB_PASSWORD
    assert (load_state().blackboard_username, load_state().blackboard_paused) == (BB_USER, None)


def test_a_wrong_blackboard_password_saves_nothing(world, isolated_agent, capsys):
    configure()
    world.blackboard.login_error = BadCredentials("rejected")
    world.answers = [BB_USER]
    world.secret_answers = ["wrong"]

    assert cli.main(["setup", "--blackboard"]) == 1

    assert len(world.blackboard.logins) == 1
    assert ("SchoolLifeAssistant-Blackboard", BB_USER) not in isolated_agent.entries
    assert load_state().blackboard_username is None
    assert "Nothing was saved" in capsys.readouterr().out


def test_status_shows_blackboard(world, capsys):
    configure()
    state = load_state()
    state.blackboard_username, state.blackboard_paused = BB_USER, "bad_credentials"
    save_state(state)

    cli.main(["status"])

    out = capsys.readouterr().out
    assert "Blackboard" in out
    assert "sla-agent setup --blackboard" in out


def test_forget_removes_the_blackboard_password_too(world, isolated_agent):
    configure()
    credentials.save_blackboard(BB_USER, BB_PASSWORD)
    state = load_state()
    state.blackboard_username = BB_USER
    save_state(state)

    cli.main(["forget"])

    assert isolated_agent.entries == {}


def test_run_syncs_blackboard_when_it_is_set_up(world, monkeypatch):
    from sla_contract.schema import Blackboard

    configure()
    credentials.save_blackboard(BB_USER, BB_PASSWORD)
    state = load_state()
    state.blackboard_username, state.registered_courses = BB_USER, [["IT093IU", "02"]]
    save_state(state)
    monkeypatch.setattr(cli, "read_blackboard", lambda client, registered: Blackboard(courses=[]))

    assert cli.main(["run"]) == 0

    assert world.blackboard.logins == [(BB_USER, BB_PASSWORD)]
    assert "blackboard" in world.server.finishes[0][1].sections()


def test_run_with_only_edusoft_paused_still_syncs_blackboard(world, monkeypatch):
    from sla_contract.schema import Blackboard

    configure(paused="bad_credentials")
    credentials.save_blackboard(BB_USER, BB_PASSWORD)
    state = load_state()
    state.blackboard_username, state.registered_courses = BB_USER, [["IT093IU", "02"]]
    save_state(state)
    monkeypatch.setattr(cli, "read_blackboard", lambda client, registered: Blackboard(courses=[]))

    cli.main(["run"])

    assert world.edusoft.logins == []
    assert world.blackboard.logins == [(BB_USER, BB_PASSWORD)]


def test_fetch_also_saves_blackboards_answers_when_set_up(world, tmp_path, monkeypatch):
    import json

    from sla_contract.schema import Blackboard

    configure()
    credentials.save_blackboard(BB_USER, BB_PASSWORD)
    state = load_state()
    state.blackboard_username, state.registered_courses = BB_USER, [["IT093IU", "02"]]
    save_state(state)

    def read(client, registered):
        client.capture["/v1/users/_1_1/courses?limit=100&expand=course"] = {"results": []}
        return Blackboard(courses=[])

    monkeypatch.setattr(cli, "read_blackboard", read)
    folder = tmp_path / "pages"

    assert cli.main(["fetch", "--save-html", str(folder)]) == 0

    saved = json.loads((folder / "blackboard-raw.json").read_text(encoding="utf-8"))
    assert saved == {"/v1/users/_1_1/courses?limit=100&expand=course": {"results": []}}
    assert world.blackboard.logouts == 1


def _fetch_blackboard_with(world, tmp_path, monkeypatch, saved_courses):
    from pathlib import Path

    from sla_contract.schema import Blackboard

    configure()
    credentials.save_blackboard(BB_USER, BB_PASSWORD)
    state = load_state()
    state.blackboard_username, state.registered_courses = BB_USER, saved_courses
    save_state(state)
    seen = []
    monkeypatch.setattr(cli, "read_blackboard", lambda client, registered: seen.append(registered) or Blackboard(courses=[]))
    world.edusoft.pages["registration"] = [
        {"registration": (Path(__file__).parent / "fixtures" / "registration.html").read_text(encoding="utf-8")}
    ] if saved_courses is None else [{"registration": "<html>not the registration page</html>"}]

    assert cli.main(["fetch", "--save-html", str(tmp_path / "pages")]) == 0
    return [(r.code, r.group) for r in seen[0]]


def test_fetch_reads_blackboard_for_the_courses_on_the_registration_page_it_just_saved(world, tmp_path, monkeypatch):
    # Before the first sync there is no saved course list yet.
    courses = _fetch_blackboard_with(world, tmp_path, monkeypatch, saved_courses=None)

    assert ("IT093IU", "02") in courses and len(courses) == 8


def test_fetch_falls_back_to_the_saved_course_list(world, tmp_path, monkeypatch):
    courses = _fetch_blackboard_with(world, tmp_path, monkeypatch, saved_courses=[["IT093IU", "02"]])

    assert courses == [("IT093IU", "02")]


def test_fetch_says_what_it_is_reading_so_a_slow_run_doesnt_look_stuck(world, tmp_path, monkeypatch, capsys):
    _fetch_blackboard_with(world, tmp_path, monkeypatch, saved_courses=[["IT093IU", "02"]])

    out = capsys.readouterr().out
    assert out.index("Reading EduSoft") < out.index("Reading Blackboard") < out.index("Saved ")
    assert "few minutes" in out


# ---- Outlook ------------------------------------------------------------------------

ME = "ititiu99001@student.hcmiu.edu.vn"


def test_setup_outlook_needs_edusoft_setup_first(world, capsys):
    assert cli.main(["setup", "--outlook"]) == 1
    assert "isn't set up" in capsys.readouterr().out


def test_setup_outlook_reads_the_only_account_after_asking(world, isolated_agent, monkeypatch, capsys):
    configure()
    monkeypatch.setattr(cli, "find_outlook_accounts", lambda: [ME])
    world.answers = [""]

    assert cli.main(["setup", "--outlook"]) == 0

    assert load_state().outlook_account == ME
    command = isolated_agent.registry.keys[("HKCU", r"Software\Classes\sla-mail\shell\open\command")][""]
    assert command.endswith('-m sla_agent open-mail "%1"')
    assert "never the text" in capsys.readouterr().out


def test_setup_outlook_asks_which_account_when_there_are_several(world, monkeypatch):
    configure()
    monkeypatch.setattr(cli, "find_outlook_accounts", lambda: ["me@gmail.com", ME])
    world.answers = ["2"]

    assert cli.main(["setup", "--outlook"]) == 0

    assert load_state().outlook_account == ME


@pytest.mark.parametrize("found, answers", [([ME], ["n"]), (["me@gmail.com", ME], ["3"]), ([], [])],
                         ids=["said-no", "bad-number", "no-account"])
def test_setup_outlook_changes_nothing_without_a_clear_answer(world, monkeypatch, found, answers):
    configure()
    monkeypatch.setattr(cli, "find_outlook_accounts", lambda: found)
    world.answers = answers

    assert cli.main(["setup", "--outlook"]) == 1

    assert load_state().outlook_account is None


def test_setup_outlook_without_classic_outlook_explains_what_to_do(world, monkeypatch, capsys):
    from sla_agent.errors import OutlookNotSetUp

    configure()

    def missing():
        raise OutlookNotSetUp("Classic Outlook isn't set up on this laptop.")

    monkeypatch.setattr(cli, "find_outlook_accounts", missing)

    assert cli.main(["setup", "--outlook"]) == 1
    assert "Open Outlook (classic)" in capsys.readouterr().out


def test_run_syncs_outlook_when_it_is_set_up(world, monkeypatch):
    from sla_contract.schema import Outlook

    configure()
    state = load_state()
    state.outlook_account = ME
    save_state(state)
    monkeypatch.setattr(cli, "read_outlook",
                        lambda address, since, context: Outlook(since=since, connected=True, emails=[]))

    assert cli.main(["run"]) == 0

    assert "outlook" in world.server.finishes[0][1].sections()


def test_status_shows_outlook(world, capsys):
    configure()
    state = load_state()
    state.outlook_account = ME
    save_state(state)

    cli.main(["status"])

    assert f"Outlook:     on ({ME})" in capsys.readouterr().out


def test_forget_removes_the_link_type(world, isolated_agent):
    from sla_agent import mail_link

    configure()
    mail_link.register("pythonw.exe")

    cli.main(["forget"])

    assert not [path for _, path in isolated_agent.registry.keys if "sla-mail" in path]


@pytest.fixture
def messages(monkeypatch):
    shown = []
    monkeypatch.setattr(cli, "show_message", shown.append)
    return shown


def test_open_mail_opens_the_email_in_outlook(monkeypatch, messages):
    opened = []
    monkeypatch.setattr(cli, "open_email", opened.append)

    assert cli.main(["open-mail", "sla-mail:00AB12/"]) == 0

    assert (opened, messages) == (["00AB12"], [])


def test_open_mail_refuses_anything_else(monkeypatch, messages):
    monkeypatch.setattr(cli, "open_email", lambda entry_id: pytest.fail("must not open anything"))

    assert cli.main(["open-mail", "javascript:alert(1)"]) == 1

    assert messages == ["This isn't a School-Life-Assistant email link."]


def test_open_mail_for_an_email_that_is_gone(monkeypatch, messages):
    from sla_agent.errors import EmailNotFound

    def gone(entry_id):
        raise EmailNotFound("This email is no longer in your Outlook Inbox.")

    monkeypatch.setattr(cli, "open_email", gone)

    assert cli.main(["open-mail", "sla-mail:00AB12"]) == 1

    assert messages == ["This email is no longer in your Outlook Inbox."]
