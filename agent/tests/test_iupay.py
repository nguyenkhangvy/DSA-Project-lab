"""IUPay: the bill-list reader (and, from Task 3, the client), against an anonymised copy of the student's
real reply of 2026-09-30 (agent/tests/fixtures/iupay-bills.json) and made-up bills."""

import json
from datetime import date
from pathlib import Path

import pytest

from sla_agent.errors import SourceChanged
from sla_agent.parsers.iupay import parse_iupay

FIXTURES = Path(__file__).parent / "fixtures"


def real_reply():
    return json.loads((FIXTURES / "iupay-bills.json").read_text(encoding="utf-8"))


def record(**changes):
    """One made-up bill as IUPay sends it: not paid yet, due Mon 15/02/2027 00:00 in Vietnam."""
    bill = {
        "_id": "000000000000000000000099", "hoc_ky_chu": "Academic year 2026-2027 - Semester 2", "hoc_ky": "20262",
        "so_phieu_bao": "E0000020001", "ma_sv": "ITITIU00000", "noi_dung": "Thu Học Phí HK 2 (2026-2027)",
        "kenh_thu": "", "ngay_thu": 0, "trang_thai": 0,
        "ma_loai_thu": {"typeFeeCode": "1", "typeFeeName": "Thu Học Phí"},
        "phai_thu": 40_000_000, "tong_thu": 40_000_000, "so_tien_phu_thu_tien_ich": 0, "mien_giam": 2_000_000,
        "ngay_tao": 1790739949000, "date_line": 1802624400000,
    }
    bill.update(changes)
    return bill


def reply(*records):
    """IUPay's answer, with the student block it always sends (made up here)."""
    return {"code": 200, "success": True, "data": {
        "data": {"records": list(records), "pagination": {"totalRows": len(records), "totalPages": 1}},
        "student": {"MaSV": "ITITIU00000", "HoTen": "Nguyễn Văn An", "Email": "ITITIU00000@student.hcmiu.edu.vn",
                    "MaLop": "ITIT24IU01"}}}


# ---- the reader ------------------------------------------------------------------


def test_the_real_reply_gives_seven_paid_bills_with_vietnam_dates():
    result = parse_iupay(real_reply())

    # 4073743 was paid at 00:00 on 01/10/2025 in Vietnam, still 30/09 in UTC.
    assert [(b.bill_no, b.term_code, b.amount, b.paid_on, b.channel) for b in result.bills] == [
        ("E0000014104", "20261", 65_250_000, date(2026, 9, 30), "Đóng qua kênh EduBill"),
        ("4073743", "20251", 36_900_000, date(2025, 10, 1), "Đóng offline"),
        ("E0000000208", "20252", 40_273_756, date(2026, 1, 16), "Đóng qua kênh EduBill"),
        ("4073742", "20242", 216_350, date(2025, 5, 10), "Đóng offline"),
        ("4073739", "20241", 32_000_000, date(2024, 9, 24), "Đóng offline"),
        ("4073741", "20242", 10_000, date(2025, 3, 12), "Đóng offline"),
        ("4073740", "20242", 10_275_000, date(2025, 3, 4), "Liên ngân hàng"),
    ]
    assert {(b.status, b.due_date, b.discount, b.fee) for b in result.bills} == {("paid", None, 0, 0)}
    first = result.bills[0]
    assert (first.term_name, first.description, first.fee_type) == (
        "Academic year 2026-2027 - Semester 1", "Thu Học Phí HK 1 (2026-2027)", "Thu Học Phí")


def test_a_bill_not_paid_yet_has_its_vietnam_due_date_and_no_payment():
    [bill] = parse_iupay(reply(record())).bills

    # date_line is 15/02/2027 00:00 in Vietnam, 14/02 in UTC.
    assert (bill.status, bill.due_date, bill.paid_on, bill.channel) == ("unpaid", date(2027, 2, 15), None, None)
    assert (bill.amount, bill.discount, bill.fee) == (40_000_000, 2_000_000, 0)


@pytest.mark.parametrize("number, status", [(0, "unpaid"), (1, "paid"), (2, "paying"), (3, "partly_paid")])
def test_each_iupay_status_number_has_its_name(number, status):
    [bill] = parse_iupay(reply(record(trang_thai=number, ngay_thu=1796092200000, kenh_thu="Liên ngân hàng"))).bills

    assert bill.status == status
    if status == "paid":
        assert (bill.paid_on, bill.channel, bill.due_date) == (date(2026, 12, 1), "Liên ngân hàng", None)
    else:
        assert (bill.paid_on, bill.channel, bill.due_date) == (None, None, date(2027, 2, 15))


def test_tags_in_a_description_become_plain_lines():
    [bill] = parse_iupay(reply(record(noi_dung="Học phí HK2<br>Bảo hiểm y tế<BR/> <b>2027</b> "))).bills

    assert bill.description == "Học phí HK2\nBảo hiểm y tế\n2027"


