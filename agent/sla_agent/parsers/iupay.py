"""IUPay reader: the student's bills, as IUPay's search page (https://iupay.hcmiu.edu.vn/search/dhqt) lists them.

IUPay answers {"data": {"data": {"records": [...]}, "student": {...}}}. Only the bill fields of the upload format
are kept; the student block (name, email, class) and every other field are dropped here, in memory.
Times are epoch milliseconds; the dates kept are Vietnam dates (UTC+7).
"""

import re
import unicodedata
from datetime import datetime, timedelta, timezone

from pydantic import ValidationError
from sla_contract.schema import Iupay, TuitionBill

from sla_agent.errors import SourceChanged

VN = timezone(timedelta(hours=7))
# trang_thai, as IUPay's own page labels it: Chưa đóng, Đã đóng, Đang thanh toán, Đóng một phần.
STATUSES = {0: "unpaid", 1: "paid", 2: "paying", 3: "partly_paid"}


def _records(reply):
    records = None
    if isinstance(reply, dict) and isinstance(reply.get("data"), dict) and isinstance(reply["data"].get("data"), dict):
        records = reply["data"]["data"].get("records")
    if not isinstance(records, list):
        raise SourceChanged("IUPay's bill list isn't in the expected format.")
    return records


def _day(millis):
    """The Vietnam date of an IUPay time; None for 0 or a missing time."""
    if millis in (None, 0):
        return None
    if isinstance(millis, bool) or not isinstance(millis, int):
        raise SourceChanged("An IUPay date isn't in the expected format.")
    return datetime.fromtimestamp(millis / 1000, VN).date()


def _text(html):
    """IUPay's descriptions are HTML: <br> becomes a line break, other tags are removed, blank lines dropped.
    Letters are composed (NFC): IUPay sends some, like the "ó" of "Đóng", as a letter plus a separate accent."""
    text = re.sub(r"<br\s*/?>", "\n", unicodedata.normalize("NFC", html or ""), flags=re.IGNORECASE)
    text = re.sub(r"<[^>]+>", "", text)
    return "\n".join(line.strip() for line in text.splitlines() if line.strip())


def _bill(record):
    number = record["trang_thai"]
    status = STATUSES.get(number) if isinstance(number, int) and not isinstance(number, bool) else None
    if status is None:
        raise SourceChanged(f"IUPay shows a bill status it didn't use before ({number!r}).")
    paid = status == "paid"
    fee_type = _text((record.get("ma_loai_thu") or {}).get("typeFeeName")) or None
    bill_no = str(record["so_phieu_bao"])
    return TuitionBill(
        bill_no=bill_no,
        term_code=str(record["hoc_ky"]),
        term_name=_text(record.get("hoc_ky_chu")) or None,
        description=_text(record.get("noi_dung")) or _text(record.get("chi_tiet")) or fee_type or bill_no,
        fee_type=fee_type,
        amount=record["phai_thu"],
        discount=record.get("mien_giam") or 0,
        fee=record.get("so_tien_phu_thu_tien_ich") or 0,
        status=status,
        due_date=None if paid else _day(record.get("date_line")),
        paid_on=_day(record.get("ngay_thu")) if paid else None,
        channel=(_text(record.get("kenh_thu")) or None) if paid else None,
    )


def parse_iupay(reply):
    """reply: the decoded JSON of IUPay's .../secretCode/{code}/bill request."""
    bills = []
    for record in _records(reply):
        try:
            bills.append(_bill(record))
        except (KeyError, TypeError, AttributeError):
            raise SourceChanged("An IUPay bill is missing a field it used to have.") from None
        except ValidationError as error:
            raise SourceChanged(f"Unexpected IUPay bill: {error.errors()[0]['msg']}") from None
    try:
        return Iupay(bills=bills)
    except ValidationError as error:
        raise SourceChanged(f"Unexpected IUPay bill list: {error.errors()[0]['msg']}") from None
