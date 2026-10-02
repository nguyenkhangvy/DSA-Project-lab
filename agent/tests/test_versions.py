"""Version numbers (versions.py) and the one place the agent's version lives."""

import tomllib
from pathlib import Path

import pytest

from sla_agent import __version__
from sla_agent.edusoft_client import USER_AGENT
from sla_agent.versions import is_newer, parse


def test_versions_are_compared_as_numbers():
    assert is_newer("v0.10.0", "0.9.0")
    assert not is_newer("v0.2.0", "0.2.0")
    assert not is_newer("0.1.9", "0.2.0")


def test_a_release_tag_and_a_short_version_are_read():
    assert parse("v0.2.0") == (0, 2, 0)
    assert parse("0.1") == (0, 1, 0)


@pytest.mark.parametrize("text", ["", "latest", "v1", "0.2.0-beta", "0.2.0.1", None])
def test_anything_else_is_not_a_version(text):
    with pytest.raises(ValueError):
        parse(text)


def test_the_agent_tells_websites_its_version():
    assert USER_AGENT == f"SchoolLifeAssistant/{__version__} (IU student project)"
    assert parse(__version__) >= (0, 2, 0)


def test_the_package_reads_its_version_from_sla_agent():
    project = tomllib.loads((Path(__file__).resolve().parents[1] / "pyproject.toml").read_text(encoding="utf-8"))

    assert "version" not in project["project"]
    assert project["project"]["dynamic"] == ["version"]
    assert project["tool"]["setuptools"]["dynamic"]["version"] == {"attr": "sla_agent.__version__"}
