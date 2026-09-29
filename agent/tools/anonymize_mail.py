"""Make an anonymized test copy of the student's Inbox, for the sorting tests (agent/tests/test_mail_samples.py).

Usage (on the laptop, with classic Outlook set up and one sync done):
    python -m agent.tools.anonymize_mail agent/tests/fixtures/mail-samples.json --name "<your full name>"
        [--private "text to hide" ...]
--name is the student's name as their emails write it; the student ID is the one `sla-agent setup` saved.

Keeps each email's subject, time and thread, and the text of announcements (events, school tasks,
training points, promotions, Blackboard announcements). Replaces:
- the student's name, ID and address, and every --private text;
- every person (lecturers, IU staff) with a made-up person whose address is built from the made-up name
  the same way, so the lecturer rules still apply; private senders (classmates, personal mail such as Gmail)
  too, keeping their domain; companies keep their names;
- links, and runs of 6 or more digits;
- the whole text of every other email (replies from people, tickets, password resets, receipts, invoices).

Each email's `expected` result is what the sorting rules give now, sessions included
(docs/superpowers/specs/2026-09-28-mailbox-events-design.md, 3.2); the student checks them before the file is
committed. The tool reads Outlook only; it writes nothing but the output file."""

import argparse
import json
import re
import sys
import unicodedata
from dataclasses import replace
from datetime import date, datetime, timezone
from pathlib import Path

from sla_agent.mail_rules import (
    Context,
    Email,
    blackboard_lecturer,
    fold,
    lecturer_handle,
    name_handles,
    sender_handle,
    sort_email,
)
from sla_agent.outlook_reader import inbox_of, open_outlook, read_emails, semester_start
from sla_agent.state import load_state

KEEP_TEXT = {"event", "school_task", "training_points", "promotion"}
PLACEHOLDER_TEXT = "(Anonymized text.)"
FAKE_PEOPLE = [("Trần", "Văn", "An"), ("Lê", "Thị", "Bình"), ("Phạm", "Minh", "Châu"), ("Hoàng", "Quốc", "Dũng"),
               ("Võ", "Thanh", "Giang"), ("Đặng", "Hữu", "Hải"), ("Bùi", "Ngọc", "Khánh"), ("Đỗ", "Thu", "Lan"),
               ("Ngô", "Đức", "Minh"), ("Dương", "Mai", "Nga"), ("Lý", "Gia", "Phúc"), ("Mai", "Anh", "Quân"),
               ("Hồ", "Bảo", "Sơn"), ("Tạ", "Kim", "Thoa"), ("Châu", "Hoài", "Uyên"), ("Kiều", "Tuấn", "Vinh")]
# Addresses of private people outside IU's staff: classmates, and personal mail. Companies write from their own
# domains and keep their names (they aren't private, and the rules never read a non-staff sender's name).
PRIVATE_DOMAINS = {"student.hcmiu.edu.vn", "gmail.com", "googlemail.com", "yahoo.com", "yahoo.com.vn", "outlook.com",
                   "hotmail.com", "live.com", "icloud.com", "me.com", "proton.me", "protonmail.com"}
URL = re.compile(r"https?://\S+")
DIGITS = re.compile(r"\d{6,}")


def plain(text):
    """Without accents, keeping letter case: "Đặng Văn Long" -> "Do Xuan Hoi"."""
    text = unicodedata.normalize("NFD", text.replace("đ", "d").replace("Đ", "D"))
    return "".join(c for c in text if not unicodedata.combining(c))


