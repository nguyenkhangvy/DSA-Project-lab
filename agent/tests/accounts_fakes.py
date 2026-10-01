"""Fakes for everything accounts.py talks to (accounts.Tools), shared by test_accounts.py and test_window.py."""

from agent.tests.fakes import FakeBlackboard, FakeEduSoft, FakeServer
from sla_agent import accounts, credentials
from sla_agent.state import State, load_state, save_state

SERVER = "https://sla.example.com"
KEY = "sla_device-key-0123456789"
STUDENT = "ITITIU20001"
PASSWORD = "s3cret-pass"
BB_USER = "ititiu20001"
BB_PASSWORD = "bb-s3cret"
ME = "ititiu20001@student.hcmiu.edu.vn"
PYTHONW = r"C:\IU_SCHOOL\p\.venv\Scripts\pythonw.exe"


class Fakes:
    """`found` is Outlook's account list (an exception instance is raised instead); `program` is what the scheduled
    task starts; `fail` maps a part ("task", "mail link", "window link", "shortcuts") to the error it raises;
    `done` lists the parts that ran; `looks` lists how Outlook was asked: "open" (only an open Outlook) or "start"."""

    def __init__(self):
        self.server = FakeServer()
        self.edusoft = FakeEduSoft()
        self.blackboard = FakeBlackboard()
        self.found = [ME]
        self.program = None
        self.fail = {}
        self.done = []
        self.servers = []
        self.looks = []

    def tools(self):
        return accounts.Tools(
            make_server=self._make_server, make_edusoft=lambda: self.edusoft,
            make_blackboard=lambda: self.blackboard, find_outlook_accounts=self._find, find_open_outlook_accounts=self._find_open, python=lambda: PYTHONW,
            install_task=self._part("task"), task_program=lambda: self.program,
            register_mail_link=self._part("mail link"), register_window_link=self._part("window link"),
            make_shortcuts=self._part("shortcuts"), has_window_link=lambda: "window link" in self.done)

    def _make_server(self, address, key):
        self.servers.append((address, key))
        return self.server

    def _find(self):
        self.looks.append("start")
        return self._accounts()

    def _find_open(self):
        self.looks.append("open")
        return self._accounts()

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
