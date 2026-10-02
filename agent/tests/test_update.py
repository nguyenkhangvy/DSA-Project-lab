"""Automatic updates (update.py) against a fake GitHub (`responses`): tests never reach github.com and never start
a program."""

import hashlib
from datetime import datetime, timedelta, timezone

import pytest
import requests
import responses

from sla_agent import __version__, update
from sla_agent.state import State, load_state, save_state

NOW = datetime(2026, 10, 5, 7, 0, tzinfo=timezone.utc)
SETUP = b"MZ the setup of the next version"
DOWNLOADS = "https://github.com/nguyenkhangvy/School-Life-Assistant/releases/download/v9.0.0/"


def release(tag="v9.0.0", setup_url=DOWNLOADS + "School-Life-Assistant.exe",
            sums_url=DOWNLOADS + "SHA256SUMS.txt"):
    return {"tag_name": tag, "assets": [
        {"name": "School-Life-Assistant.exe", "browser_download_url": setup_url},
        {"name": "SHA256SUMS.txt", "browser_download_url": sums_url},
    ]}


def publish(setup=SETUP, sums=None):
    """GitHub's answers for release v9.0.0: the release, its checksums and its setup."""
    responses.get(update.LATEST, json=release())
    listed = f"{hashlib.sha256(SETUP).hexdigest()}  School-Life-Assistant.exe\n" if sums is None else sums
    responses.get(DOWNLOADS + "SHA256SUMS.txt", body=listed)
    responses.get(DOWNLOADS + "School-Life-Assistant.exe", body=setup)


class Starts(list):
    """Stands in for starting the downloaded setup."""

    def __call__(self, path):
        self.append(path)


@pytest.fixture
def state():
    save_state(State(server_url="https://sla.example.com", student_id="ITITIU20001"))
    return load_state()


def downloaded():
    folder = update.downloads()
    return sorted(path.name for path in folder.iterdir()) if folder.exists() else []


@responses.activate
def test_a_newer_release_is_downloaded_checked_and_started(state):
    publish()
    started = Starts()

    assert update.check_and_start(state, NOW, start=started)

    assert started == [update.downloads() / "School-Life-Assistant-9.0.0.exe"]
    assert started[0].read_bytes() == SETUP
    assert load_state().update_checked_at == NOW.isoformat()


@pytest.mark.parametrize("tag", [f"v{__version__}", "v0.0.1"])
@responses.activate
def test_the_same_or_an_older_release_downloads_nothing(state, tag):
    responses.get(update.LATEST, json=release(tag=tag))
    started = Starts()

    assert not update.check_and_start(state, NOW, start=started)

    assert started == []
    assert len(responses.calls) == 1


@responses.activate
def test_github_is_asked_at_most_once_a_day(state):
    state.update_checked_at = (NOW - timedelta(hours=23)).isoformat()

    assert not update.check_and_start(state, NOW, start=Starts())

    assert len(responses.calls) == 0


def test_a_check_time_in_the_future_after_the_clock_was_fixed_does_not_block_checks():
    assert update.due(State(update_checked_at=(NOW + timedelta(days=300)).isoformat()), NOW)
    assert update.due(State(update_checked_at=(NOW - timedelta(hours=24)).isoformat()), NOW)
    assert update.due(State(), NOW)


@responses.activate
def test_a_wrong_checksum_keeps_nothing_and_starts_nothing(state):
    publish(setup=b"MZ something else")
    started = Starts()

    assert not update.check_and_start(state, NOW, start=started)

    assert started == []
    assert downloaded() == []


@responses.activate
def test_a_setup_larger_than_the_limit_is_not_kept(state, monkeypatch):
    publish()
    monkeypatch.setattr(update, "MAX_BYTES", 10)
    started = Starts()

    assert not update.check_and_start(state, NOW, start=started)

    assert started == []
    assert downloaded() == []


