"""The Windows scheduled task that runs `sla-agent run` every minute and at logon.

It runs as the logged-in user (no admin rights, no stored Windows password),
which is also what gives it access to that user's Credential Manager.
"""

import getpass
import os
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

from sla_agent.launcher import arguments

TASK_NAME = r"\SchoolLifeAssistant\Sync"
NS = "http://schemas.microsoft.com/windows/2004/02/mit/task"
# schtasks is a console program: started from the window (pythonw, no console) it would flash a console window.
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


class SchedulerError(Exception):
    pass


def current_user():
    domain = os.environ.get("USERDOMAIN")
    user = os.environ.get("USERNAME") or getpass.getuser()
    return f"{domain}\\{user}" if domain else user


def _add(parent, tag, text=None, **attrs):
    element = ET.SubElement(parent, f"{{{NS}}}{tag}", attrs)
    if text is not None:
        element.text = text
    return element


def task_xml(program, user):
    ET.register_namespace("", NS)
    task = ET.Element(f"{{{NS}}}Task", version="1.2")

    info = _add(task, "RegistrationInfo")
    _add(info, "Description", "School-Life-Assistant: new mail every minute; EduSoft, IUPay, Blackboard and "
                              "Outlook every 30 minutes.")

    triggers = _add(task, "Triggers")
    every_minute = _add(triggers, "TimeTrigger")
    repetition = _add(every_minute, "Repetition")
    _add(repetition, "Interval", "PT1M")
    _add(every_minute, "StartBoundary", "2026-01-01T00:00:00")
    _add(every_minute, "Enabled", "true")
    at_logon = _add(triggers, "LogonTrigger")
    _add(at_logon, "Enabled", "true")
    _add(at_logon, "UserId", user)
    _add(at_logon, "Delay", "PT2M")

    principal = _add(_add(task, "Principals"), "Principal", id="Author")
    _add(principal, "UserId", user)
    _add(principal, "LogonType", "InteractiveToken")
    _add(principal, "RunLevel", "LeastPrivilege")

    settings = _add(task, "Settings")
    for tag, value in (
        ("MultipleInstancesPolicy", "IgnoreNew"),
        ("DisallowStartIfOnBatteries", "false"),
        ("StopIfGoingOnBatteries", "false"),
        ("StartWhenAvailable", "true"),
        ("RunOnlyIfNetworkAvailable", "true"),
        ("AllowStartOnDemand", "true"),
        ("Enabled", "true"),
        ("ExecutionTimeLimit", "PT10M"),
    ):
        _add(settings, tag, value)

    action = _add(_add(task, "Actions", Context="Author"), "Exec")
    _add(action, "Command", program)
    _add(action, "Arguments", arguments(program, "run"))

    return ET.tostring(task, encoding="unicode")


def install_task(program, user, folder, runner=subprocess.run):
    folder = Path(folder)
    folder.mkdir(parents=True, exist_ok=True)
    xml_path = folder / "sync-task.xml"
    xml_path.write_text('<?xml version="1.0" encoding="UTF-16"?>\n' + task_xml(program, user), encoding="utf-16")
    result = runner(
        ["schtasks", "/Create", "/F", "/TN", TASK_NAME, "/XML", str(xml_path)],
        capture_output=True, text=True, creationflags=NO_WINDOW,
    )
    if result.returncode != 0:
        raise SchedulerError(f"Couldn't create the scheduled task: {(result.stderr or result.stdout).strip()}")


def _task_scheduler():
    """Task Scheduler's own COM interface (through pywin32), connected."""
    import win32com.client

    service = win32com.client.Dispatch("Schedule.Service")
    service.Connect()
    return service


def task_program(service=None):
    """The program the scheduled task starts, or None when the task is missing or can't be read. Read through Task
    Scheduler's COM interface, not `schtasks /Query`: schtasks prints in the console's code page, which turns the
    letters of a Vietnamese user folder into '?' (and the program would seem to be gone)."""
    try:
        task = (_task_scheduler() if service is None else service).GetFolder("\\").GetTask(TASK_NAME)
        return task.Definition.Actions.Item(1).Path
    except Exception:  # not Windows (ImportError), no such task, or Task Scheduler refused (pywin32's com_error)
        return None


def remove_task(runner=subprocess.run):
    runner(["schtasks", "/Delete", "/F", "/TN", TASK_NAME], capture_output=True, text=True, creationflags=NO_WINDOW)
