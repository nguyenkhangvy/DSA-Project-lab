from urllib.parse import parse_qs

import pytest
import requests
import responses

from agent.tests.fakes import (
    CAPTCHA_LOGIN_PAGE,
    LOGGED_IN_HOME,
    LOGIN_FAILED,
    LOGIN_PAGE,
    LOGIN_URL,
    TIMETABLE_PAGE,
)
from sla_agent.edusoft_client import USER_AGENT, EduSoftClient
from sla_agent.errors import BadCredentials, ExtraVerification, NetworkError, SessionExpired, UnexpectedRedirect

TIMETABLE_URL = "https://edusoftweb.hcmiu.edu.vn/default.aspx?page=thoikhoabieu"


@pytest.fixture
def sleeps():
    return []


@pytest.fixture
def client(sleeps):
    return EduSoftClient(sleep=sleeps.append)


def posts():
    return [call for call in responses.calls if call.request.method == "POST"]


def posted_form(call):
    return {key: values[0] for key, values in parse_qs(call.request.body, keep_blank_values=True).items()}


@responses.activate
def test_login_sends_the_form_with_its_hidden_fields_and_an_honest_user_agent(client):
    responses.get(LOGIN_URL, body=LOGIN_PAGE)
    responses.post(LOGIN_URL, body=LOGGED_IN_HOME)

    client.login("ITITIU20001", "s3cret-pass")

    [call] = posts()
    assert posted_form(call) == {
        "__EVENTTARGET": "",
        "__EVENTARGUMENT": "",
        "__VIEWSTATE": "VIEWSTATE-abc123",
        "__VIEWSTATEGENERATOR": "CA0B0334",
        "ctl00$ContentPlaceHolder1$ctl00$txtTaiKhoa": "ITITIU20001",
        "ctl00$ContentPlaceHolder1$ctl00$txtMatKhau": "s3cret-pass",
        "ctl00$ContentPlaceHolder1$ctl00$btnDangNhap": "Đăng Nhập",
    }
    assert all(c.request.headers["User-Agent"] == USER_AGENT for c in responses.calls)
    assert "SchoolLifeAssistant" in USER_AGENT


@responses.activate
def test_a_wrong_password_raises_bad_credentials_after_exactly_one_attempt(client):
    responses.get(LOGIN_URL, body=LOGIN_PAGE)
    responses.post(LOGIN_URL, body=LOGIN_FAILED)

    with pytest.raises(BadCredentials):
        client.login("ITITIU20001", "wrong")

    assert len(posts()) == 1


@responses.activate
def test_a_captcha_on_the_login_page_stops_before_sending_the_password(client):
    responses.get(LOGIN_URL, body=CAPTCHA_LOGIN_PAGE)

    with pytest.raises(ExtraVerification):
        client.login("ITITIU20001", "s3cret-pass")

    assert posts() == []


@responses.activate
def test_a_captcha_after_submitting_raises_extra_verification(client):
    responses.get(LOGIN_URL, body=LOGIN_PAGE)
    responses.post(LOGIN_URL, body=CAPTCHA_LOGIN_PAGE)

    with pytest.raises(ExtraVerification):
        client.login("ITITIU20001", "s3cret-pass")


@responses.activate
def test_a_redirect_to_microsoft_sign_in_raises_extra_verification(client):
    responses.get(LOGIN_URL, body=LOGIN_PAGE)
    responses.post(LOGIN_URL, status=302,
                   headers={"Location": "https://login.microsoftonline.com/common/oauth2/authorize?x=1"})

    with pytest.raises(ExtraVerification):
        client.login("ITITIU20001", "s3cret-pass")

    assert all("microsoftonline" not in c.request.url for c in responses.calls)


@responses.activate
def test_a_redirect_to_another_site_is_refused(client):
    responses.get(LOGIN_URL, status=302, headers={"Location": "https://evil.example/steal"})

    with pytest.raises(UnexpectedRedirect):
        client.login("ITITIU20001", "s3cret-pass")

    assert [c.request.url for c in responses.calls] == [LOGIN_URL]


