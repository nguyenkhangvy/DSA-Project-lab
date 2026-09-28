"""The agent's data format: what it accepts and refuses. contract/samples/ holds example uploads that the
Java website's tests check too (web/src/test/java/vn/edu/hcmiu/sla/school/sync/SyncContractTest.java)."""

import copy
import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from sla_contract.schema import FinishRun

SAMPLES = Path(__file__).resolve().parents[1] / "samples"
BB = "https://blackboard.hcmiu.edu.vn"


def _sample(name):
    return json.loads((SAMPLES / name).read_text(encoding="utf-8"))


# A complete, valid upload from the agent. Times are Vietnam time (+07:00).
def full_payload():
    return _sample("finish-edusoft.json")


# A valid Blackboard section as the agent uploads it.
def blackboard_payload():
    return _sample("finish-blackboard.json")["blackboard"]["data"]


def test_a_complete_upload_is_accepted():
    finish = FinishRun.model_validate(full_payload())

    meeting = finish.timetable.data.courses[0].meetings[0]
    assert meeting.start_at.utcoffset().total_seconds() == 7 * 3600
    assert finish.tuition.data.balance == 12500000


def test_times_without_a_timezone_are_rejected():
    payload = full_payload()
    payload["exams"]["data"]["exams"][0]["start_at"] = "2026-12-12T08:00:00"

    with pytest.raises(ValidationError):
        FinishRun.model_validate(payload)


def test_a_class_that_ends_before_it_starts_is_rejected():
    payload = full_payload()
    meeting = payload["timetable"]["data"]["courses"][0]["meetings"][0]
    meeting["end_at"] = "2026-09-29T07:00:00+07:00"

    with pytest.raises(ValidationError):
        FinishRun.model_validate(payload)


@pytest.mark.parametrize(
    "path",
    [
        ("date_of_birth",),
        ("timetable", "data", "student_id"),
        ("timetable", "data", "courses", 0, "student_name"),
        ("tuition", "data", "bank_account"),
    ],
)
def test_unknown_fields_are_rejected_so_no_extra_personal_data_gets_in(path):
    payload = full_payload()
    target = payload
    for key in path[:-1]:
        target = target[key]
    target[path[-1]] = "something personal"

    with pytest.raises(ValidationError):
        FinishRun.model_validate(payload)


@pytest.mark.parametrize(
    "section",
    [
        {"status": "ok"},
        {"status": "failed", "error_message": "Tuition table not found"},
        {"status": "failed", "error_code": "made_up_code", "error_message": "x"},
        {"status": "done", "data": {}},
    ],
    ids=["ok-without-data", "failed-without-code", "unknown-error-code", "unknown-status"],
)
def test_each_part_is_either_ok_with_data_or_failed_with_a_reason(section):
    payload = full_payload()
    payload["tuition"] = section

    with pytest.raises(ValidationError):
        FinishRun.model_validate(payload)


def test_a_whole_run_failure_carries_no_data():
    payload = full_payload()
    payload["error_code"] = "bad_credentials"
    payload["error_message"] = "EduSoft rejected the password"

    with pytest.raises(ValidationError):
        FinishRun.model_validate(payload)


def test_an_upload_with_neither_data_nor_an_error_is_rejected():
    with pytest.raises(ValidationError):
        FinishRun.model_validate({"schema_version": 1})


def _failed(code="edusoft_changed"):
    return {"status": "failed", "error_code": code, "error_message": "Table not found"}


@pytest.mark.parametrize(
    "changes, expected",
    [
        ({}, "success"),
        ({"tuition": _failed()}, "partial"),
        ({"timetable": _failed(), "exams": _failed(), "tuition": _failed()}, "failed"),
        ({"exams": None, "tuition": None}, "success"),
        (
            {"timetable": None, "exams": None, "tuition": None,
             "error_code": "bad_credentials", "error_message": "EduSoft rejected the password"},
            "failed",
        ),
    ],
    ids=["all-ok", "one-failed", "all-failed", "only-timetable-sent", "whole-run-error"],
)
def test_overall_status(changes, expected):
    payload = copy.deepcopy(full_payload())
    for key, value in changes.items():
        if value is None:
            payload.pop(key, None)
        else:
            payload[key] = value

    assert FinishRun.model_validate(payload).overall_status() == expected


def test_a_blackboard_section_is_accepted_next_to_edusoft():
    payload = full_payload()
    payload["blackboard"] = {"status": "ok", "data": blackboard_payload()}

    finish = FinishRun.model_validate(payload)

    assert list(finish.sections()) == ["timetable", "exams", "tuition", "blackboard"]
    assert finish.blackboard.data.courses[0].assignments[0].score == 8.5