class People:
    """Real person -> made-up person, keyed by the address beginning (e.g. "vmkhoa")."""

    def __init__(self):
        self.fakes = {}  # real handle -> (family, middle, given)

    def fake(self, handle):
        if handle not in self.fakes:
            self.fakes[handle] = FAKE_PEOPLE[len(self.fakes) % len(FAKE_PEOPLE)]
        return self.fakes[handle]

    def _written(self, handle, real_name):
        """The made-up name's words in the real name's word order (Vietnamese "Vo Minh Khoa" or the other
        way round, "Hung Quoc Pham")."""
        family, middle, given = self.fake(handle)
        words = re.findall(r"[a-z]+", fold(real_name))
        reverse = len(words) >= 2 and handle in {"".join(w[0] for w in words[:0:-1]) + words[0],
                                                 "".join(words[::-1])}
        return [given, middle, family] if reverse else [family, middle, given]

    def name(self, handle, real_name):
        """(made-up name written like the real one: word order, accents, capitals; its address beginning,
        built the same way: initials + given name, or the whole name joined)."""
        family, middle, given = self.fake(handle)
        name = " ".join(self._written(handle, real_name))
        if real_name == plain(real_name):
            name = plain(name)
        if real_name.isupper():
            name = name.upper()
        words = re.findall(r"[a-z]+", fold(real_name))
        canonical = re.findall(r"[a-z]+", fold(f"{family} {middle} {given}"))
        joined = handle in ("".join(words), "".join(words[::-1]))
        fake_handle = "".join(canonical) if joined else canonical[0][0] + canonical[1][0] + canonical[2]
        return name, fake_handle

    def variants(self, handle, real_name):
        """(real, made-up) pairs for the name as it may appear in a text: both word orders, with and without
        accents, as written and in capitals."""
        real, fake = real_name.split(), self._written(handle, real_name)
        pairs = []
        for real_words, fake_words in ((real, fake), (real[::-1], fake[::-1])):
            for shape in (lambda t: t, plain, str.upper, lambda t: plain(t).upper()):
                pairs.append((shape(" ".join(real_words)), shape(" ".join(fake_words))))
        return pairs

    def short(self, handle):
        """EduSoft's short form: "T.V.An"."""
        family, middle, given = self.fake(handle)
        return f"{family[0]}.{middle[0]}.{given}"


def anonymize(emails, context, student_name, student_id, private=()):
    """(anonymized emails, anonymized context, replacements): made-up people for real ones, and the text
    rules of this module's docstring. `emails` are mail_rules.Email objects."""
    people = People()
    swaps = []  # (real, fake), longest first when applied

    def person(handle, real_name, domain):
        name, fake_handle = people.name(handle, real_name)
        if len(real_name.split()) >= 2:
            swaps.extend(people.variants(handle, real_name))
        swaps.append((f"{handle}@{domain}", f"{fake_handle}@{domain}"))
        return name, f"{fake_handle}@{domain}"

    courses = []
    for code, course_name, lecturer in context.courses:
        if lecturer:
            handle = lecturer_handle(lecturer)
            swaps.append((lecturer, people.short(handle)))
            courses.append((code, course_name, people.short(handle)))
        else:
            courses.append((code, course_name, lecturer))

    student_words = student_name.split()
    for real in {student_name, plain(student_name), plain(student_name).upper(), " ".join(student_words[-2:]),
                 plain(" ".join(student_words[-2:]))}:
        swaps.append((real, "Student Name"))
    swaps.append((student_id, "STUDENT01"))  # replaced in any letter case
    swaps += [(text, "(private)") for text in private]

    fakes = {}
    for email in emails:
        handle = sender_handle(email)
        domain = email.sender_address.rpartition("@")[2]
        lecturer = blackboard_lecturer(email)
        if lecturer:
            real_name = email.sender_name.split(" - ")[0].strip()
            name, address = person(lecturer, real_name, "hcmiu.edu.vn")
            fakes[email.key] = (f"{name} - {address}", email.sender_address)
        elif handle and (handle in name_handles(email.sender_name)
                         or any(handle == lecturer_handle(c[2]) for c in context.courses if c[2])):
            fakes[email.key] = person(handle, email.sender_name, domain)
        elif domain.lower() in PRIVATE_DOMAINS and student_id.lower() not in email.sender_address.lower():
            fakes[email.key] = person(email.sender_address.rpartition("@")[0].lower(), email.sender_name, domain)

    swaps.sort(key=lambda pair: len(pair[0]), reverse=True)

    def clean(text):
        for real, fake in swaps:
            text = re.sub(re.escape(real), fake.replace("\\", "\\\\"), text, flags=re.IGNORECASE)
        return DIGITS.sub(lambda m: "0" * len(m.group(0)), URL.sub("https://example.com/link", text))

    result = []
    for email in emails:
        sorted_now = sort_email(email, context)
        keep = bool(set(sorted_now.categories) & KEEP_TEXT) and "requests_account" not in sorted_now.categories
        keep = keep or bool(blackboard_lecturer(email))
        name, address = fakes.get(email.key, (email.sender_name, email.sender_address))
        result.append(replace(email, sender_name=clean(name) if email.key not in fakes else name,
                              sender_address=address, subject=clean(email.subject),
                              text=clean(email.text) if keep else PLACEHOLDER_TEXT))
    fake_context = Context(courses=tuple(courses), bb_courses=context.bb_courses)
    return result, fake_context, swaps