@responses.activate
def test_a_plain_http_redirect_on_edusoft_is_followed_over_https(client):
    responses.get(LOGIN_URL, body=LOGIN_PAGE)
    responses.post(LOGIN_URL, status=302,
                   headers={"Location": "http://edusoftweb.hcmiu.edu.vn/default.aspx?page=gioithieu"})
    responses.get("https://edusoftweb.hcmiu.edu.vn/default.aspx?page=gioithieu", body=LOGGED_IN_HOME)

    client.login("ITITIU20001", "s3cret-pass")

    assert all(c.request.url.startswith("https://") for c in responses.calls)


@responses.activate
def test_get_page_returns_the_html_when_logged_in(client):
    responses.get(TIMETABLE_URL, body=TIMETABLE_PAGE)

    assert "IT093IU" in client.get_page("timetable")


@responses.activate
def test_get_page_raises_session_expired_when_edusoft_shows_the_login_form(client):
    responses.get(TIMETABLE_URL, body=LOGIN_PAGE)

    with pytest.raises(SessionExpired):
        client.get_page("timetable")


@responses.activate
def test_network_errors_are_retried_twice_with_waits(client, sleeps):
    responses.get(TIMETABLE_URL, body=requests.ConnectionError("down"))
    responses.get(TIMETABLE_URL, status=503)
    responses.get(TIMETABLE_URL, body=TIMETABLE_PAGE)

    assert "IT093IU" in client.get_page("timetable")
    assert sleeps == [5, 30]


@responses.activate
def test_network_errors_give_up_after_three_tries(client, sleeps):
    for _ in range(3):
        responses.get(TIMETABLE_URL, body=requests.Timeout("slow"))

    with pytest.raises(NetworkError):
        client.get_page("timetable")

    assert len(responses.calls) == 3


# ---- Reading whole sections (several pages each) ---------------------------------

from pathlib import Path  # noqa: E402

FIXTURES = Path(__file__).parent / "fixtures"
FINAL_URL = "https://edusoftweb.hcmiu.edu.vn/default.aspx?page=xemlichthi"
MIDTERM_URL = "https://edusoftweb.hcmiu.edu.vn/default.aspx?page=xemlichthigk"


def fixture(name):
    return (FIXTURES / name).read_text(encoding="utf-8")


@responses.activate
def test_reading_the_timetable_asks_edusoft_for_the_semester_view(client):
    responses.get(TIMETABLE_URL, body=fixture("timetable-weekly.html"))
    responses.post(TIMETABLE_URL, body=fixture("timetable-semester.html"))

    pages = client.read("timetable")

    [call] = posts()
    form = posted_form(call)
    assert form["ctl00$ContentPlaceHolder1$ctl00$ddlLoai"] == "1"
    assert form["__EVENTTARGET"] == "ctl00$ContentPlaceHolder1$ctl00$ddlLoai"
    assert form["ctl00$ContentPlaceHolder1$ctl00$ddlChonNHHK"] == "20261"
    assert form["__VIEWSTATE"] == "VIEWSTATE-REMOVED"
    assert set(pages) == {"weekly", "semester"}
    assert "grid-roll2" in pages["semester"]


@responses.activate
def test_reading_exams_uses_the_current_semester_and_both_exam_pages(client):
    responses.get(TIMETABLE_URL, body=fixture("timetable-weekly.html"))
    responses.get(FINAL_URL, body=fixture("exams-final-none.html"))
    responses.get(MIDTERM_URL, body=fixture("exams-midterm.html"))

    pages = client.read("exams")

    assert pages["term"] == "20261"
    assert "dropNHHK" in pages["final"]
    assert "lblTitle" in pages["midterm"]
    assert posts() == []  # 20261 isn't in the final exam list yet, so nothing to switch


@responses.activate
def test_reading_exams_switches_the_final_exam_page_to_the_current_semester(client):
    client.current_term = "20251"
    responses.get(FINAL_URL, body=fixture("exams-final.html"))  # shows 20252, lists 20251
    responses.post(FINAL_URL, body=fixture("exams-final.html"))
    responses.get(MIDTERM_URL, body=fixture("exams-midterm.html"))

    client.read("exams")

    [call] = posts()
    assert posted_form(call)["ctl00$ContentPlaceHolder1$ctl00$dropNHHK"] == "20251"


@responses.activate
def test_a_form_answer_showing_the_login_form_means_the_session_expired(client):
    responses.get(TIMETABLE_URL, body=fixture("timetable-weekly.html"))
    responses.post(TIMETABLE_URL, body=LOGIN_PAGE)

    with pytest.raises(SessionExpired):
        client.read("timetable")
