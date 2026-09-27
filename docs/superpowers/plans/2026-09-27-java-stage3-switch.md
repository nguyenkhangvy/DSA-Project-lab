# Java Stage 3: The Switch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Java website replaces the Python website. It runs on port 5000, where the laptop agent already sends its data. A real sync goes into it, and the student confirms the pages show their data. Then the Python website code goes and Alembic's leftover table is dropped. Last, the README is rewritten for the Java site and the laptop agent.

**Architecture:** No new features. One setting changes (the port), and one Flyway migration (`DROP TABLE IF EXISTS alembic_version`) runs against a copy of a Python-made database in its test. The Python side shrinks to the laptop agent (`agent/`) and its data format (`contract/`). The data-format test moves from `tests/` to `contract/tests/`, because `tests/helpers.py` imported the website. The GitHub checks run the agent's tests without MySQL, plus the unchanged Java job. Task 2 is a checkpoint: nothing is removed until the student has seen their own data on the Java site.

**Tech Stack:** Java 17, Spring Boot 4.1.1, Flyway, H2 (tests); Python 3.12 + pytest for the agent; GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-26-java-website-design.md` — §2 (stage 3), §6, §7 (the switch), §8, §10.

**Tried first:** every file below was made in a scratch worktree before this plan was written. With the changes, 310 Java tests and 205 Python tests (169 agent, 36 data format) pass. Without the migration, its test fails.

## Global Constraints

- The laptop agent's saved server address `http://localhost:5000` must keep working unchanged; the site's port is 5000 (spec §7).
- The student confirms the Java site with their own data before anything is removed (spec §7: "Only when all of these hold"). Every Java test passing and the browser check in Edge (1400×1000 and 390×844, light and dark) were done in stage 2b on made-up data.
- Remove: `app/`, the website tests in `tests/`, `migrations/`, `wsgi.py`, `.flaskenv`, the Flask packages from the requirements. Keep: `agent/`, `contract/` (the data format and the shared samples), the agent's requirements (spec §7).
- `alembic_version` is dropped by a Flyway migration named by the migration rule `V<date>_<module>_<n>__<what>.sql` (module 1: the student owns the School module and this switch).
- Real syncs with the saved EduSoft and Blackboard logins run in the main session only, never a subagent. Never print `.env` values, the student ID or the device key.
- Comments in `web/` that mention the Python site or name its files ("Java twin of tests/test_school_sync_api.py") stay as they are: they say where the code and formats came from (like `DATABASE_URL`'s `mysql+pymysql://`), and those files stay in the git history.
- Outside the repository, this plan does three things: it stops the old sites (the Python site on port 5000 and the student's own Java site on port 8080, if they're running), runs one real sync, and drops `alembic_version` from the student's database. All three are announced in the plan the student approves.
- Commands below are for Git Bash, run from the repository root.

## Review Focus

1. **The agent's scheduled check-ins during the switch.** Between stopping Flask and starting Java nothing answers on port 5000. The agent must only log "couldn't reach", never lose data or pause. The agent's own tests cover `ServerUnreachable`; Task 2 starts Java right after stopping Flask.
2. **A database made by the Python site.** The drop must work on the student's database, which has Flyway's baseline row and `alembic_version`, and on an empty one (a teammate's laptop, GitHub's MySQL). Pinned by `MigrationTest.theSwitchRemovesAlembicsTableFromTheDatabaseThePythonSiteMade` (Task 4) and the existing `anEmptyDatabaseGetsEveryTable`.
3. **Python tests losing their helpers.** `tests/helpers.py` imported `app.config`, so deleting `app/` would break the data-format test. Pinned by moving it to `contract/tests/test_contract.py` with its own helpers (Task 3) and running the whole Python suite there.
4. **Port 5000 drifting back.** A later edit of `application.properties` would silently break every laptop's sync. Pinned by `SiteSettingsTest.theSiteRunsOnPort5000WhereTheLaptopAgentLooksForIt` (Task 1).
5. **Cookies on http://localhost.** If `.env` lacks `SESSION_COOKIE_SECURE=false`, login on the laptop silently fails (the browser drops the HTTPS-only cookie). Task 2 checks the setting before the student logs in; the README (Task 5) says to keep it.

---

### Task 1: The Java site on port 5000

**Files:**
- Modify: `web/src/main/resources/application.properties` (replaced)
- Test: `web/src/test/java/vn/edu/hcmiu/sla/core/SiteSettingsTest.java`

**Interfaces:**
- Consumes: stage 1's settings file.
- Produces: the site listens on port 5000.

- [ ] **Step 1: Write the failing test**

`web/src/test/java/vn/edu/hcmiu/sla/core/SiteSettingsTest.java`:

```java
package vn.edu.hcmiu.sla.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

/** Settings that other programs depend on. */
class SiteSettingsTest {

    @Test
    void theSiteRunsOnPort5000WhereTheLaptopAgentLooksForIt() throws Exception {
        Properties settings = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));

        assertThat(settings.getProperty("server.port")).isEqualTo("5000");
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `(cd web && ./mvnw -B test -Dtest=SiteSettingsTest)`
Expected: `Tests run: 1, Failures: 1`, with `expected: "5000"` but was `"8080"`.

- [ ] **Step 3: Change the port**

Replace `web/src/main/resources/application.properties` with:

```properties
spring.application.name=sla-web

# Settings from .env: the repository root's, or one next to this project.
spring.config.import=optional:file:../.env[.properties],optional:file:.env[.properties]

# 5000: the laptop agent's saved address is http://localhost:5000.
server.port=5000

server.servlet.session.cookie.http-only=true
server.servlet.session.cookie.same-site=lax
# HTTPS-only cookies unless SESSION_COOKIE_SECURE=false (for http://localhost).
server.servlet.session.cookie.secure=${SESSION_COOKIE_SECURE:true}

# Flyway creates and changes tables; JPA only checks them.
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false
spring.jpa.properties.hibernate.jdbc.time_zone=UTC
# On a database the Python site already set up, record V1 as done instead of running it.
spring.flyway.baseline-on-migrate=true
spring.flyway.baseline-version=1
# Migrations are named V<date>_<module>_<n>__<what>.sql (V20261001_2_1__expense_tables.sql; module 1 School,
# 2 Expense, 3 Health), so files that teammates make in parallel never clash and can arrive in any order.
spring.flyway.out-of-order=true
```

- [ ] **Step 4: Run it to make sure it passes**

Run: `(cd web && ./mvnw -B test -Dtest=SiteSettingsTest)`
Expected: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 5: Run every test**

Run: `(cd web && ./mvnw -B test)`
Expected: `Tests run: 309, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add web/src/main/resources/application.properties web/src/test/java/vn/edu/hcmiu/sla/core/SiteSettingsTest.java
git commit -m "feat(web): run on port 5000, where the laptop agent sends its data"
```

---

### Task 2: Checkpoint: the switch, a real sync, and the student's own check

Nothing in the repository changes in this task. It stops the Python site, starts the Java site on port 5000 against the student's database, and runs one real sync with the agent's own `sync-now`. Then **the executor stops and asks the student** to look at their own data on the Java site. Only after the student says it's right do Tasks 3–5 run.

**Files:** none.

**Interfaces:**
- Consumes: Task 1 (port 5000); stages 1–2.
- Produces: the Java site running on port 5000 (it keeps running for Task 4); the student's go-ahead.

- [ ] **Step 1: Login cookies work on http://localhost**

Run (prints only whether the setting is there, not the file):

```bash
grep -qiE "^SESSION_COOKIE_SECURE=false\s*$" .env && echo "SESSION_COOKIE_SECURE=false: present" || echo "SESSION_COOKIE_SECURE=false: missing"
```

Expected: `present`. If missing, stop and ask the student to add the line `SESSION_COOKIE_SECURE=false` to `.env` (from `.env.example`); don't edit `.env` for them.

- [ ] **Step 2: Stop the old sites**

```bash
netstat -ano | grep -E ":(5000|8080) .*LISTENING"
```

Two old sites may be running: the Python site on port 5000 (`python.exe`), and the student's own Java site from before this plan on port 8080 (`java.exe`, started with `mvnw.cmd spring-boot:run` from a terminal; it would stay on the old port). For each PID shown, check which it is with PowerShell: `(Get-CimInstance Win32_Process -Filter "ProcessId=<pid>").CommandLine` shows `flask` or `wsgi` for the Python site and `vn.edu.hcmiu.sla.SlaWebApplication` for the Java site. Then stop it with `taskkill //F //PID <pid>`. Expected: nothing listens on ports 5000 or 8080 afterwards. If either port belongs to anything else, stop and ask the student.

- [ ] **Step 3: Start the Java site on port 5000**

In the background (it reads the repository's `.env`, so the student's database):

```bash
(cd web && ./mvnw -B spring-boot:run)
```

Expected in its output: `No migration necessary`, `Tomcat started on port 5000`, `Started SlaWebApplication`.

- [ ] **Step 4: One real sync into it, with the agent's own command**

**Main session only.** `sync-now` uses the saved EduSoft and Blackboard logins, which go only to EduSoft and Blackboard, as always. It sends to the agent's saved address, `http://localhost:5000`, now the Java site.

Run: `.venv/Scripts/sla-agent.exe sync-now`
Expected: `Sync finished.` (or `Sync finished, but couldn't read: …` if EduSoft or Blackboard itself had a problem). `The last sync was less than 5 minutes ago` means wait 5 minutes and run it again.

- [ ] **Step 5: What the Java site saved**

Save as `$SCRATCH/java_sync_check.py` (`SCRATCH` is the session's scratchpad; this is the script from stage 2a):

```python
"""Show the newest sync run and its What-changed lines (reads DATABASE_URL from .env, prints no secrets)."""

import json
from urllib.parse import unquote, urlsplit

import pymysql

url = next(line.split("=", 1)[1].strip().strip('"').strip("'")
           for line in open(".env", encoding="utf-8") if line.strip().startswith("DATABASE_URL="))
parts = urlsplit(url)
db = pymysql.connect(host=parts.hostname, port=parts.port or 3306, user=unquote(parts.username),
                     password=unquote(parts.password or ""), database=parts.path.lstrip("/"), charset="utf8mb4")
with db.cursor() as cursor:
    cursor.execute("SELECT id, `trigger`, status, sections FROM school_sync_runs ORDER BY id DESC LIMIT 1")
    run_id, trigger, status, sections = cursor.fetchone()
    print("run", run_id, trigger, status, json.loads(sections) if sections else None)
    cursor.execute("SELECT section, kind, summary FROM school_changes WHERE sync_run_id = %s ORDER BY id", (run_id,))
    for row in cursor.fetchall():
        print(" ", *row)
db.close()
```

Run: `.venv/Scripts/python.exe "$SCRATCH/java_sync_check.py"`
Expected: `run <id> manual success {…every part "ok"…}`, and What-changed lines only for real changes since the last sync.

- [ ] **Step 6: Stop and ask the student**

Ask the student to open http://localhost:5000/school, log in with their usual account, and check:
- the Overview (status "Synced at …", today's and tomorrow's classes with their Online/Make-up/Cancelled tags, To submit);
- the Timetable, including the real class changes from Blackboard announcements;
- Courses and a course page, Exams, Tuition and Devices.

If they want to compare side by side, the Python site can still run on another port until Task 3: `.venv/Scripts/flask.exe run --port 5001`, then stop it with Ctrl+C.

Wait for the student's answer. If they report a problem, debug it (superpowers:systematic-debugging) before going on. Tasks 3–5 run only after they say it's right. Leave the Java site running.

---

### Task 3: Remove the Python website

The website's Python code, its tests and its migrations go. Python stays only for the laptop agent and its data format. The data-format test moves to `contract/tests/`, with the few helpers it used from `tests/helpers.py` copied in (that file imported the website). The GitHub check for Python runs the agent's and the data-format tests, without MySQL.

**Files:**
- Create: `contract/tests/test_contract.py` (moved from `tests/test_contract.py`, with its helpers)
- Modify: `pyproject.toml`, `requirements-dev.txt`, `.github/workflows/ci.yml` (all replaced)
- Delete: `app/`, `tests/`, `migrations/`, `wsgi.py`, `.flaskenv`, `requirements.txt`

**Interfaces:**
- Consumes: the student's go-ahead (Task 2); `contract/samples/` (stage 2a).
- Produces: `pytest` from the repository root runs `contract/tests` and `agent/tests`.

- [ ] **Step 1: Move the data-format test**

`contract/tests/test_contract.py` (the old `tests/test_contract.py` below its imports, unchanged):

```python
"""The agent's data format: what it accepts and refuses. contract/samples/ holds example uploads that the
Java website's tests check too (web/src/test/java/vn/edu/hcmiu/sla/school/sync/SyncContractTest.java)."""

import copy
import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from sla_contract.schema import FinishRun

SAMPLES = Path(__file__).resolve().parents[1] / "samples"
BB = "https://blackboard.hcmiu.edu.vn"


def _sample(name):
    return json.loads((SAMPLES / name).read_text(encoding="utf-8"))


# A complete, valid upload from the agent. Times are Vietnam time (+07:00).
def full_payload():
    return _sample("finish-edusoft.json")


# A valid Blackboard section as the agent uploads it.
def blackboard_payload():
    return _sample("finish-blackboard.json")["blackboard"]["data"]


def test_a_complete_upload_is_accepted():
    finish = FinishRun.model_validate(full_payload())

    meeting = finish.timetable.data.courses[0].meetings[0]
    assert meeting.start_at.utcoffset().total_seconds() == 7 * 3600
    assert finish.tuition.data.balance == 12500000


def test_times_without_a_timezone_are_rejected():
    payload = full_payload()
    payload["exams"]["data"]["exams"][0]["start_at"] = "2026-12-12T08:00:00"

    with pytest.raises(ValidationError):
        FinishRun.model_validate(payload)


def test_a_class_that_ends_before_it_starts_is_rejected():
    payload = full_payload()
    meeting = payload["timetable"]["data"]["courses"][0]["meetings"][0]
    meeting["end_at"] = "2026-09-29T07:00:00+07:00"

    with pytest.raises(ValidationError):
        FinishRun.model_validate(payload)


@pytest.mark.parametrize(
    "path",
    [
        ("date_of_birth",),
        ("timetable", "data", "student_id"),
        ("timetable", "data", "courses", 0, "student_name"),
        ("tuition", "data", "bank_account"),
    ],
)
def test_unknown_fields_are_rejected_so_no_extra_personal_data_gets_in(path):
    payload = full_payload()
    target = payload
    for key in path[:-1]:
        target = target[key]
    target[path[-1]] = "something personal"

    with pytest.raises(ValidationError):
        FinishRun.model_validate(payload)


@pytest.mark.parametrize(
    "section",
    [
        {"status": "ok"},
        {"status": "failed", "error_message": "Tuition table not found"},
        {"status": "failed", "error_code": "made_up_code", "error_message": "x"},
        {"status": "done", "data": {}},
    ],
    ids=["ok-without-data", "failed-without-code", "unknown-error-code", "unknown-status"],
)
def test_each_part_is_either_ok_with_data_or_failed_with_a_reason(section):
    payload = full_payload()
    payload["tuition"] = section

    with pytest.raises(ValidationError):
        FinishRun.model_validate(payload)


def test_a_whole_run_failure_carries_no_data():
    payload = full_payload()
    payload["error_code"] = "bad_credentials"
    payload["error_message"] = "EduSoft rejected the password"

    with pytest.raises(ValidationError):
        FinishRun.model_validate(payload)


def test_an_upload_with_neither_data_nor_an_error_is_rejected():
    with pytest.raises(ValidationError):
        FinishRun.model_validate({"schema_version": 1})


def _failed(code="edusoft_changed"):
    return {"status": "failed", "error_code": code, "error_message": "Table not found"}


@pytest.mark.parametrize(
    "changes, expected",
    [
        ({}, "success"),
        ({"tuition": _failed()}, "partial"),
        ({"timetable": _failed(), "exams": _failed(), "tuition": _failed()}, "failed"),
        ({"exams": None, "tuition": None}, "success"),
        (
            {"timetable": None, "exams": None, "tuition": None,
             "error_code": "bad_credentials", "error_message": "EduSoft rejected the password"},
            "failed",
        ),
    ],
    ids=["all-ok", "one-failed", "all-failed", "only-timetable-sent", "whole-run-error"],
)
def test_overall_status(changes, expected):
    payload = copy.deepcopy(full_payload())
    for key, value in changes.items():
        if value is None:
            payload.pop(key, None)
        else:
            payload[key] = value

    assert FinishRun.model_validate(payload).overall_status() == expected


def test_a_blackboard_section_is_accepted_next_to_edusoft():
    payload = full_payload()
    payload["blackboard"] = {"status": "ok", "data": blackboard_payload()}

    finish = FinishRun.model_validate(payload)

    assert list(finish.sections()) == ["timetable", "exams", "tuition", "blackboard"]
    assert finish.blackboard.data.courses[0].assignments[0].score == 8.5


@pytest.mark.parametrize(
    "change",
    [
        lambda p: p["courses"][0]["announcements"][0].update(url="https://evil.example/x"),
        lambda p: p["courses"][0]["announcements"][0].update(posted_at="2026-09-28T02:00:00"),
        lambda p: p["courses"][0]["assignments"][0].update(status="done"),
        lambda p: p["courses"][0]["materials"][0].update(kind="video"),
        lambda p: p["courses"][0].update(student_email="s@example.com"),
        lambda p: p["courses"][0]["announcements"][0].update(text="x" * 5001),
    ],
    ids=["link-to-another-site", "naive-time", "unknown-status", "unknown-kind", "extra-field", "text-too-long"],
)
def test_bad_blackboard_data_is_rejected(change):
    data = blackboard_payload()
    change(data)

    with pytest.raises(ValidationError):
        FinishRun.model_validate({"blackboard": {"status": "ok", "data": data}})


def test_a_failed_blackboard_part_can_say_its_format_changed():
    finish = FinishRun.model_validate({
        "timetable": full_payload()["timetable"],
        "blackboard": {"status": "failed", "error_code": "source_changed", "error_message": "Unexpected format"},
    })

    assert finish.overall_status() == "partial"


# ---- contract/samples/: the Java website's tests check the same files ----------


@pytest.mark.parametrize("path", sorted(SAMPLES.glob("*.json")), ids=lambda path: path.name)
def test_every_shared_sample_is_accepted(path):
    FinishRun.model_validate(json.loads(path.read_text(encoding="utf-8")))


@pytest.mark.parametrize("path", sorted((SAMPLES / "invalid").glob("*.json")), ids=lambda path: path.name)
def test_every_shared_invalid_sample_is_refused(path):
    with pytest.raises(ValidationError):
        FinishRun.model_validate(json.loads(path.read_text(encoding="utf-8")))
```

Replace `pyproject.toml` with:

```toml
[tool.pytest.ini_options]
testpaths = ["contract/tests", "agent/tests"]
addopts = "-q"
```

Run: `.venv/Scripts/python.exe -m pytest -p no:cacheprovider contract/tests`
Expected: `36 passed`.

- [ ] **Step 2: Delete the Python website**

```bash
git rm -r -q app tests migrations wsgi.py .flaskenv requirements.txt
rm -rf app tests migrations
```

(The `rm -rf` removes the `__pycache__` folders that `git rm` leaves behind; checked while writing this plan: nothing else is in them.)

Replace `requirements-dev.txt` with:

```text
# The laptop sync agent (agent/), the data format it uploads (contract/), and their tests.
# The website is in web/ (Java); it needs no Python.
-e ./contract
-e ./agent
pydantic==2.13.5
requests==2.34.2
beautifulsoup4==4.15.0
keyring==25.7.0

# Tests
pytest==9.1.1
responses==0.26.3
```

Run: `.venv/Scripts/python.exe -m pytest -p no:cacheprovider`
Expected: `205 passed` (169 agent, 36 data format).

- [ ] **Step 3: The GitHub checks**

Replace `.github/workflows/ci.yml` with (the `web` job is unchanged):

```yaml
name: tests

on:
  pull_request:
  push:
    branches: [main]

jobs:
  agent:
    # The laptop sync agent and the data format it uploads (Python). The website is the web job below.
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v5

      - uses: actions/setup-python@v6
        with:
          python-version: "3.12"
          cache: pip

      - run: pip install -r requirements-dev.txt

      - name: Agent and data-format tests
        run: pytest

  web:
    runs-on: ubuntu-latest

    services:
      mysql:
        image: mysql:8.4
        env:
          MYSQL_ROOT_PASSWORD: root
          MYSQL_DATABASE: sla_web_test
        ports:
          - 3306:3306
        options: >-
          --health-cmd="mysqladmin ping -h 127.0.0.1 -proot"
          --health-interval=5s
          --health-timeout=5s
          --health-retries=20

    defaults:
      run:
        working-directory: web

    steps:
      - uses: actions/checkout@v5

      - uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: "17"
          cache: maven

      - name: Java tests (in-memory database)
        run: ./mvnw -B test

      - name: Java tests (on MySQL)
        env:
          # CI-only throwaway database; not a real secret. Environment variables win over the test settings.
          SPRING_DATASOURCE_URL: jdbc:mysql://127.0.0.1:3306/sla_web_test
          SPRING_DATASOURCE_USERNAME: root
          SPRING_DATASOURCE_PASSWORD: root
        run: ./mvnw -B test
```

- [ ] **Step 4: The Java tests still pass**

Run: `(cd web && ./mvnw -B test)`
Expected: `Tests run: 309, Failures: 0, Errors: 0, Skipped: 0` (the Java site never used the Python files; the shared samples are still at `contract/samples/`).

- [ ] **Step 5: Commit**

(Step 2's `git rm` already staged the deletions.)

```bash
git add contract/tests/test_contract.py pyproject.toml requirements-dev.txt .github/workflows/ci.yml
git commit -m "chore: remove the Python website; Python stays for the laptop agent and its data format"
```

---

### Task 4: Drop Alembic's leftover table

The Python site's migration tool kept its version in `alembic_version`; Flyway keeps the tables up to date now. The migration runs on every database: it drops the table on the student's database, and on an empty one it does nothing.

**Files:**
- Create: `web/src/main/resources/db/migration/V20260927_1_1__drop_alembic_version.sql`
- Test: `web/src/test/java/vn/edu/hcmiu/sla/core/MigrationTest.java` (replaced; a new test and a shared `tables` helper)

**Interfaces:**
- Consumes: stage 1's Flyway settings (`baseline-on-migrate`, `baseline-version=1`, `out-of-order`) and `MigrationNamingTest`.
- Produces: no `alembic_version` table on any database the site runs on.

- [ ] **Step 1: Write the failing test**

Replace `web/src/test/java/vn/edu/hcmiu/sla/core/MigrationTest.java` with:

```java
package vn.edu.hcmiu.sla.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** On an empty database (a teammate's laptop), Flyway's V1 creates every table the Python site had. */
@SpringBootTest
class MigrationTest {

    @Autowired
    DataSource dataSource;

    static Set<String> tables(DataSource database) throws Exception {
        Set<String> tables = new HashSet<>();
        try (Connection connection = database.getConnection();
             ResultSet rows = connection.getMetaData().getTables(connection.getCatalog(), null, "%", new String[] {"TABLE"})) {
            while (rows.next()) {
                tables.add(rows.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return tables;
    }

    @Test
    void anEmptyDatabaseGetsEveryTable() throws Exception {
        assertThat(tables(dataSource)).contains(
                "users", "school_sync_devices", "school_sync_settings", "school_sync_runs", "school_changes",
                "school_courses", "school_class_meetings", "school_exams", "school_tuition", "school_events",
                "school_bb_courses", "school_bb_announcements", "school_bb_assignments", "school_bb_materials",
                "flyway_schema_history");
    }

    @Test
    void theSwitchRemovesAlembicsTableFromTheDatabaseThePythonSiteMade() throws Exception {
        // Like the student's database: made by the Python site, with Alembic's alembic_version table.
        DriverManagerDataSource pythonMade = new DriverManagerDataSource(
                "jdbc:h2:mem:python-made-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        try (Connection connection = pythonMade.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("CREATE TABLE alembic_version (version_num VARCHAR(32) NOT NULL, PRIMARY KEY (version_num))");
            sql.execute("INSERT INTO alembic_version VALUES ('7d2f4b9c1e30')");
        }

        // The site's own Flyway settings: record V1 as done on an existing database, then run what is newer.
        Flyway.configure().dataSource(pythonMade).baselineOnMigrate(true).baselineVersion("1").outOfOrder(true)
                .load().migrate();

        assertThat(tables(pythonMade)).contains("flyway_schema_history").doesNotContain("alembic_version");
    }

    @Test
    void theBaselineLeavesKeyNamesToMysqlLikeAlembicDid() throws Exception {
        // The student's database got MySQL's own names (email, user_id, school_courses_ibfk_1, ...). Unnamed keys
        // give a Flyway-made database the same names, so a later migration that changes a key by name works on both.
        String baseline = new ClassPathResource("db/migration/V1__baseline.sql").getContentAsString(StandardCharsets.UTF_8);

        assertThat(baseline).doesNotContain("CONSTRAINT");
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `(cd web && ./mvnw -B test -Dtest=MigrationTest)`
Expected: `Tests run: 3, Failures: 1`: `theSwitchRemovesAlembicsTableFromTheDatabaseThePythonSiteMade` finds `alembic_version` still there.

- [ ] **Step 3: The migration**

`web/src/main/resources/db/migration/V20260927_1_1__drop_alembic_version.sql`:

```sql
-- The Python site is gone, and with it Alembic, its migration tool. Flyway keeps the tables up to date now.
DROP TABLE IF EXISTS alembic_version;
```

- [ ] **Step 4: Run it to make sure it passes**

Run: `(cd web && ./mvnw -B test -Dtest='MigrationTest,MigrationNamingTest')`
Expected: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 5: Run every test**

Run: `(cd web && ./mvnw -B test)`
Expected: `Tests run: 310, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add web/src/main/resources/db/migration/V20260927_1_1__drop_alembic_version.sql web/src/test/java/vn/edu/hcmiu/sla/core/MigrationTest.java
git commit -m "feat(web): drop Alembic's version table; Flyway keeps the tables up to date now"
```

- [ ] **Step 7: Apply it to the student's database**

Stop the Java site started in Task 2 (its background task, then any Java process still listening on port 5000: `netstat -ano | grep ":5000 .*LISTENING"` and `taskkill //F //PID <pid>`), and start it again in the background:

```bash
(cd web && ./mvnw -B spring-boot:run)
```

Expected in its output: `Migrating schema \`school_life\` to version "20260927.1.1 - drop alembic version"`, `Successfully applied 1 migration`, `Started SlaWebApplication` on port 5000. Leave it running.

---

### Task 5: README and settings example for the Java site

**Files:**
- Modify: `README.md` (replaced), `.env.example` (replaced), `docs/superpowers/specs/2026-09-26-java-website-design.md` (status line)

**Interfaces:**
- Consumes: everything above.
- Produces: nothing new for later tasks.

- [ ] **Step 1: README**

Replace `README.md` with (the "Adding your module" guide is the previous "Adding your module in Java", unchanged except rule 5; the Flask sections are gone; the laptop agent gets its own section):

````markdown
# School-Life-Assistant

One web app for IU students, built by a team of 3 for the Web Application Development course:

- **School** (Vy): EduSoft timetable, exams and tuition, and Blackboard courses, synced automatically from a laptop, on one calendar.
- **Expense**: expense management.
- **Health**: health management.

Design: [the website](docs/superpowers/specs/2026-09-26-java-website-design.md) and [the School sync](docs/superpowers/specs/2026-09-25-edusoft-first-phase1-design.md).

Stack: Java 17 and Spring Boot with Thymeleaf pages, MySQL 8, a little JavaScript. The laptop sync agent for the School module is written in Python.

---

## First-time setup (Windows)

1. **Get the code** (skip this if you already have the project folder):

   ```powershell
   git clone https://github.com/nguyenkhangvy/School-Life-Assistant.git
   cd School-Life-Assistant
   ```

2. **Install Java 17** (Temurin, from adoptium.net). `java -version` should say 17. You don't need Maven: the project brings its own (`web\mvnw.cmd`).

3. **Create your local MySQL database.** Open a MySQL prompt with `mysql -u root -p`, then run the following, using a password of your own:

   ```sql
   CREATE DATABASE school_life CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
   CREATE USER 'sla_app'@'localhost' IDENTIFIED BY 'pick-your-own-password';
   GRANT ALL PRIVILEGES ON school_life.* TO 'sla_app'@'localhost';
   ```

4. **Create your settings file.** Copy `.env.example` to `.env` (`copy .env.example .env`) and put your database password in `DATABASE_URL`. Keep `SESSION_COOKIE_SECURE=false`: your laptop runs the site on `http://`, not `https://`.

   `.env` is in `.gitignore`. **Never commit it.**

5. **Start the site:**

   ```powershell
   cd web
   .\mvnw.cmd spring-boot:run
   ```

   Open http://localhost:5000, create an account and log in. Stop the site with Ctrl+C. The first start downloads Maven and the libraries (a few minutes); on an empty database it creates every table.

## Everyday commands

In PowerShell, from the `web` folder unless it says otherwise:

| What | Command |
|---|---|
| Start the site | `.\mvnw.cmd spring-boot:run` |
| Run the tests | `.\mvnw.cmd test` (an in-memory database, never yours) |
| After pulling new code | nothing: new tables and changes are applied when the site starts |
| After renaming or deleting a migration | `.\mvnw.cmd clean`, or the old copy stays in `target/` |
| Run the laptop agent's tests (project folder, agent installed) | `pytest` |

Git Bash works too: `cd web && ./mvnw spring-boot:run`. GitHub runs the website's tests on an in-memory database and on MySQL, and the agent's tests, for every pull request.

---

## The laptop agent (School sync)

Only needed to sync your own EduSoft and Blackboard into the School pages. It runs on your laptop, keeps your passwords in Windows Credential Manager, reads EduSoft and Blackboard there, and uploads only your timetable, exams, tuition and Blackboard courses to the site, with a device key.

1. **Install it** (Python 3.12), in the project folder:

   ```powershell
   py -3.12 -m venv .venv
   .venv\Scripts\activate
   pip install -r requirements-dev.txt
   ```

   If `activate` fails with "running scripts is disabled on this system", run
   `Set-ExecutionPolicy -Scope Process -ExecutionPolicy RemoteSigned` first (it only affects that window).
   Your prompt starts with `(.venv)` once it worked.

2. **Get a device key.** With the site running, open School → Devices, add your laptop and copy its key. It is shown only once.

3. **Set it up:** `sla-agent setup`. It asks for the web app address (`http://localhost:5000`), the device key, your EduSoft student ID and password (checked once with EduSoft), and optionally your Blackboard login. It then checks in every 15 minutes while you're logged in to Windows; the site tells it when a sync is due (every 12 hours, or soon after you press "Sync now").

| What | Command |
|---|---|
| Sync right away | `sla-agent sync-now` |
| See the last result | `sla-agent status` |
| Set up or change the Blackboard login | `sla-agent setup --blackboard` |
| Remove the saved passwords, key and schedule | `sla-agent forget` |

### The laptop agent's data format

The laptop agent uploads its data in the format set by `contract/sla_contract/schema.py`. The Java site reads it with `web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncContract.java`, so change the two together. `contract/samples/` holds example uploads that both the Python and the Java tests check: every file there must be accepted, every file in `contract/samples/invalid/` refused. When the format changes, update or add a sample.

---

## Adding your module

Example: Expense. Everything goes under `web/src/main/`.

1. **A table.** A migration named `V<date>_<module>_<number>__<what>.sql`. The module number (School = 1, Expense = 2, Health = 3) means two teammates never pick the same version: `resources/db/migration/V20261001_2_1__expense_tables.sql` (then `…_2_2__…`, `…_2_3__…` for more Expense migrations that day). `MigrationNamingTest` checks every name. After renaming or deleting a migration, run `.\mvnw.cmd clean`; otherwise the old copy stays in `target/`:

   ```sql
   CREATE TABLE expense_items (
       id INT NOT NULL AUTO_INCREMENT,
       user_id INT NOT NULL,
       title VARCHAR(200) NOT NULL,
       amount BIGINT NOT NULL,
       spent_on DATE NOT NULL,
       PRIMARY KEY (id),
       CONSTRAINT fk_expense_items_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
   );
   CREATE INDEX ix_expense_items_user_id ON expense_items (user_id);
   ```

   and a class for it, `java/vn/edu/hcmiu/sla/expense/ExpenseItem.java`:

   ```java
   package vn.edu.hcmiu.sla.expense;

   import java.time.LocalDate;

   import jakarta.persistence.Column;
   import jakarta.persistence.Entity;
   import jakarta.persistence.GeneratedValue;
   import jakarta.persistence.GenerationType;
   import jakarta.persistence.Id;
   import jakarta.persistence.Table;

   @Entity
   @Table(name = "expense_items")
   public class ExpenseItem {

       @Id
       @GeneratedValue(strategy = GenerationType.IDENTITY)
       private Integer id;

       @Column(name = "user_id", nullable = false)
       private Integer userId;

       @Column(nullable = false, length = 200)
       private String title;

       @Column(nullable = false)
       private long amount; // VND

       @Column(name = "spent_on", nullable = false)
       private LocalDate spentOn;

       protected ExpenseItem() {
       }

       public ExpenseItem(Integer userId, String title, long amount, LocalDate spentOn) {
           this.userId = userId;
           this.title = title;
           this.amount = amount;
           this.spentOn = spentOn;
       }

       public Integer getId() { return id; }
       public String getTitle() { return title; }
       public long getAmount() { return amount; }
       public LocalDate getSpentOn() { return spentOn; }
   }
   ```

   with a repository, `ExpenseItemRepository.java`:

   ```java
   package vn.edu.hcmiu.sla.expense;

   import java.util.List;
   import java.util.Optional;

   import org.springframework.data.jpa.repository.JpaRepository;

   public interface ExpenseItemRepository extends JpaRepository<ExpenseItem, Integer> {

       List<ExpenseItem> findByUserIdOrderBySpentOnDesc(Integer userId);

       Optional<ExpenseItem> findByIdAndUserId(Integer id, Integer userId);
   }
   ```

2. **Pages.** A controller, `ExpenseController.java`. Every page needs login automatically:

   ```java
   package vn.edu.hcmiu.sla.expense;

   import org.springframework.security.core.annotation.AuthenticationPrincipal;
   import org.springframework.stereotype.Controller;
   import org.springframework.ui.Model;
   import org.springframework.web.bind.annotation.GetMapping;
   import org.springframework.web.bind.annotation.RequestMapping;

   import vn.edu.hcmiu.sla.auth.AppUser;

   @Controller
   @RequestMapping("/expense")
   public class ExpenseController {

       private final ExpenseItemRepository expenses;

       public ExpenseController(ExpenseItemRepository expenses) {
           this.expenses = expenses;
       }

       @GetMapping
       String index(@AuthenticationPrincipal AppUser user, Model model) {
           model.addAttribute("items", expenses.findByUserIdOrderBySpentOnDesc(user.id()));
           return "expense/index";
       }
   }
   ```

3. **Templates** in `resources/templates/expense/`. They use the shared layout, so they get the header, the menu and the always-light look. `index.html`:

   ```html
   <!doctype html>
   <html xmlns:th="http://www.thymeleaf.org" th:replace="~{layout :: page(~{::title}, ~{::main})}">
   <head>
     <title>Expense · School-Life-Assistant</title>
   </head>
   <body>
   <main>
     <h1>Expense</h1>
     <ul>
       <li th:each="item : ${items}" th:text="|${item.spentOn} ${item.title}: ${item.amount} VND|">…</li>
     </ul>
   </main>
   </body>
   </html>
   ```

4. **The menu.** Add one bean in your package, and Expense gets its menu link and dashboard card:

   ```java
   package vn.edu.hcmiu.sla.expense;

   import org.springframework.context.annotation.Bean;
   import org.springframework.context.annotation.Configuration;

   import vn.edu.hcmiu.sla.core.NavModule;

   @Configuration
   class ExpenseModule {

       @Bean
       NavModule expenseNav() {
           return new NavModule("Expense", "/expense");
       }
   }
   ```

**Rules for every module in Java:**

1. URLs start with the module name (`/expense/...`), tables with the module name (`expense_...`).
2. Every table with user data has `user_id` → `users (id)`.
3. Every query is filtered by the logged-in user (`@AuthenticationPrincipal AppUser user`, then `user.id()`). To load one row, use both id and owner, so another user's row gives 404:

   ```java
   ExpenseItem item = expenses.findByIdAndUserId(id, user.id())
           .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
   ```

4. Forms use `th:action="@{/expense/...}"`, which adds the security code (CSRF) by itself. After a change, redirect and show a message with `Flash.success(redirect, "Saved.")`.
5. Changing a table means a new migration file; never edit a migration that is already on `main`.

---

## Team workflow

- Work on a branch, open a pull request, and get one teammate's review before merging to `main`.
- The tests must pass (GitHub shows a green check on the pull request).
- Never commit `.env`, passwords or keys.
````

- [ ] **Step 2: Settings example**

Replace `.env.example` with (Flask's `SECRET_KEY` is gone):

```text
# Copy this file to .env and fill in your own values. Never commit .env.

# Your local MySQL database (see README, "First-time setup").
# If your password contains @ write it as %40 (e.g. pass@word -> pass%40word).
DATABASE_URL=mysql+pymysql://sla_app:YOUR_DB_PASSWORD@localhost:3306/school_life?charset=utf8mb4

# Local development runs on http://localhost, so cookies can't be HTTPS-only.
# Leave this line out on the public site.
SESSION_COOKIE_SECURE=false
```

- [ ] **Step 3: Spec status**

In `docs/superpowers/specs/2026-09-26-java-website-design.md`, replace the status line.

Old:

```text
**Status:** Stage 1 built (see docs/superpowers/plans/2026-09-26-java-stage1-foundation.md); stage 2 built in two parts, the sync API and saving (docs/superpowers/plans/2026-09-27-java-stage2a-sync-api.md) and the School pages (docs/superpowers/plans/2026-09-27-java-stage2b-school-pages.md); stage 3 to come
```

New:

```text
**Status:** Done. Stage 1 (docs/superpowers/plans/2026-09-26-java-stage1-foundation.md), stage 2 in two parts (docs/superpowers/plans/2026-09-27-java-stage2a-sync-api.md, docs/superpowers/plans/2026-09-27-java-stage2b-school-pages.md) and stage 3, the switch (docs/superpowers/plans/2026-09-27-java-stage3-switch.md). The website is Java; Python remains for the laptop agent.
```

- [ ] **Step 4: Check that nothing still points at the Python site**

Run: `grep -nE "flask|Flask|8080|SECRET_KEY|wsgi" README.md .env.example pyproject.toml requirements-dev.txt .github/workflows/ci.yml web/src/main/resources/application.properties`
Expected: no output.

- [ ] **Step 5: Commit**

```bash
git add README.md .env.example docs/superpowers/specs/2026-09-26-java-website-design.md
git commit -m "docs: the README for the Java site and the laptop agent; the switch is done"
```
