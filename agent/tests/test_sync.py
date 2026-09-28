import logging
from datetime import date, datetime, timezone

import pytest
from sla_contract.schema import Blackboard, Exams, Timetable, Tuition

from agent.tests.fakes import FakeBlackboard, FakeEduSoft, FakeServer
from sla_agent.errors import (
    BadCredentials,
    ExtraVerification,
    NetworkError,
    ParseError,
    SessionExpired,
)
from sla_agent.log import setup_logging
from sla_agent.state import State
from sla_agent.sync import run_sync

NOW = datetime(2026, 10, 1, 7, 0, tzinfo=timezone.utc)
PASSWORD = "s3cret-pass"


def parse_timetable(html):
    return Timetable(term_code="20261", courses=[])


def parse_exams(html):
    return Exams(term_code="20261", exams=[])


def parse_tuition(html):
    return Tuition(term_code="20261", amount_due=1, amount_paid=0, balance=1, due_date=date(2026, 10, 15))


PARSERS = {"timetable": parse_timetable, "exams": parse_exams, "tuition": parse_tuition}


def broken_parser(html):
    raise ParseError("Tuition table not found")


@pytest.fixture
def state():
    return State(server_url="https://sla.example.com", student_id="ITITIU20001")



BB_PASSWORD = "bb-s3cret"


def empty_blackboard(client, registered):
    return Blackboard(courses=[])


def sync(state, edusoft, server=None, parsers=PARSERS, trigger="scheduled", blackboard=None, read=None):
    server = server or FakeServer()
    outcome = run_sync(trigger, state=state, edusoft=edusoft, server=server, parsers=parsers,
                       password=PASSWORD, now=NOW, blackboard=blackboard,
                       blackboard_password=BB_PASSWORD if blackboard else None,
                       read_blackboard=read or empty_blackboard)
    return outcome, server


def only_finish(server):
    [(run_id, result)] = server.finishes
    assert run_id == 41
    return result


def test_a_good_sync_uploads_all_three_parts(state):
    outcome, server = sync(state, FakeEduSoft())

    result = only_finish(server)
    assert result.overall_status() == "success"
    assert set(result.sections()) == {"timetable", "exams", "tuition"}
    assert outcome.status == "success"
    assert state.last_attempt_at == NOW.isoformat()
    assert state.last_result["status"] == "success"


def test_a_wrong_password_pauses_after_exactly_one_login_attempt(state):
    edusoft = FakeEduSoft(login_error=BadCredentials("EduSoft rejected the student ID or password."))

    outcome, server = sync(state, edusoft)

    assert len(edusoft.logins) == 1
    assert edusoft.page_calls == []
    result = only_finish(server)
    assert {name: part.error_code for name, part in result.sections().items()} == {
        "timetable": "bad_credentials", "exams": "bad_credentials", "tuition": "bad_credentials"}
    assert state.paused == "bad_credentials"
    assert "sla-agent setup" in outcome.message


def test_a_paused_agent_contacts_nobody(state):
    state.paused = "bad_credentials"
    edusoft = FakeEduSoft()

    outcome, server = sync(state, edusoft)

    assert (edusoft.logins, server.starts, server.finishes) == ([], [], [])
    assert outcome.status == "paused"


def test_extra_verification_pauses_and_suggests_import(state):
    outcome, server = sync(state, FakeEduSoft(login_error=ExtraVerification("CAPTCHA shown")))

    assert only_finish(server).timetable.error_code == "extra_verification"
    assert state.paused == "extra_verification"
    assert "sla-agent import" in outcome.message


def test_edusoft_unreachable_is_reported_but_does_not_pause(state):
    outcome, server = sync(state, FakeEduSoft(login_error=NetworkError("timed out")))

    assert only_finish(server).timetable.error_code == "network"
    assert state.paused is None


def test_a_page_the_parser_cannot_read_fails_only_that_part(state):
    outcome, server = sync(state, FakeEduSoft(), parsers={**PARSERS, "tuition": broken_parser})

    result = only_finish(server)
    assert result.overall_status() == "partial"
    assert (result.tuition.status, result.tuition.error_code) == ("failed", "edusoft_changed")
    assert result.timetable.status == "ok"