def sample(email, context):
    item = sort_email(email, context)
    return {
        "subject": email.subject, "sender_name": email.sender_name, "sender_address": email.sender_address,
        "received_at": email.received_at.isoformat(), "thread_id": email.thread_id, "text": email.text,
        "expected": {
            "categories": item.categories, "from_lecturer": item.from_lecturer,
            "dates": [d.isoformat() for d in item.dates],
            "sessions": [s.model_dump(mode="json", exclude_none=True) for s in item.sessions],
            "register_by": item.register_by and item.register_by.isoformat(),
            "blackboard_title": item.blackboard_title,
            "class_changes": [c.model_dump(mode="json", exclude_none=True) for c in item.class_changes],
        },
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("output")
    parser.add_argument("--name", required=True, help="the student's full name, as in emails")
    parser.add_argument("--student-id", help="default: the one `sla-agent setup` saved")
    parser.add_argument("--private", action="append", default=[], help="any other text to hide")
    parser.add_argument("--since", type=date.fromisoformat)
    args = parser.parse_args(argv)

    state = load_state()
    if not state.outlook_account or not (args.student_id or state.student_id):
        print("Run `sla-agent setup`, `sla-agent setup --outlook` and one sync first.")
        return 1
    context = Context(courses=tuple(tuple(c) for c in state.courses or []),
                      bb_courses=tuple(tuple(c) for c in state.bb_courses or []))
    since = args.since or semester_start(state.term_code, datetime.now(timezone.utc).date())
    _, inbox = inbox_of(open_outlook(), state.outlook_account)
    emails, skipped = read_emails(inbox, since)

    fake, fake_context, swaps = anonymize(emails, context, args.name, args.student_id or state.student_id,
                                          args.private)
    samples = [sample(email, fake_context) for email in fake]
    real_results = [sort_email(email, context) for email in emails]
    differ = [s["subject"] for s, real in zip(samples, real_results)
              if s["expected"]["categories"] != real.categories
              or s["expected"]["from_lecturer"] != real.from_lecturer]
    Path(args.output).write_text(json.dumps({
        "about": "Anonymized copies of the student's Inbox made by agent/tools/anonymize_mail.py. "
                 "`expected` is what the sorting rules must give; checked by the student.",
        "context": {"courses": [list(c) for c in fake_context.courses],
                    "bb_courses": [list(c) for c in fake_context.bb_courses]},
        "emails": samples,
    }, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")

    written = Path(args.output).read_text(encoding="utf-8")
    leaks = [real for real, _ in swaps if len(real) >= 4 and real.lower() in written.lower()]
    print(f"emails: {len(samples)} (skipped {skipped}), names and addresses replaced: {len({r for r, _ in swaps})}, "
          f"still present: {len(leaks)}, sorted differently after anonymizing: {len(differ)}")
    for subject in differ:
        print("  differs:", subject)
    return 0


if __name__ == "__main__":
    sys.exit(main())
