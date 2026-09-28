"""The sorting rules on anonymized copies of the student's real Inbox: agent/tests/fixtures/mail-samples.json,
made by agent/tools/anonymize_mail.py. Each `expected` result, sessions included, was checked by the student."""

import json
from datetime import datetime
from pathlib import Path

import pytest

from sla_agent.mail_rules import Context, Email, sort_email

SAMPLES = json.loads((Path(__file__).parent / "fixtures" / "mail-samples.json").read_text(encoding="utf-8"))
CONTEXT = Context(courses=tuple(tuple(c) for c in SAMPLES["context"]["courses"]),
                  bb_courses=tuple(tuple(c) for c in SAMPLES["context"]["bb_courses"]))


@pytest.mark.parametrize("sample", SAMPLES["emails"],
                         ids=[f"{n:02d} {s['subject'][:40]}" for n, s in enumerate(SAMPLES["emails"])])
def test_each_email_sorts_as_the_student_checked(sample):
    email = Email(key="0" * 64, entry_id="00", thread_id=sample["thread_id"],
                  received_at=datetime.fromisoformat(sample["received_at"]), sender_name=sample["sender_name"],
                  sender_address=sample["sender_address"], subject=sample["subject"], text=sample["text"])

    item = sort_email(email, CONTEXT)

    assert {
        "categories": item.categories, "from_lecturer": item.from_lecturer,
        "dates": [d.isoformat() for d in item.dates],
        "sessions": [s.model_dump(mode="json", exclude_none=True) for s in item.sessions],
        "register_by": item.register_by and item.register_by.isoformat(),
        "blackboard_title": item.blackboard_title,
        "class_changes": [c.model_dump(mode="json", exclude_none=True) for c in item.class_changes],
    } == sample["expected"]


def test_the_samples_cover_every_box():
    categories = {c for sample in SAMPLES["emails"] for c in sample["expected"]["categories"]}

    assert categories >= {"class", "event", "training_points", "school_task", "money", "requests_account",
                          "system_notice", "promotion"}
    assert any(sample["expected"]["class_changes"] for sample in SAMPLES["emails"])
    assert any(sample["expected"]["sessions"] for sample in SAMPLES["emails"])
