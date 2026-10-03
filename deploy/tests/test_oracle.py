"""The server's scripts (deploy/oracle; README, "Always on: AWS Lightsail"), run by bash with git and docker
replaced by fakes that write down each call."""

import gzip
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

ORACLE = Path(__file__).resolve().parents[1] / "oracle"

if sys.platform == "win32":
    # Git's bash, with its tools (sed, gzip...) on PATH. WSL's bash can't see Windows paths. git.exe is in Git\cmd,
    # or Git\mingw64\bin in Git Bash, so look up from it for the folder that has usr\bin\bash.exe.
    _git = shutil.which("git")
    _tools = next((folder / "usr" / "bin" for folder in Path(_git).resolve().parents
                   if (folder / "usr" / "bin" / "bash.exe").exists()), None) if _git else None
    BASH = str(_tools / "bash.exe") if _tools else None
    TOOLS = [str(_tools)] if BASH else []
else:
    BASH = shutil.which("bash")
    TOOLS = []

pytestmark = pytest.mark.skipif(BASH is None, reason="needs bash (on Windows, Git's)")

# Stands in for git and docker: each call becomes a line of calls.log, ending with whether compose.yaml is in the
# folder it ran in. FAKE_GIT_EXIT / FAKE_DOCKER_EXIT make it fail.
FAKE = r"""#!/usr/bin/env bash
name=$(basename "$0")
where=elsewhere; [ -f compose.yaml ] && where=here
echo "$name $* ($where)" >> "$FAKES/calls.log"
if [ "$name $1" = "docker run" ]; then
    # A backup: keep the option file it mounted and the database it named, and print a dump.
    for arg in "$@"; do case $arg in *:/*) cp "${arg%%:*}" "$FAKES/client.cnf" ;; esac; done
    echo "${@: -1}" > "$FAKES/database"
    echo "-- dump of the database"
fi
code=FAKE_${name^^}_EXIT
exit "${!code:-0}"
"""

AIVEN = "DATABASE_URL=mysql://avnadmin:Sup3rSecret@mysql-sla.aivencloud.com:12345/defaultdb?ssl-mode=REQUIRED"


def run(script, tmp_path, env_file=None, **settings):
    """A copy of deploy/oracle/<script> in tmp_path/oracle, with its .env, started from tmp_path."""
    folder = tmp_path / "oracle"
    folder.mkdir(exist_ok=True)
    shutil.copy(ORACLE / script, folder / script)
    (folder / "compose.yaml").write_text("")
    if env_file is not None:
        (folder / ".env").write_text(env_file, newline="\n")
    fakes = tmp_path / "fakes"
    fakes.mkdir(exist_ok=True)
    for name in ("git", "docker"):
        (fakes / name).write_text(FAKE, newline="\n")
        (fakes / name).chmod(0o755)
    env = {**os.environ, "PATH": os.pathsep.join([str(fakes), *TOOLS, os.environ["PATH"]]),
           "FAKES": fakes.as_posix(), "BACKUP_DIR": (tmp_path / "backups").as_posix(), **settings}
    return subprocess.run([BASH, (folder / script).as_posix()], cwd=tmp_path, env=env, capture_output=True,
                          text=True)


def calls(tmp_path):
    log = tmp_path / "fakes" / "calls.log"
    return log.read_text() if log.exists() else ""


# ---- backup.sh ----------------------------------------------------------------------


def backup(tmp_path, env_file=f"SITE_ADDRESS=sla.example.org\n{AIVEN}\n", **settings):
    return run("backup.sh", tmp_path, env_file, **settings)


def old_backups(tmp_path, count):
    """`count` backups from January 2000, oldest first."""
    folder = tmp_path / "backups"
    folder.mkdir()
    names = [f"sla-2000-01-{day:02d}-000000.sql.gz" for day in range(1, count + 1)]
    for name in names:
        (folder / name).write_bytes(b"old")
    return names


def mounted(tmp_path):
    """What the backup gave mysqldump: its option file's settings, and the database."""
    lines = (tmp_path / "fakes" / "client.cnf").read_text().splitlines()
    settings = {key: value.strip('"') for key, value in (line.split("=", 1) for line in lines if "=" in line)}
    settings["database"] = (tmp_path / "fakes" / "database").read_text().strip()
    return settings


