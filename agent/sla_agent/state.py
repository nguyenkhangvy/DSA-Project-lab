"""Local, non-secret agent state in %LOCALAPPDATA%\\SchoolLifeAssistant\\state.json.

Secrets (EduSoft password, device key) are never here: they live in
Windows Credential Manager (see credentials.py).
"""

import json
import os
from dataclasses import asdict, dataclass, fields
from pathlib import Path


def agent_home():
    override = os.environ.get("SLA_AGENT_HOME")
    if override:
        return Path(override)
    base = os.environ.get("LOCALAPPDATA") or Path.home() / ".local" / "share"
    return Path(base) / "SchoolLifeAssistant"


@dataclass
class State:
    server_url: str | None = None
    student_id: str | None = None
    paused: str | None = None  # error code that paused automatic sync, e.g. "bad_credentials"
    last_attempt_at: str | None = None  # ISO time (UTC) of the last sync attempt
    last_result: dict | None = None  # {"at", "status", "message"} of the last sync
    blackboard_username: str | None = None
    blackboard_paused: str | None = None  # error code that paused Blackboard sync
    registered_courses: list | None = None  # [[course code, group], ...] from EduSoft's registration page
    outlook_account: str | None = None  # the classic Outlook account whose Inbox is read
    term_code: str | None = None  # EduSoft's semester, from the last timetable read, e.g. "20261"
    courses: list | None = None  # [[course code, course name, lecturer], ...] from the last timetable read
    bb_courses: list | None = None  # [[Blackboard course name, course code], ...] from the last Blackboard read


def load_state():
    path = agent_home() / "state.json"
    if not path.exists():
        return State()
    data = json.loads(path.read_text(encoding="utf-8"))
    known = {f.name for f in fields(State)}
    return State(**{key: value for key, value in data.items() if key in known})


def save_state(state):
    home = agent_home()
    home.mkdir(parents=True, exist_ok=True)
    temporary = home / "state.json.tmp"
    temporary.write_text(json.dumps(asdict(state), indent=2, ensure_ascii=False), encoding="utf-8")
    temporary.replace(home / "state.json")
