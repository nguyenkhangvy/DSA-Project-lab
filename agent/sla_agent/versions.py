"""Version numbers such as "0.2.0", or a release tag such as "v0.10.1", compared as numbers: 0.10.0 is newer than
0.9.0. Standard library only, because the setup (installer.py) uses it too."""

import re

PATTERN = re.compile(r"v?(\d{1,6})\.(\d{1,6})(?:\.(\d{1,6}))?")


def parse(text):
    """(major, minor, patch) from "0.2.0", "v0.2.0" or "0.1"; ValueError for anything else."""
    match = PATTERN.fullmatch((text or "").strip())
    if not match:
        raise ValueError(f"not a version number: {text!r}")
    return tuple(int(part or 0) for part in match.groups())


def is_newer(candidate, current):
    return parse(candidate) > parse(current)