def test_a_bill_without_a_description_is_named_by_its_fee_type():
    [bill] = parse_iupay(reply(record(noi_dung=""))).bills

    assert bill.description == "Thu Học Phí"


def test_nothing_about_the_student_leaves_the_reader():
    uploaded = parse_iupay(reply(record())).model_dump_json()

    for personal in ("ITITIU00000", "Nguyễn Văn An", "student.hcmiu.edu.vn", "ITIT24IU01"):
        assert personal not in uploaded


def test_no_bills_is_an_empty_list():
    assert parse_iupay(reply()).bills == []


@pytest.mark.parametrize(
    "answer",
    [
        reply(record(trang_thai=5)),
        reply(record(phai_thu=-1)),
        reply({k: v for k, v in record().items() if k != "so_phieu_bao"}),
        reply(record(), record()),
        reply(record(date_line="15/02/2027")),
        reply("not a bill"),
        {"code": 200, "data": {"student": {}}},
        {"code": 200, "data": {"data": {"records": "none"}}},
        [],
    ],
    ids=["unknown-status", "negative-amount", "no-bill-number", "same-bill-twice", "date-as-text", "bill-not-an-object",
         "no-records", "records-not-a-list", "not-an-object"],
)
def test_an_answer_that_does_not_look_right_is_source_changed(answer):
    with pytest.raises(SourceChanged):
        parse_iupay(answer)


# ---- the client --------------------------------------------------------------------

import requests  # noqa: E402
import responses  # noqa: E402

from sla_agent.edusoft_client import USER_AGENT  # noqa: E402
from sla_agent.errors import BadCredentials, ExtraVerification, NetworkError  # noqa: E402
from sla_agent.iupay_client import SCHOOL_URL, IupayClient  # noqa: E402
from sla_agent.log import redact  # noqa: E402

CAPTCHA = f"{SCHOOL_URL}/captcha-public"
SEARCH = f"{SCHOOL_URL}/secret/ITITIU00000/0"
CODE = "$2b$05$Made.Up.Lookup.Code.For.Tests.Only@k218l5eyWabcdefgh"  # made up, shaped like IUPay's
BILLS = f"{SCHOOL_URL}/secretCode/{CODE}/bill?limit=99999&offset=0"


def answer_search(captcha=False, found=None):
    responses.get(CAPTCHA, json={"code": 200, "data": {"enabled": captcha}, "success": True})
    responses.get(SEARCH, json=found or {"code": 200, "data": {"secretCode": CODE}, "success": True})


@responses.activate
def test_the_client_makes_the_three_requests_the_iupay_page_makes():
    answer_search()
    responses.get(BILLS, json=real_reply())

    answer = IupayClient().read_bills("ITITIU00000")

    assert answer == real_reply()
    assert [c.request.method for c in responses.calls] == ["GET", "GET", "GET"]
    assert all(c.request.headers["User-Agent"] == USER_AGENT for c in responses.calls)


@responses.activate
def test_the_lookup_code_is_hidden_in_the_log():
    answer_search()
    responses.get(BILLS, json=real_reply())

    IupayClient().read_bills("ITITIU00000")

    assert redact(f"GET {BILLS}") == f"GET {SCHOOL_URL}/secretCode/***/bill?limit=99999&offset=0"


@responses.activate
def test_a_captcha_stops_before_the_student_id_is_sent():
    answer_search(captcha=True)

    with pytest.raises(ExtraVerification):
        IupayClient().read_bills("ITITIU00000")

    assert len(responses.calls) == 1


@responses.activate
def test_an_unknown_student_id_says_what_iupay_said():
    answer_search(found={"code": 200, "success": True, "data": {
        "success": False, "statusCode": 400, "message": "Thông tin sinh viên không chính xác"}})

    with pytest.raises(BadCredentials, match="Thông tin sinh viên không chính xác"):
        IupayClient().read_bills("ITITIU00000")


@responses.activate
def test_iupay_not_answering_is_a_network_problem():
    responses.get(CAPTCHA, body=requests.ConnectTimeout())

    with pytest.raises(NetworkError):
        IupayClient().read_bills("ITITIU00000")


@pytest.mark.parametrize(
    "search",
    [{"code": 200, "data": {}}, {"code": 200}, "<html>Bảo trì hệ thống</html>"],
    ids=["no-lookup-code", "no-data", "not-json"],
)
@responses.activate
def test_a_search_answer_that_does_not_look_right_is_source_changed(search):
    responses.get(CAPTCHA, json={"code": 200, "data": {"enabled": False}})
    if isinstance(search, str):
        responses.get(SEARCH, body=search)
    else:
        responses.get(SEARCH, json=search)

    with pytest.raises(SourceChanged):
        IupayClient().read_bills("ITITIU00000")
