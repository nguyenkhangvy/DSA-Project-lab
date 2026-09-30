"""Talks to EduSoft Web: log in, read pages. Reads only; never changes anything there.

Safety rules:
- only https://edusoftweb.hcmiu.edu.vn; redirects elsewhere are refused
- an honest user agent that names this app
- a CAPTCHA, one-time code or Microsoft sign-in stops the login (never bypassed)
- the login form is submitted once; a rejected password is never retried
"""

import logging
import time
from urllib.parse import urljoin

import requests
from bs4 import BeautifulSoup

from sla_agent.errors import (
    BadCredentials,
    ExtraVerification,
    NetworkError,
    ParseError,
    SessionExpired,
)
from sla_agent.guarded_http import asks_for_verification, guarded_request

log = logging.getLogger(__name__)

HOST = "edusoftweb.hcmiu.edu.vn"
BASE_URL = f"https://{HOST}/"
LOGIN_URL = f"{BASE_URL}default.aspx?page=dangnhap"
USER_AGENT = "SchoolLifeAssistant/0.1 (IU student project)"
RETRY_WAITS = (5, 30)  # seconds to wait before the 2nd and 3rd try

USERNAME_FIELD = "ctl00$ContentPlaceHolder1$ctl00$txtTaiKhoa"
PASSWORD_FIELD = "ctl00$ContentPlaceHolder1$ctl00$txtMatKhau"
SUBMIT_FIELD = "ctl00$ContentPlaceHolder1$ctl00$btnDangNhap"

# EduSoft page names (default.aspx?page=...), confirmed with real pages on 2026-09-25.
PAGES = {
    "home": "gioithieu",
    "timetable": "thoikhoabieu",
    "exams": "xemlichthi",  # final exams
    "midterm_exams": "xemlichthigk",
    "registration": "dkmonhoc",  # read only, never submitted
}

# Form fields used to switch views (reading only; nothing is changed on EduSoft).
TIMETABLE_TERM_FIELD = "ctl00$ContentPlaceHolder1$ctl00$ddlChonNHHK"
TIMETABLE_VIEW_FIELD = "ctl00$ContentPlaceHolder1$ctl00$ddlLoai"
SEMESTER_VIEW = "1"  # "TKB học kỳ cá nhân": every course of the semester, not just this week
FINAL_EXAM_TERM_FIELD = "ctl00$ContentPlaceHolder1$ctl00$dropNHHK"


def _has_login_form(soup):
    return soup.find("input", attrs={"name": USERNAME_FIELD}) is not None


def _selected(html, field):
    """(selected value, all values) of a dropdown in the page."""
    select = BeautifulSoup(html, "html.parser").find("select", attrs={"name": field})
    if select is None:
        return None, []
    options = select.find_all("option")
    chosen = select.find("option", selected=True) or (options[0] if options else None)
    return (chosen.get("value") if chosen else None), [o.get("value") for o in options]


def _form_fields(html):
    """What a browser would send back: hidden fields and each dropdown's current choice."""
    form = BeautifulSoup(html, "html.parser").find("form")
    if form is None:
        raise ParseError("EduSoft's page has no form to switch views with.")
    fields = {tag["name"]: tag.get("value", "") for tag in form.find_all("input", attrs={"type": "hidden"})
              if tag.get("name")}
    for select in form.find_all("select"):
        if select.get("name"):
            chosen = select.find("option", selected=True) or select.find("option")
            fields[select["name"]] = chosen.get("value", "") if chosen else ""
    return fields


class EduSoftClient:
    def __init__(self, session=None, sleep=time.sleep):
        self.session = session or requests.Session()
        self.session.headers["User-Agent"] = USER_AGENT
        self.sleep = sleep
        self.current_term = None  # e.g. "20261", learned from the timetable page
        self.student_id = None  # set after a successful login

    # ---- HTTP with safety checks ------------------------------------------

    def _request(self, method, url, data=None):
        """One request, following redirects by hand so every hop is checked."""
        return guarded_request(self.session, method, url, HOST, data=data, site="EduSoft")

    def _get_with_retries(self, url):
        for wait in (*RETRY_WAITS, None):
            try:
                return self._request("GET", url)
            except NetworkError:
                if wait is None:
                    raise
                log.info("EduSoft not reachable, trying again in %s s", wait)
                self.sleep(wait)

    # ---- Public API --------------------------------------------------------

    def login(self, student_id, password):
        login_page = BeautifulSoup(self._get_with_retries(LOGIN_URL).text, "html.parser")
        if asks_for_verification(login_page):
            raise ExtraVerification("EduSoft's login page asks for extra verification.")
        form = login_page.find("form")
        if form is None or not _has_login_form(login_page):
            raise NetworkError("EduSoft's login page didn't load correctly.")

        # Send every hidden field, empty ones too, exactly like a browser does.
        fields = {
            tag["name"]: tag.get("value", "")
            for tag in form.find_all("input", attrs={"type": "hidden"})
            if tag.get("name")
        }
        submit = login_page.find("input", attrs={"name": SUBMIT_FIELD})
        fields.update({
            USERNAME_FIELD: student_id,
            PASSWORD_FIELD: password,
            SUBMIT_FIELD: submit.get("value", "") if submit else "",
        })
        action = urljoin(LOGIN_URL, form.get("action") or LOGIN_URL)

        # Submitted once. No retry here: a retry could count as a second failed login.
        result = BeautifulSoup(self._request("POST", action, data=fields).text, "html.parser")
        if asks_for_verification(result):
            raise ExtraVerification("EduSoft asked for extra verification after login.")
        if _has_login_form(result):
            raise BadCredentials("EduSoft rejected the student ID or password.")
        self.student_id = student_id
        log.info("Logged in to EduSoft")

    def _page_url(self, name):
        return f"{BASE_URL}default.aspx?page={PAGES[name]}"

    @staticmethod
    def _logged_in(html):
        if _has_login_form(BeautifulSoup(html, "html.parser")):
            raise SessionExpired("EduSoft showed the login form instead of the page.")
        return html

    def get_page(self, name):
        return self._logged_in(self._get_with_retries(self._page_url(name)).text)

    def switch_view(self, name, html, field, value):
        """Send the page's form back with one dropdown changed, as choosing it in a browser does."""
        fields = _form_fields(html)
        fields.update({field: value, "__EVENTTARGET": field, "__EVENTARGUMENT": ""})
        return self._logged_in(self._request("POST", self._page_url(name), data=fields).text)

    def read(self, section):
        """All pages one section needs, for its reader in sla_agent.parsers."""
        if section == "timetable":
            weekly = self.get_page("timetable")
            self.current_term = _selected(weekly, TIMETABLE_TERM_FIELD)[0]
            semester = self.switch_view("timetable", weekly, TIMETABLE_VIEW_FIELD, SEMESTER_VIEW)
            return {"weekly": weekly, "semester": semester}
        if section == "exams":
            self._learn_current_term()
            final = self.get_page("exams")
            shown, listed = _selected(final, FINAL_EXAM_TERM_FIELD)
            if shown != self.current_term and self.current_term in listed:
                final = self.switch_view("exams", final, FINAL_EXAM_TERM_FIELD, self.current_term)
            return {"term": self.current_term, "final": final, "midterm": self.get_page("midterm_exams")}
        if section == "registration":
            return {"registration": self.get_page("registration")}  # GET only: never submit this form
        raise KeyError(section)

    def _learn_current_term(self):
        if self.current_term is None:
            self.current_term = _selected(self.get_page("timetable"), TIMETABLE_TERM_FIELD)[0]
        return self.current_term