@pytest.mark.parametrize("body", [
    release(setup_url="https://example.com/School-Life-Assistant.exe"),
    release(sums_url="http://github.com/nguyenkhangvy/School-Life-Assistant/releases/download/v9.0.0/SHA256SUMS.txt"),
    {"tag_name": "v9.0.0", "assets": []},
    release(tag="latest"),
    release(tag="v1"),
])
@responses.activate
def test_a_release_that_does_not_look_right_downloads_nothing(state, body):
    responses.get(update.LATEST, json=body)
    started = Starts()

    assert not update.check_and_start(state, NOW, start=started)

    assert started == []
    assert len(responses.calls) == 1


@pytest.mark.parametrize("sums", ["", "not a checksum\n", f"{'a' * 64}  Other.exe\n"])
@responses.activate
def test_checksums_without_a_line_for_the_setup_download_nothing(state, sums):
    publish(sums=sums)
    started = Starts()

    assert not update.check_and_start(state, NOW, start=started)

    assert started == []
    assert downloaded() == []


@pytest.mark.parametrize("answer", [
    dict(status=403, json={"message": "API rate limit exceeded"}),
    dict(body=requests.ConnectionError("offline")),
])
@responses.activate
def test_github_unreachable_or_busy_changes_nothing_and_waits_a_day(state, answer):
    responses.get(update.LATEST, **answer)

    assert not update.check_and_start(state, NOW, start=Starts())

    assert load_state().update_checked_at == NOW.isoformat()
    assert not update.due(load_state(), NOW + timedelta(hours=1))


@responses.activate
def test_a_setup_that_cannot_be_started_changes_nothing(state):
    publish()

    def blocked(path):
        raise PermissionError("blocked by the antivirus")

    assert not update.check_and_start(state, NOW, start=blocked)


def test_the_first_run_of_a_new_version_remembers_when_it_updated_itself():
    state = State(agent_version="0.1.0")

    assert update.note_new_version(state, NOW)

    assert (state.agent_version, state.updated_at) == (__version__, NOW.isoformat())


def test_the_first_run_ever_is_not_an_update():
    state = State()

    assert update.note_new_version(state, NOW)

    assert (state.agent_version, state.updated_at) == (__version__, None)


def test_the_same_version_changes_nothing():
    assert not update.note_new_version(State(agent_version=__version__), NOW)


def test_downloaded_setups_are_deleted():
    update.downloads().mkdir(parents=True)
    (update.downloads() / "School-Life-Assistant-9.0.0.exe").write_bytes(b"MZ")
    (update.downloads() / "School-Life-Assistant-9.0.0.exe.part").write_bytes(b"MZ")

    update.clean_downloads()

    assert downloaded() == []
    update.clean_downloads()  # and nothing to delete is fine


# ---- after the website refused this version (review I1) ----------------------------------------


def test_after_a_refusal_the_next_check_is_within_the_hour_not_every_minute():
    state = State(update_checked_at=(NOW - timedelta(hours=2)).isoformat())

    update.check_soon(state, NOW)

    assert not update.due(state, NOW + timedelta(minutes=59))
    assert update.due(state, NOW + timedelta(minutes=61))


def test_after_a_refusal_a_check_already_due_stays_due():
    state = State()

    update.check_soon(state, NOW)

    assert update.due(state, NOW)


# ---- an unreadable check time (review M2) --------------------------------------------------------


@pytest.mark.parametrize("unreadable", ["not a time", "2026-10-05T07:00:00"])  # the second has no time zone
@responses.activate
def test_an_unreadable_check_time_counts_as_due_and_never_stops_the_sync(state, unreadable):
    state.update_checked_at = unreadable  # as if state.json had been edited by hand
    save_state(state)
    responses.get(update.LATEST, json=release(tag=f"v{__version__}"))

    assert not update.check_and_start(state, NOW, start=Starts())

    assert load_state().update_checked_at == NOW.isoformat()


def test_after_a_refusal_an_unreadable_check_time_is_simply_due():
    state = State(update_checked_at="not a time")

    update.check_soon(state, NOW)

    assert update.due(state, NOW)
