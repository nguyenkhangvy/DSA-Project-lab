"""connect.py: what Connect listens with on 127.0.0.1 while the student is in the browser (spec
2026-10-04-connect-button-design.md, 3 and 5.1). requests stands in for the browser coming back from the website."""

import re
import threading
from urllib.parse import parse_qs, urlsplit

import pytest
import requests

from sla_agent import connect
from sla_agent.connect import Cancelled, Closed, Connection, TimedOut
from sla_agent.log import redact

# RFC 7636's example verifier and its challenge: the website's tests (ConnectCodesTest) pin the same pair.
VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
CODE = "c0de-0123456789_abcdefghijklmnopqrstuvwxyzA"
SHAPE = re.compile(r"[A-Za-z0-9_-]{43}")

BROWSER = requests.Session()
BROWSER.trust_env = False  # straight to 127.0.0.1, whatever proxy Windows has


@pytest.fixture
def connection():
    made = Connection()
    yield made
    made.close()


def come_back(connection, path="/callback", **query):
    """The browser, sent back to the app by the website."""
    return BROWSER.get(f"http://127.0.0.1:{connection.port}{path}", params=query, timeout=5)


def test_the_challenge_is_the_verifiers_sha256_in_url_safe_base64():
    assert connect.challenge_of(VERIFIER) == CHALLENGE


def test_each_connection_has_its_own_secrets_in_the_shape_the_website_checks(connection):
    other = Connection()
    try:
        assert (connection.state, connection.verifier) != (other.state, other.verifier)
        for secret in (connection.state, connection.verifier, connection.challenge):
            assert SHAPE.fullmatch(secret)
        assert connection.challenge == connect.challenge_of(connection.verifier)
    finally:
        other.close()


def test_it_listens_on_this_laptop_only(connection):
    assert connection.host == "127.0.0.1"
    assert 1024 <= connection.port <= 65535


def test_the_link_opens_the_connect_page_with_the_port_state_challenge_and_name(connection):
    parts = urlsplit(connection.url("https://sla.example.com/", "LAPTOP-AN"))

    assert (parts.scheme, parts.netloc, parts.path) == ("https", "sla.example.com", "/school/devices/connect")
    assert parse_qs(parts.query) == {"port": [str(connection.port)], "state": [connection.state],
                                     "challenge": [connection.challenge], "name": ["LAPTOP-AN"]}


def test_the_code_that_comes_back_with_the_right_state_is_the_answer(connection):
    page = come_back(connection, code=CODE, state=connection.state)

    assert page.status_code == 200
    assert connect.DONE in page.text
    assert page.headers["Cache-Control"] == "no-store"
    assert connection.wait(5) == CODE


def test_anything_else_is_not_found_and_the_wait_goes_on(connection):  # Review Focus 2
    assert come_back(connection, code=CODE, state="not-the-state").status_code == 404
    assert come_back(connection, code=CODE).status_code == 404
    assert come_back(connection, state=connection.state).status_code == 404  # no code
    assert come_back(connection, path="/favicon.ico").status_code == 404
    assert come_back(connection, path="/other", code=CODE, state=connection.state).status_code == 404

    come_back(connection, code=CODE, state=connection.state)
    assert connection.wait(5) == CODE


def test_cancel_on_the_website_raises_cancelled(connection):
    page = come_back(connection, error="cancelled", state=connection.state)

    assert connect.CANCELLED in page.text
    with pytest.raises(Cancelled):
        connection.wait(5)


def test_only_the_first_answer_counts(connection):
    come_back(connection, code=CODE, state=connection.state)
    come_back(connection, error="cancelled", state=connection.state)

    assert connection.wait(5) == CODE


def test_nothing_back_in_time_raises_timed_out(connection):
    with pytest.raises(TimedOut):
        connection.wait(0.2)


def test_closing_meanwhile_raises_closed(connection):  # Review Focus 1
    threading.Timer(0.2, connection.close).start()

    with pytest.raises(Closed):
        connection.wait(5)


def test_after_the_wait_nothing_listens_on_the_port(connection):
    come_back(connection, code=CODE, state=connection.state)
    connection.wait(5)

    with pytest.raises(requests.ConnectionError):
        come_back(connection, code=CODE, state=connection.state)


def test_close_can_be_called_again(connection):
    connection.close()
    connection.close()


def test_the_code_and_the_secrets_are_kept_out_of_the_log(connection):
    come_back(connection, code=CODE, state=connection.state)
    connection.wait(5)

    assert redact(f"{CODE} {connection.state} {connection.verifier}") == "*** *** ***"