def test_an_expired_session_logs_in_again_once_and_retries_the_page(state):
    edusoft = FakeEduSoft(pages={"exams": [SessionExpired("login form shown"), {"term": "20261", "final": "", "midterm": ""}]})

    sync(state, edusoft)

    assert len(edusoft.logins) == 2
    assert edusoft.page_calls.count("exams") == 2


def test_a_session_that_keeps_expiring_fails_that_part_without_looping(state):
    edusoft = FakeEduSoft(pages={"exams": [SessionExpired("login form shown")]})

    outcome, server = sync(state, edusoft)

    assert len(edusoft.logins) == 2
    assert only_finish(server).exams.error_code == "session_expired"


def test_an_unexpected_parser_crash_still_finishes_the_run(state):
    def crashing(html):
        raise AttributeError("'NoneType' object has no attribute 'find_all'")

    outcome, server = sync(state, FakeEduSoft(), parsers={**PARSERS, "timetable": crashing})

    result = only_finish(server)
    assert result.timetable.status == "failed"
    assert result.exams.status == "ok"


def test_the_password_never_reaches_the_log_file(state, tmp_path):
    log_path = setup_logging(tmp_path)
    edusoft = FakeEduSoft(login_error=BadCredentials(f"rejected {PASSWORD}"))

    sync(state, edusoft)
    logging.getLogger("sla_agent.test").info("password was %s", PASSWORD)

    for handler in logging.getLogger().handlers:
        if str(tmp_path) in getattr(handler, "baseFilename", ""):
            handler.flush()
            logging.getLogger().removeHandler(handler)
            handler.close()
    text = log_path.read_text(encoding="utf-8")
    assert PASSWORD not in text
    assert "***" in text


def test_a_password_rejected_during_re_login_pauses_and_stops(state):
    class PasswordChangedMidSync(FakeEduSoft):
        def login(self, student_id, password):
            self.logins.append((student_id, password))
            if len(self.logins) > 1:
                raise BadCredentials("EduSoft rejected the student ID or password.")

    edusoft = PasswordChangedMidSync(pages={"timetable": [SessionExpired("login form shown")]})

    outcome, server = sync(state, edusoft)

    assert len(edusoft.logins) == 2
    assert edusoft.page_calls == ["timetable"]
    assert only_finish(server).timetable.error_code == "bad_credentials"
    assert state.paused == "bad_credentials"


@pytest.fixture
def bb_state(state):
    state.blackboard_username = "bbuser"
    state.registered_courses = [["IT093IU", "02"]]
    return state


def test_blackboard_syncs_after_edusoft_and_logs_out(bb_state):
    blackboard = FakeBlackboard()
    seen = []

    def read(client, registered):
        seen.append(registered)
        return Blackboard(courses=[])

    outcome, server = sync(bb_state, FakeEduSoft(), blackboard=blackboard, read=read)

    assert list(only_finish(server).sections()) == ["timetable", "exams", "tuition", "blackboard"]
    assert blackboard.logins == [("bbuser", BB_PASSWORD)]
    assert blackboard.logouts == 1
    assert outcome.status == "success"
    assert len(seen) == 1


def test_a_wrong_edusoft_password_still_syncs_blackboard_with_the_last_known_courses(bb_state):
    edusoft = FakeEduSoft(login_error=BadCredentials("rejected"))
    seen = []

    outcome, server = sync(bb_state, edusoft, blackboard=FakeBlackboard(),
                           read=lambda client, registered: seen.append(registered) or Blackboard(courses=[]))

    result = only_finish(server)
    assert result.timetable.error_code == "bad_credentials"
    assert result.blackboard.status == "ok"
    assert bb_state.paused == "bad_credentials"
    assert [tuple(r) for r in seen[0]] == [("IT093IU", "02")]


def test_a_wrong_blackboard_password_pauses_only_blackboard(bb_state):
    blackboard = FakeBlackboard(login_error=BadCredentials("rejected"))

    outcome, server = sync(bb_state, FakeEduSoft(), blackboard=blackboard)

    result = only_finish(server)
    assert (result.timetable.status, result.blackboard.error_code) == ("ok", "bad_credentials")
    assert (bb_state.paused, bb_state.blackboard_paused) == (None, "bad_credentials")
    assert len(blackboard.logins) == 1
    assert "sla-agent setup --blackboard" in outcome.message


def test_a_paused_blackboard_is_not_contacted(bb_state):
    bb_state.blackboard_paused = "bad_credentials"
    blackboard = FakeBlackboard()

    sync(bb_state, FakeEduSoft(), blackboard=blackboard)

    assert blackboard.logins == []


