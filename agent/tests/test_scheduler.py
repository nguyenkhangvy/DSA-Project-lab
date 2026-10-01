import subprocess
import xml.etree.ElementTree as ET

import pytest

from sla_agent.scheduler import TASK_NAME, SchedulerError, install_task, remove_task, task_program, task_xml

NS = {"t": "http://schemas.microsoft.com/windows/2004/02/mit/task"}
PYTHONW = r"C:\IU SCHOOL\School-Life-Assistant\.venv\Scripts\pythonw.exe"
USER = r"LAPTOP-AN\an & co"


@pytest.fixture
def task():
    return ET.fromstring(task_xml(PYTHONW, USER))


def text(task, path):
    return task.find(path, NS).text


def test_runs_every_minute_and_after_logon(task):
    assert text(task, "t:Triggers/t:TimeTrigger/t:Repetition/t:Interval") == "PT1M"
    assert text(task, "t:Triggers/t:LogonTrigger/t:UserId") == USER


def test_runs_the_agent_without_a_console_window(task):
    assert text(task, "t:Actions/t:Exec/t:Command") == PYTHONW
    assert text(task, "t:Actions/t:Exec/t:Arguments") == "-m sla_agent run"


def test_runs_as_the_logged_in_user_without_admin_rights_or_a_stored_windows_password(task):
    assert text(task, "t:Principals/t:Principal/t:UserId") == USER
    assert text(task, "t:Principals/t:Principal/t:LogonType") == "InteractiveToken"
    assert text(task, "t:Principals/t:Principal/t:RunLevel") == "LeastPrivilege"


def test_catches_up_never_overlaps_works_on_battery_and_has_a_time_limit(task):
    assert text(task, "t:Settings/t:StartWhenAvailable") == "true"
    assert text(task, "t:Settings/t:MultipleInstancesPolicy") == "IgnoreNew"
    assert text(task, "t:Settings/t:DisallowStartIfOnBatteries") == "false"
    assert text(task, "t:Settings/t:StopIfGoingOnBatteries") == "false"
    assert text(task, "t:Settings/t:ExecutionTimeLimit") == "PT10M"


class FakeRunner:
    def __init__(self, returncode=0, stderr=""):
        self.calls = []
        self.returncode = returncode
        self.stderr = stderr

    def __call__(self, args, **kwargs):
        xml_path = args[args.index("/XML") + 1] if "/XML" in args else None
        xml = open(xml_path, encoding="utf-16").read() if xml_path else None
        self.calls.append((args, xml))

        class Result:
            pass

        result = Result()
        result.returncode, result.stdout, result.stderr = self.returncode, "", self.stderr
        return result


def test_install_creates_the_task_from_the_xml(tmp_path):
    runner = FakeRunner()

    install_task(PYTHONW, USER, folder=tmp_path, runner=runner)

    [(args, xml)] = runner.calls
    assert args[:2] == ["schtasks", "/Create"]
    assert args[args.index("/TN") + 1] == TASK_NAME
    assert "/F" in args
    assert "PT1M" in xml


def test_install_reports_a_schtasks_failure(tmp_path):
    with pytest.raises(SchedulerError, match="Access is denied"):
        install_task(PYTHONW, USER, folder=tmp_path, runner=FakeRunner(returncode=1, stderr="ERROR: Access is denied."))


def test_remove_deletes_the_task():
    runner = FakeRunner()

    remove_task(runner=runner)

    [(args, _)] = runner.calls
    assert args == ["schtasks", "/Delete", "/F", "/TN", TASK_NAME]


class Answer:
    def __init__(self, returncode, stdout):
        self.returncode, self.stdout, self.stderr = returncode, stdout, ""


def test_the_tasks_program_is_read_from_windows():
    xml = '<?xml version="1.0" encoding="UTF-16"?><Task><Actions><Exec><Command>' + PYTHONW + '</Command></Exec>'
    xml += "</Actions></Task>"

    assert task_program(runner=lambda *a, **k: Answer(0, xml)) == PYTHONW


def test_no_program_when_the_task_is_missing():
    assert task_program(runner=lambda *a, **k: Answer(1, "ERROR: The system cannot find the file specified.")) is None


def test_schtasks_never_opens_a_console_window(tmp_path):
    """The window runs under pythonw: without CREATE_NO_WINDOW each schtasks call would flash a console."""
    flags = []

    def runner(args, **kwargs):
        flags.append(kwargs.get("creationflags"))
        return Answer(0, "")

    install_task(PYTHONW, USER, folder=tmp_path, runner=runner)
    task_program(runner=runner)
    remove_task(runner=runner)

    assert flags == [getattr(subprocess, "CREATE_NO_WINDOW", 0)] * 3