def test_backup_saves_the_dump_compressed(tmp_path):
    result = backup(tmp_path)

    assert result.returncode == 0, result.stderr
    [saved] = (tmp_path / "backups").iterdir()
    assert re.fullmatch(r"sla-\d{4}-\d\d-\d\d-\d{6}\.sql\.gz", saved.name)
    assert gzip.decompress(saved.read_bytes()) == b"-- dump of the database\n"


@pytest.mark.parametrize("line, expected", [
    # Aiven's Service URI. %40 is an @; a + stays a +, as the site reads it.
    ("DATABASE_URL=mysql://avnadmin:p%40ss+word@mysql-sla.aivencloud.com:12345/defaultdb?ssl-mode=REQUIRED",
     {"user": "avnadmin", "password": "p@ss+word", "host": "mysql-sla.aivencloud.com", "port": "12345",
      "database": "defaultdb"}),
    # A laptop's form, in quotes, without a port.
    ("DATABASE_URL='mysql+pymysql://sla_app:secret@localhost/school_life?charset=utf8mb4'",
     {"user": "sla_app", "password": "secret", "host": "localhost", "port": "3306", "database": "school_life"}),
])
def test_backup_dumps_the_database_that_database_url_names(tmp_path, line, expected):
    result = backup(tmp_path, f"SITE_ADDRESS=sla.example.org\n{line}\n")

    assert result.returncode == 0, result.stderr
    given = mounted(tmp_path)
    assert {key: given.get(key) for key in expected} == expected


def test_backup_keeps_the_password_off_the_command_line(tmp_path):
    backup(tmp_path)

    assert "docker run" in calls(tmp_path)
    assert "Sup3rSecret" not in calls(tmp_path)  # anyone on the VM can list command lines


def test_backup_takes_one_snapshot_the_way_aiven_allows(tmp_path):
    backup(tmp_path)

    call = calls(tmp_path)
    assert "--single-transaction" in call  # every table as it was at one moment, while the site keeps writing
    assert "--no-tablespaces" in call  # Aiven's user may not read tablespaces...
    assert "--set-gtid-purged=OFF" in call  # ...nor set GTIDs, which a restore would otherwise try


def test_backup_keeps_the_newest_14(tmp_path):
    old = old_backups(tmp_path, 15)
    (tmp_path / "backups" / "notes.txt").write_text("not a backup")

    result = backup(tmp_path)

    assert result.returncode == 0, result.stderr
    kept = sorted(path.name for path in (tmp_path / "backups").glob("sla-*.sql.gz"))
    assert len(kept) == 14
    assert kept[:13] == old[2:]  # 15 old and 1 new: the two oldest went
    assert (tmp_path / "backups" / "notes.txt").exists()


def test_a_failed_backup_saves_nothing_and_keeps_the_old_ones(tmp_path):
    old = old_backups(tmp_path, 15)

    result = backup(tmp_path, FAKE_DOCKER_EXIT="1")

    assert result.returncode != 0
    assert sorted(path.name for path in (tmp_path / "backups").iterdir()) == old


@pytest.mark.parametrize("env_file", ["SITE_ADDRESS=sla.example.org\n", None])
def test_backup_without_database_url_stops_before_dumping(tmp_path, env_file):
    result = backup(tmp_path, env_file)

    assert result.returncode != 0
    assert "DATABASE_URL" in result.stderr
    assert calls(tmp_path) == ""


# ---- update.sh ----------------------------------------------------------------------


def test_update_pulls_main_then_rebuilds_the_site(tmp_path):
    result = run("update.sh", tmp_path)

    assert result.returncode == 0, result.stderr
    assert calls(tmp_path).splitlines() == [
        "git pull --ff-only (here)",
        "docker compose up -d --build (here)",
        "docker image prune -f (here)",
    ]


def test_update_stops_when_the_pull_fails(tmp_path):
    result = run("update.sh", tmp_path, FAKE_GIT_EXIT="1")

    assert result.returncode != 0
    assert calls(tmp_path).splitlines() == ["git pull --ff-only (here)"]