def test_both_paused_contacts_nobody(bb_state):
    bb_state.paused, bb_state.blackboard_paused = "bad_credentials", "bad_credentials"
    edusoft, blackboard = FakeEduSoft(), FakeBlackboard()

    outcome, server = sync(bb_state, edusoft, blackboard=blackboard)

    assert (edusoft.logins, blackboard.logins, server.starts) == ([], [], [])
    assert outcome.status == "paused"


def test_an_expired_blackboard_session_logs_in_again_once(bb_state):
    blackboard = FakeBlackboard()
    calls = []

    def read(client, registered):
        calls.append(1)
        if len(calls) == 1:
            raise SessionExpired("expired")
        return Blackboard(courses=[])

    _, server = sync(bb_state, FakeEduSoft(), blackboard=blackboard, read=read)

    assert len(blackboard.logins) == 2
    assert only_finish(server).blackboard.status == "ok"


def test_blackboard_without_a_known_course_list_waits_for_edusoft(state):
    state.blackboard_username, state.paused = "bbuser", "bad_credentials"

    _, server = sync(state, FakeEduSoft(), blackboard=FakeBlackboard())

    result = only_finish(server)
    assert result.blackboard.status == "failed"
    assert "course list" in result.blackboard.error_message


def test_the_registered_course_list_is_remembered(bb_state):
    from pathlib import Path

    page = (Path(__file__).parent / "fixtures" / "registration.html").read_text(encoding="utf-8")
    bb_state.registered_courses = None
    edusoft = FakeEduSoft(pages={"registration": [{"registration": page}]})

    sync(bb_state, edusoft)

    assert ["IT093IU", "02"] in bb_state.registered_courses
    assert len(bb_state.registered_courses) == 8


# A paused system is reported in every run, so the web page keeps showing the pause
# instead of dropping that system's line once newer runs only carry the other one.

def test_a_paused_edusoft_is_reported_as_paused_in_every_run(bb_state):
    bb_state.paused = "bad_credentials"
    edusoft = FakeEduSoft()

    outcome, server = sync(bb_state, edusoft, blackboard=FakeBlackboard())

    sections = only_finish(server).sections()
    assert edusoft.logins == []
    assert [(sections[n].status, sections[n].error_code) for n in ("timetable", "exams", "tuition")] == \
        [("failed", "bad_credentials")] * 3
    assert sections["blackboard"].status == "ok"
    assert "EduSoft rejected" in outcome.message


def test_a_paused_blackboard_is_reported_as_paused_in_every_run(bb_state):
    bb_state.blackboard_paused = "extra_verification"
    blackboard = FakeBlackboard()

    outcome, server = sync(bb_state, FakeEduSoft(), blackboard=blackboard)

    result = only_finish(server)
    assert blackboard.logins == []
    assert (result.blackboard.status, result.blackboard.error_code) == ("failed", "extra_verification")
    assert result.timetable.status == "ok"
    assert "Blackboard asked for extra verification" in outcome.message


def test_this_semesters_timetable_courses_stay_when_the_registration_list_moves_on(bb_state):
    # During registration week the list may already hold next semester's choices; the timetable
    # still has this semester's courses, so their Blackboard data isn't dropped.
    from pathlib import Path

    from sla_contract.schema import Course

    page = (Path(__file__).parent / "fixtures" / "registration.html").read_text(encoding="utf-8")
    edusoft = FakeEduSoft(pages={"registration": [{"registration": page}]})
    parsers = {**PARSERS, "timetable": lambda html: Timetable(term_code="20261", courses=[
        Course(course_code="IT093IU", course_name="Web Application Development", group="02"),
        Course(course_code="CS999IU", course_name="Only on the timetable", group="05"),
    ])}

    sync(bb_state, edusoft, parsers=parsers)

    assert ["CS999IU", "05"] in bb_state.registered_courses
    assert len(bb_state.registered_courses) == 9  # the 8 registered, plus CS999IU; IT093IU only once


# ---- Outlook ------------------------------------------------------------------------


def outlook_result(since):
    from sla_contract.schema import Outlook

    return Outlook(since=since, connected=True, emails=[])


@pytest.fixture
def mail_state(state):
    state.outlook_account = "ititiu99001@student.hcmiu.edu.vn"
    return state


