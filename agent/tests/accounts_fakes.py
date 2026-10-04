"""Fakes for everything accounts.py talks to (accounts.Tools), shared by test_accounts.py and test_window.py."""

from sla_contract.schema import ConnectResult

from agent.tests.fakes import FakeBlackboard, FakeEduSoft, FakeServer
from sla_agent import accounts, credentials
from sla_agent.outlook_reader import SIGNED_IN
from sla_agent.state import State, load_state, save_state

SERVER = "https://sla.example.com"
KEY = "sla_device-key-0123456789-abcdefghijklmnopqrstu"  # a real key's shape: sla_ and 43 characters
STUDENT = "ITITIU20001"
PASSWORD = "s3cret-pass"
BB_USER = "ititiu20001"
BB_PASSWORD = "bb-s3cret"
ME = "ititiu20001@student.hcmiu.edu.vn"
PYTHONW = r"C:\IU_SCHOOL\p\.venv\Scripts\pythonw.exe"
CODE = "c0de-0123456789_abcdefghijklmnopqrstuvwxyzA"  # a Connect code's shape: 43 characters
NEW_KEY = "sla_new-key-0123456789-abcdefghijklmnopqrstuvwx"  # the key Connect brings back


class FakeConnection:
    """connect.Connection without a listener: wait() gives `answer`, a code, or raises it when it is an exception
    instance (connect.Cancelled(), TimedOut(), Closed())."""

    verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"

    def __init__(self, answer=CODE):
        self.answer, self.closed, self.waits = answer, False, []

    def url(self, address, name):
        return f"{address}/school/devices/connect?name={name}"

    def wait(self, timeout):
        self.waits.append(timeout)
        if isinstance(self.answer, Exception):
            raise self.answer
        return self.answer

    def close(self):
        self.closed = True


class Fakes:
    """`found` is Outlook's account list (an exception instance is raised instead); `outlook` is what the registry
    says about Outlook (SIGNED_IN unless a test changes it; an exception instance is raised instead); `program` is
    what the scheduled task starts; `fail` maps a part ("task", "mail link", "window link", "shortcuts",
    "desktop icon", "start outlook") to the error it raises; `done` lists the parts that ran; `looks` lists each
    time Outlook itself was asked ("start"); `me` is the program this agent is: what turn_on_sync points things
    at."""

    def __init__(self):
        self.me = PYTHONW
        self.opened = []  # the addresses opened as an app
        self.server = FakeServer()
        self.edusoft = FakeEduSoft()
        self.blackboard = FakeBlackboard()
        self.found = [ME]
        self.outlook = SIGNED_IN
        self.program = None
        self.fail = {}
        self.done = []
        self.servers = []
        self.looks = []
        self.answer = CODE  # what the next Connect's browser brings back (a code, or an exception instance)
        self.connections = []  # every FakeConnection made, oldest first
        self.listen_error = None  # an OSError: Connect can't listen
        self.browsed = []  # the pages Connect opened in the browser
        self.trade = ConnectResult(key=NEW_KEY, email=ME)  # the trade-in's answer, or an exception instance
        self.trades = []  # (address, code, verifier) of each trade-in

    def tools(self):
        return accounts.Tools(
            make_server=self._make_server, make_edusoft=lambda: self.edusoft,
            make_blackboard=lambda: self.blackboard, find_outlook_accounts=self._find,
            outlook_state=self._outlook_state,
            start_outlook=self._part("start outlook"), program=lambda: self.me,
            install_task=self._part("task"), task_program=lambda: self.program,
            register_mail_link=self._part("mail link"), register_window_link=self._part("window link"),
            make_shortcuts=self._part("shortcuts"), add_desktop_shortcut=self._part("desktop icon"),
            has_desktop_shortcut=lambda: "desktop icon" in self.done,
            has_window_link=lambda: "window link" in self.done,
            open_site=self.opened.append, listen=self._listen, open_browser=self.browsed.append,
            connect=self._trade)

    def _make_server(self, address, key):
        self.servers.append((address, key))
        return self.server

    def _listen(self):
        if self.listen_error:
            raise self.listen_error
        self.connections.append(FakeConnection(self.answer))
        return self.connections[-1]

    def _trade(self, address, code, verifier):
        self.trades.append((address, code, verifier))
        if isinstance(self.trade, Exception):
            raise self.trade
        return self.trade

    def _find(self):
        self.looks.append("start")
        return self._accounts()

    def _outlook_state(self):
        if isinstance(self.outlook, Exception):
            raise self.outlook
        return self.outlook

    def _accounts(self):
        if isinstance(self.found, Exception):
            raise self.found
        return self.found

    def _part(self, name):
        def run(*args, **kwargs):
            if name in self.fail:
                raise self.fail[name]
            self.done.append(name)

        return run


def set_up():
    """A laptop already set up with the website and EduSoft only."""
    credentials.save_edusoft(STUDENT, PASSWORD)
    credentials.save_device_key(SERVER, KEY)
    save_state(State(server_url=SERVER, student_id=STUDENT))
    return load_state()
