"""Automatic updates of the built app (spec 2026-10-02-agent-exe-design.md, 6).

Once a day the scheduled `run` asks GitHub for the newest release. When it is newer than this app, its setup is
downloaded, its SHA-256 compared with the release's SHA256SUMS.txt, and it is started with `--update <this
process>`: the setup (installer.py) waits for this run to end and swaps the app folder. Only the built app updates
itself; from source, `git pull` does. Nothing here raises: a problem is logged and changes nothing, so the sync
still happens."""

import copy
import hashlib
import logging
import os
import subprocess
from datetime import datetime, timedelta

import requests

from sla_agent import __version__
from sla_agent.edusoft_client import USER_AGENT
from sla_agent.state import agent_home, save_state
from sla_agent.versions import is_newer, parse

log = logging.getLogger(__name__)

REPOSITORY = "nguyenkhangvy/School-Life-Assistant"
LATEST = f"https://api.github.com/repos/{REPOSITORY}/releases/latest"
DOWNLOADS = f"https://github.com/{REPOSITORY}/releases/download/"
SETUP = "School-Life-Assistant.exe"
SUMS = "SHA256SUMS.txt"
EVERY = timedelta(hours=24)
MAX_BYTES = 150 * 1024 * 1024
TIMEOUT = (10, 60)
# The setup must outlive this run: started on its own, and outside the scheduled task's job where Windows allows it.
DETACHED = getattr(subprocess, "DETACHED_PROCESS", 0) | getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
BREAKAWAY = getattr(subprocess, "CREATE_BREAKAWAY_FROM_JOB", 0)


class UpdateProblem(Exception):
    pass


def downloads():
    return agent_home() / "update"


def due(state, now):
    """Whether a day has passed since the last check (or the clock was moved back before it)."""
    if not state.update_checked_at:
        return True
    return not timedelta(0) <= now - datetime.fromisoformat(state.update_checked_at) < EVERY


def check_and_start(state, now, session=None, start=None):
    """Once a day: when GitHub has a newer release, download and check its setup and start it. True when the setup
    was started, and this run should end without syncing. The time of the check is saved first, so a failed check
    also waits a day."""
    if not due(state, now):
        return False
    before = copy.deepcopy(state)
    state.update_checked_at = now.isoformat()
    save_state(state, before)
    try:
        setup = _download_newer(session or requests.Session())
        if setup is None:
            return False
        (_start if start is None else start)(setup)
    except Exception as error:  # an update problem never stops the sync
        log.warning("Update: %s", error)
        return False
    log.info("Update: started %s", setup.name)
    return True


def _download_newer(session):
    """The checked setup of a release newer than this app, or None when this is the newest."""
    session.headers["User-Agent"] = USER_AGENT
    release = _get(session, LATEST, headers={"Accept": "application/vnd.github+json"}).json()
    tag = release.get("tag_name") or ""
    if not is_newer(tag, __version__):
        log.info("Update: this is the newest version (GitHub's latest is %s)", tag)
        return None
    links = {asset.get("name"): asset.get("browser_download_url") or "" for asset in release.get("assets") or []}
    for name in (SETUP, SUMS):
        if not links.get(name, "").startswith(DOWNLOADS):
            raise UpdateProblem(f"release {tag} has no {name} on {DOWNLOADS}")
    expected = _listed_sum(_get(session, links[SUMS]).text, SETUP)
    folder = downloads()
    folder.mkdir(parents=True, exist_ok=True)
    path = folder / f"School-Life-Assistant-{'.'.join(str(number) for number in parse(tag))}.exe"
    _download(session, links[SETUP], path, expected)
    return path


def _get(session, url, **options):
    answer = session.get(url, timeout=TIMEOUT, **options)
    answer.raise_for_status()
    if not answer.url.startswith("https://"):
        raise UpdateProblem(f"{url} was redirected away from https")
    return answer


def _listed_sum(text, name):
    """The SHA-256 SHA256SUMS.txt lists for `name` ("<hex>  <name>", as sha256sum writes it)."""
    for line in text.splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[1].lstrip("*") == name and len(parts[0]) == 64:
            return parts[0].lower()
    raise UpdateProblem(f"{SUMS} lists no SHA-256 for {name}")


def _download(session, url, path, expected):
    """Save `url` as `path` only if it is at most MAX_BYTES and its SHA-256 is `expected`."""
    partial = path.with_name(path.name + ".part")
    digest, size = hashlib.sha256(), 0
    try:
        with _get(session, url, stream=True) as answer, open(partial, "wb") as file:
            for chunk in answer.iter_content(chunk_size=1 << 20):
                size += len(chunk)
                if size > MAX_BYTES:
                    raise UpdateProblem(f"the setup is larger than {MAX_BYTES} bytes")
                digest.update(chunk)
                file.write(chunk)
        if digest.hexdigest() != expected:
            raise UpdateProblem(f"the setup's SHA-256 isn't the one in {SUMS}")
        partial.replace(path)
    finally:
        partial.unlink(missing_ok=True)


def _start(setup):
    """Start the setup on its own, working in the agent's folder (never inside app\\, which it renames)."""
    command = [str(setup), "--update", str(os.getpid())]
    try:
        subprocess.Popen(command, cwd=agent_home(), creationflags=DETACHED | BREAKAWAY, close_fds=True)
    except PermissionError:  # the scheduled task's job doesn't allow leaving it
        subprocess.Popen(command, cwd=agent_home(), creationflags=DETACHED, close_fds=True)


def note_new_version(state, now):
    """The built app's first run after it changed: remember its version, and when it updated itself (not on its
    first run ever). Returns whether `state` changed."""
    if state.agent_version == __version__:
        return False
    if state.agent_version is not None:
        state.updated_at = now.isoformat()
    state.agent_version = __version__
    return True


def clean_downloads():
    """Delete downloaded setups. One that is still installing is in use and stays until a later run."""
    folder = downloads()
    for path in folder.iterdir() if folder.exists() else ():
        try:
            path.unlink()
        except OSError:
            pass