def sync_with_outlook(state, edusoft, read_outlook, parsers=PARSERS, **options):
    server = FakeServer()
    outcome = run_sync("scheduled", state=state, edusoft=edusoft, server=server, parsers=parsers,
                       password=PASSWORD, now=NOW, read_outlook=read_outlook, **options)
    return outcome, server


def test_outlook_syncs_after_the_others_with_this_syncs_courses(mail_state):
    from sla_contract.schema import Course

    seen = []

    def read(address, since, context):
        seen.append((address, since, context))
        return outlook_result(since)

    parsers = {**PARSERS, "timetable": lambda html: Timetable(term_code="20261", courses=[
        Course(course_code="IT093IU", course_name="Web Application Development", lecturer="P.Q.Hùng")])}
    outcome, server = sync_with_outlook(mail_state, FakeEduSoft(), read, parsers=parsers)

    assert list(only_finish(server).sections()) == ["timetable", "exams", "tuition", "outlook"]
    [(address, since, context)] = seen
    assert (address, since) == ("ititiu99001@student.hcmiu.edu.vn", date(2026, 8, 1))
    assert context.courses == (("IT093IU", "Web Application Development", "P.Q.Hùng"),)
    assert (mail_state.term_code, mail_state.courses) == (
        "20261", [["IT093IU", "Web Application Development", "P.Q.Hùng"]])
    assert outcome.status == "success"


def test_outlook_uses_the_blackboard_course_names_read_in_the_same_sync(mail_state):
    from sla_contract.schema import BbCourse

    mail_state.blackboard_username, mail_state.registered_courses = "bbuser", [["IT093IU", "02"]]
    seen = []
    course = BbCourse(bb_id="_1_1", course_code="IT093IU", name="Web Application Development_S1_2026-27_G02",
                      url="https://blackboard.hcmiu.edu.vn/x")

    sync_with_outlook(mail_state, FakeEduSoft(), lambda a, s, c: seen.append(c) or outlook_result(s),
                      blackboard=FakeBlackboard(), blackboard_password=BB_PASSWORD,
                      read_blackboard=lambda client, registered: Blackboard(courses=[course]))

    assert seen[0].bb_courses == (("Web Application Development_S1_2026-27_G02", "IT093IU"),)


def test_a_failed_edusoft_keeps_the_courses_from_the_last_sync(mail_state):
    mail_state.term_code, mail_state.courses = "20261", [["IT093IU", "Web Application Development", "P.Q.Hùng"]]
    seen = []

    sync_with_outlook(mail_state, FakeEduSoft(login_error=NetworkError("timed out")),
                      lambda a, s, c: seen.append(c) or outlook_result(s))

    assert seen[0].courses == (("IT093IU", "Web Application Development", "P.Q.Hùng"),)


def test_an_outlook_problem_fails_only_outlook_and_pauses_nothing(mail_state):
    from sla_agent.errors import OutlookBlocked

    def blocked(address, since, context):
        raise OutlookBlocked("Outlook didn't let the agent read your mail.")

    outcome, server = sync_with_outlook(mail_state, FakeEduSoft(), blocked)

    result = only_finish(server)
    assert (result.timetable.status, result.outlook.error_code) == ("ok", "outlook_blocked")
    assert (outcome.status, mail_state.paused) == ("partial", None)


def test_an_unexpected_outlook_crash_still_finishes_the_run(mail_state):
    def crashing(address, since, context):
        raise KeyError("x")

    outcome, server = sync_with_outlook(mail_state, FakeEduSoft(), crashing)

    assert only_finish(server).outlook.error_code == "unknown"


def test_outlook_still_syncs_while_edusoft_is_paused(mail_state):
    mail_state.paused = "bad_credentials"
    edusoft = FakeEduSoft()

    outcome, server = sync_with_outlook(mail_state, edusoft, lambda a, s, c: outlook_result(s))

    result = only_finish(server)
    assert edusoft.logins == []
    assert (result.timetable.error_code, result.outlook.status) == ("bad_credentials", "ok")


def test_outlook_is_not_read_until_it_is_set_up(state):
    seen = []

    outcome, server = sync_with_outlook(state, FakeEduSoft(), lambda a, s, c: seen.append(a))

    assert seen == []
    assert "outlook" not in only_finish(server).sections()
