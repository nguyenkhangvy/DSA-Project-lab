"""The tool that makes agent/tests/fixtures/mail-samples.json: made-up people must sort like the real ones."""

from datetime import datetime, timezone

from agent.tools.anonymize_mail import PLACEHOLDER_TEXT, anonymize
from sla_agent.mail_rules import Context, Email, sort_email

ARRIVED = datetime(2026, 9, 21, 1, 5, tzinfo=timezone.utc)
CONTEXT = Context(courses=(("IT093IU", "Web Application Development", "P.Q.Hùng"),),
                  bb_courses=(("Web Application Development_S1_2026-27_G02", "IT093IU"),))


def email(key, subject, text, address, name):
    return Email(key=key * 64, entry_id="00AB", thread_id=None, received_at=ARRIVED, sender_name=name,
                 sender_address=address, subject=subject, text=text)


EMAILS = [
    email("1", "Web Application Development_S1_2026-27_G02: Online class on 22/9",
          "Class 22/9 is online: https://teams.microsoft.com/l/meetup-join/19%3a 22-9-2026. Hung Quoc Pham, Ph.D.",
          "bb@hcmiu.edu.vn", "Hung Quoc Pham - pqhung@hcmiu.edu.vn"),
    email("2", "Re: Slide bài tập", "Dear Thị Mai, see page 78. Khoa", "vmkhoa@hcmiu.edu.vn", "Vo Minh Khoa"),
    email("3", "[Ticket: 12345] Trần Thị Mai – Yêu cầu mới được tạo", "Em là Trần Thị Mai, MSSV ITITIU99001",
          "oss@hcmiu.edu.vn", "OSS HCMIU"),
    email("4", "[THƯ MỜI] Workshop", "Gửi ititiu99001@student.hcmiu.edu.vn: cộng điểm rèn luyện. Mã 12345678.\n"
          "Thời gian: 13h30 – 16h30, ngày 24/9/2026", "oss@hcmiu.edu.vn", "P.CTSV [OSS]"),
]


def test_people_are_replaced_by_made_up_people_who_sort_the_same():
    fake, context, _ = anonymize(EMAILS, CONTEXT, "Trần Thị Mai", "ITITIU99001")

    text = repr(fake) + repr(context)
    for real in ("Thi Mai", "ITITIU99001", "ititiu99001", "pqhung", "vmkhoa", "Khoa", "Hung Quoc Pham", "P.Q.Hùng"):
        assert real not in text
    assert [sort_email(e, context).categories for e in fake] == [sort_email(e, CONTEXT).categories for e in EMAILS]
    assert [sort_email(e, context).from_lecturer for e in fake] == [True, True, False, False]
    assert sort_email(fake[0], context).class_changes[0].course_code == "IT093IU"


def test_private_people_outside_iu_staff_get_made_up_names_and_companies_keep_theirs():
    emails = [
        email("5", "Hỏi bài - Nguyễn Hoàng Long", "", "hoanglong.nguyen99@gmail.com", "Nguyễn Hoàng Long"),
        email("6", "Nhóm đồ án - Phan Thanh Tâm", "", "ititiu99002@student.hcmiu.edu.vn", "Phan Thanh Tâm"),
        email("7", "[ M-Invoice ] TB: Xuất hóa đơn", "", "noreply@m-invoice.vn", "M-INVOICE"),
        email("8", "Ghi chú", "", "ititiu99001@student.hcmiu.edu.vn", "Trần Thị Mai"),
    ]

    fake, context, _ = anonymize(emails, CONTEXT, "Trần Thị Mai", "ITITIU99001")

    text = repr(fake)
    for real in ("Hoàng Long", "Hoang Long", "hoanglong", "Thanh Tâm", "Thanh Tam", "ititiu99002"):
        assert real not in text
    assert fake[0].sender_address.endswith("@gmail.com")
    assert fake[1].sender_address.endswith("@student.hcmiu.edu.vn")
    assert (fake[2].sender_name, fake[2].sender_address) == ("M-INVOICE", "noreply@m-invoice.vn")
    assert fake[3].sender_name == "Student Name"
    assert [sort_email(e, context).categories for e in fake] == [sort_email(e, CONTEXT).categories for e in emails]


def test_texts_are_kept_only_for_announcements_without_links_or_long_numbers():
    fake, _, _ = anonymize(EMAILS, CONTEXT, "Trần Thị Mai", "ITITIU99001")

    assert "https://example.com/link" in fake[0].text and "teams.microsoft" not in fake[0].text
    assert (fake[1].text, fake[2].text) == (PLACEHOLDER_TEXT, PLACEHOLDER_TEXT)
    assert "student01@student.hcmiu.edu.vn" in fake[3].text.lower() and "00000000" in fake[3].text


def test_an_events_times_survive_anonymizing():
    fake, context, _ = anonymize(EMAILS, CONTEXT, "Trần Thị Mai", "ITITIU99001")

    sessions = sort_email(fake[3], context).sessions

    assert [(s.day.isoformat(), s.start.isoformat(), s.end.isoformat()) for s in sessions] == [
        ("2026-09-24", "13:30:00", "16:30:00")]
