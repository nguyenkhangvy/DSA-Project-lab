"""Reads the student's bills from IUPay (https://iupay.hcmiu.edu.vn/search/dhqt). Reads only; never pays.

IUPay's page gets its data from AQTech's EduBill API at api.mybill.aqtech.vn; its search needs only the
student ID. The agent makes the same three requests the page makes (checked 2026-09-30):
1. .../captcha-public: whether IUPay asks for a captcha. It is never solved: the IUPay part fails instead.
2. .../secret/{student ID}/0: a lookup code for this student (the 0 is an ID-card number IU doesn't use).
3. .../secretCode/{code}/bill: every bill, paid or not.
The lookup code is treated like a password: hidden in the log, never saved.
"""

import logging
from urllib.parse import quote

import requests

from sla_agent.edusoft_client import USER_AGENT
from sla_agent.errors import BadCredentials, ExtraVerification, SourceChanged
from sla_agent.guarded_http import guarded_request
from sla_agent.log import protect

log = logging.getLogger(__name__)

HOST = "api.mybill.aqtech.vn"
SCHOOL_URL = f"https://{HOST}/api/organization/dhqt/school/dh"  # IU, undergraduate
SITE = "IUPay"


def _data(answer):
    return answer.get("data") if isinstance(answer, dict) else None


class IupayClient:
    def __init__(self, session=None):
        self.session = session or requests.Session()
        self.session.headers["User-Agent"] = USER_AGENT
        self.session.headers["Accept"] = "application/json"

    def _json(self, url):
        response = guarded_request(self.session, "GET", url, HOST, site=SITE)
        try:
            return response.json()
        except ValueError:
            raise SourceChanged("IUPay's answer isn't JSON.") from None

    def read_bills(self, student_id):
        """IUPay's decoded answer to the bill request for this student."""
        captcha = _data(self._json(f"{SCHOOL_URL}/captcha-public"))
        if isinstance(captcha, dict) and captcha.get("enabled"):
            raise ExtraVerification("IUPay now asks for a captcha; tuition can't sync.")

        found = _data(self._json(f"{SCHOOL_URL}/secret/{quote(student_id, safe='')}/0"))
        if not isinstance(found, dict):
            raise SourceChanged("IUPay's answer to the student search isn't in the expected format.")
        if found.get("success") is False:
            reason = str(found.get("message") or "no reason given")[:200]
            raise BadCredentials(f"IUPay didn't recognise the student ID: {reason}")
        code = found.get("secretCode")
        if not isinstance(code, str) or not code:
            raise SourceChanged("IUPay's answer to the student search has no lookup code.")
        protect(code)

        answer = self._json(f"{SCHOOL_URL}/secretCode/{code}/bill?limit=99999&offset=0")
        log.info("IUPay answered the bill request")
        return answer
