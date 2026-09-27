import copy
import json

import pytest
from pydantic import ValidationError

from sla_contract.schema import FinishRun
from tests.helpers import BB, SAMPLES, blackboard_payload, full_payload


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


# ---- contract/samples/: the Java website's tests check the same files ----------


@pytest.mark.parametrize("path", sorted(SAMPLES.glob("*.json")), ids=lambda path: path.name)
def test_every_shared_sample_is_accepted(path):
    FinishRun.model_validate(json.loads(path.read_text(encoding="utf-8")))


@pytest.mark.parametrize("path", sorted((SAMPLES / "invalid").glob("*.json")), ids=lambda path: path.name)
def test_every_shared_invalid_sample_is_refused(path):
    with pytest.raises(ValidationError):
        FinishRun.model_validate(json.loads(path.read_text(encoding="utf-8")))