@pytest.mark.parametrize(
    "change",
    [
        lambda p: p["courses"][0]["announcements"][0].update(url="https://evil.example/x"),
        lambda p: p["courses"][0]["announcements"][0].update(posted_at="2026-09-28T02:00:00"),
        lambda p: p["courses"][0]["assignments"][0].update(status="done"),
        lambda p: p["courses"][0]["materials"][0].update(kind="video"),
        lambda p: p["courses"][0].update(student_email="s@example.com"),
        lambda p: p["courses"][0]["announcements"][0].update(text="x" * 5001),
    ],
    ids=["link-to-another-site", "naive-time", "unknown-status", "unknown-kind", "extra-field", "text-too-long"],
)
def test_bad_blackboard_data_is_rejected(change):
    data = blackboard_payload()
    change(data)

    with pytest.raises(ValidationError):
        FinishRun.model_validate({"blackboard": {"status": "ok", "data": data}})


def test_a_failed_blackboard_part_can_say_its_format_changed():
    finish = FinishRun.model_validate({
        "timetable": full_payload()["timetable"],
        "blackboard": {"status": "failed", "error_code": "source_changed", "error_message": "Unexpected format"},
    })

    assert finish.overall_status() == "partial"


# A valid Outlook section as the agent uploads it: results only, never an email's text.
def outlook_payload():
    return _sample("finish-outlook.json")["outlook"]["data"]


def test_an_outlook_section_is_accepted_next_to_the_others():
    payload = full_payload()
    payload["outlook"] = {"status": "ok", "data": outlook_payload()}

    finish = FinishRun.model_validate(payload)

    assert list(finish.sections()) == ["timetable", "exams", "tuition", "outlook"]
    first, second, third = finish.outlook.data.emails
    assert first.class_changes[0].kind == "online"
    assert second.categories == ["event", "training_points"]
    assert (third.sorted, third.class_changes[0].start.isoformat()) == (False, "13:15:00")
    assert (second.from_lecturer, second.sorted, second.class_changes) == (False, True, [])
    assert [(s.day.isoformat(), s.start.isoformat(), s.end and s.end.isoformat()) for s in second.sessions] == [
        ("2026-09-25", "13:30:00", "16:30:00"), ("2026-10-02", "08:00:00", None)]
    assert first.sessions == []
    assert (second.register_by.isoformat(), first.register_by) == ("2026-09-23", None)


@pytest.mark.parametrize(
    "change",
    [
        lambda p: p["emails"][0].update(text="The email's text must never leave the laptop."),
        lambda p: p["emails"][0].update(body="<p>html</p>"),
        lambda p: p["emails"][0].update(categories=["event", "training_points", "promotion"]),
        lambda p: p["emails"][0].update(categories=["event", "event"]),
        lambda p: p["emails"][0].update(categories=["homework"]),
        lambda p: p["emails"][0].update(entry_id="00000000a1b2"),
        lambda p: p["emails"][0].update(entry_id="javascript:alert(1)"),
        lambda p: p["emails"][0].update(key="not-a-hash"),
        lambda p: p["emails"][0].update(received_at="2026-09-21T01:05:00"),
        lambda p: p["emails"][0]["class_changes"][0].update(kind="moved"),
        lambda p: p["emails"][0].update(dates=["2026-09-22"] * 31),
        lambda p: p.update(emails=p["emails"] * 667),
        lambda p: p["emails"][1].update(loses_points=True),
        lambda p: p["emails"][1].update(sessions=p["emails"][1]["sessions"] * 5 + [p["emails"][1]["sessions"][0]]),
        lambda p: p["emails"][1]["sessions"][0].update(end="13:30:00"),
        lambda p: p["emails"][1]["sessions"][0].update(start="25:00:00"),
        lambda p: p["emails"][1]["sessions"][1].pop("start"),
        lambda p: p["emails"][1].update(register_by="22/9/2026"),
    ],
    ids=["text", "html", "three-categories", "repeated-category", "unknown-category", "lower-case-entry-id",
         "script-entry-id", "bad-key", "naive-time", "unknown-change", "too-many-dates", "too-many-emails",
         "loses-points", "eleven-sessions", "end-not-after-start", "bad-session-time", "session-without-start",
         "bad-register-by"],
)
def test_bad_outlook_data_is_rejected(change):
    data = outlook_payload()
    change(data)

    with pytest.raises(ValidationError):
        FinishRun.model_validate({"outlook": {"status": "ok", "data": data}})


@pytest.mark.parametrize("code", ["outlook_not_set_up", "outlook_blocked"])
def test_a_failed_outlook_part_says_why(code):
    finish = FinishRun.model_validate({
        "timetable": full_payload()["timetable"],
        "outlook": {"status": "failed", "error_code": code, "error_message": "Outlook problem"},
    })

    assert finish.overall_status() == "partial"


# ---- contract/samples/: the Java website's tests check the same files ----------


@pytest.mark.parametrize("path", sorted(SAMPLES.glob("*.json")), ids=lambda path: path.name)
def test_every_shared_sample_is_accepted(path):
    FinishRun.model_validate(json.loads(path.read_text(encoding="utf-8")))


@pytest.mark.parametrize("path", sorted((SAMPLES / "invalid").glob("*.json")), ids=lambda path: path.name)
def test_every_shared_invalid_sample_is_refused(path):
    with pytest.raises(ValidationError):
        FinishRun.model_validate(json.loads(path.read_text(encoding="utf-8")))
