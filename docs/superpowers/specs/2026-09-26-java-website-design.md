# School-Life-Assistant: the website in Java (Spring Boot)

**Date:** 2026-09-26
**Scope:** rebuild the website (login, shared layout, School module, sync API) in Java with Spring Boot, so the teammates can build the Expense and Health modules in Java; the laptop sync agent stays in Python
**Owner:** Nguyen Khang Vy
**Status:** Stage 1 built (see docs/superpowers/plans/2026-09-26-java-stage1-foundation.md); stages 2–3 to come
**Builds on:** [EduSoft-first Phase 1](2026-09-25-edusoft-first-phase1-design.md), [Blackboard](2026-09-26-blackboard-design.md), [Class changes and To submit](2026-09-26-class-changes-and-to-submit-design.md). Every behaviour those documents describe stays the same; only the website's language and framework change.

---

## 1. Goal

The two teammates want to write their modules (Expense, Health) in Java. Today the website is one Python (Flask) program, and a second, separate Java website would not share the login, menu or look (see the discussion on 2026-09-26). So the website itself moves to Java: one Spring Boot website that all three modules live in.

### Decided with the student

- **Website only.** The laptop sync agent (`agent/`, about 2,100 lines plus 2,300 lines of tests) stays in Python. It only talks to the website through the sync API, which keeps working exactly as now. The teammates never work on the agent.
- **Spring Boot** (Spring MVC, Thymeleaf, Spring Security, Spring Data JPA, Flyway).
- **Approach A: rebuild beside, then switch.** The Java website is built next to the Python one, on the same MySQL database and tables, while the Python website keeps working. When the Java website does everything the Python one does, it takes over and the Python website code is deleted.

### Not in scope

- Rewriting the laptop agent, or changing the sync data format or API
- New features (personal events, the changes page, the interval setting and deployment stay on the roadmap, built in Java later)
- Actually deploying (only readiness for Render is covered, §9)

---

## 2. Stages

1. **Java foundation:** project, settings, login/register/logout, shared layout and menu, dashboard, Flyway on the existing database, README for adding a module. After this stage the teammates can start Expense and Health in Java.
2. **School module in Java:** the sync API first, then saving, status, scheduling, class changes, and every page, each with its tests.
3. **Switch:** the Java website runs on port 5000 and the Python website code is removed (§7).

Each stage gets its own implementation plan; this document covers all three.

---

## 3. Project layout and technology

- A new folder **`web/`** holds a Maven project with the **Maven wrapper** (`web/mvnw`, `web/mvnw.cmd`), so nobody installs Maven. Java 17 (installed on the student's laptop: 17.0.12).
- **Spring Boot 4.1.1** (the current release; Spring Boot 4 splits its starters): `spring-boot-starter-webmvc`, `-thymeleaf` (with `thymeleaf-extras-springsecurity6`), `-security`, `-data-jpa`, `-validation`, `-flyway` + `flyway-mysql`, `mysql-connector-j`, `bcprov-jdk18on` 1.86 (BouncyCastle, for scrypt passwords, §4.2). Tests: the matching `-test` starters and `h2`.
- **Base package `vn.edu.hcmiu.sla`**, one package per module:
  - `core`: settings, security, layout/menu, current-user helper, time helpers, error pages
  - `auth`: the `users` table, register, login, logout
  - `main`: the dashboard at `/`
  - `school`: the School module (stage 2)
  - later `expense` and `health` (the teammates)
- `web/src/main/resources/templates/` holds `layout.html` and one folder per module (`auth/`, `main/`, `school/`, later `expense/`, `health/`).
- `web/src/main/resources/static/` holds the current `css/style.css` and `js/timetable.js`, copied unchanged (always light; FullCalendar 6.1.21 from jsDelivr with the same SRI hash).
- **Running:** `web\mvnw spring-boot:run` (Windows) or `./mvnw spring-boot:run` from `web/`. Port **8080** during stages 1–2 (the Python website keeps 5000); **5000** after the switch.

---

## 4. Foundation (stage 1)

### 4.1 Settings

- The Java website reads the **same `.env` file** as now, from the repository root (and from `web/` if present); real environment variables win. (`spring.config.import=optional:file:../.env[.properties],optional:file:.env[.properties]`.)
- **`DATABASE_URL`** keeps its current form (`mysql+pymysql://user:password@host:3306/db?charset=utf8mb4`). A small settings class turns it into a JDBC URL, user and password (percent-decoded, so `%40` becomes `@`). Missing `DATABASE_URL` stops the website with "Missing settings: DATABASE_URL. Copy .env.example to .env and fill them in."; more than one `@` stops it with the current hint about writing `@` as `%40`.
- `SECRET_KEY` stays in `.env` for the Python website; the Java website keeps sessions on the server and doesn't need it.
- **`SESSION_COOKIE_SECURE`**: the same meaning as now (HTTPS-only cookie unless set to `false` for `http://localhost`).
- **Time:** the database stores naive UTC. The JVM default time zone is set to UTC at startup and Hibernate uses `hibernate.jdbc.time_zone=UTC`; pages convert to Vietnam time (UTC+7) exactly as the current filters do (`vn_time`, `vn_clock`, `vn_date`, `day_label`, `money`, `score`).

### 4.2 Accounts and login

- **Pages and forms as now:**
  - `GET/POST /auth/register`: email (trimmed, lower-cased), display name, password (8–128 characters), confirm password
  - `GET/POST /auth/login`: email, password; after login, go to the page that was asked for (`next`) or `/`
  - `POST /auth/logout`
  - Same messages and validation errors as the current forms.
- **Passwords:** a `WerkzeugPasswordEncoder` checks the current hash format `scrypt:32768:8:1$<salt>$<hex>` (scrypt with N, r, p from the hash, 64-byte key, salt as UTF-8 text; also `pbkdf2:sha256:<iterations>$<salt>$<hex>`), and stores new passwords as `scrypt:32768:8:1$<16-character random salt>$<hex>`. So the student's current password works, and during stages 1–2 an account made on either website works on both.
- **Security:** Spring Security form login; every page needs login except `/auth/**`, static files, and `/api/school/sync/**` (device key, §5.1). CSRF token on every form (Thymeleaf adds it); the sync API is CSRF-exempt. Session cookie HttpOnly, SameSite=Lax, Secure per `SESSION_COOKIE_SECURE`.
- **Current user:** a helper gives the logged-in user's id. Rule for every module (README): every query filters by that id, and loading one row uses both id and owner (`findByIdAndUserId(...)`), so another user's row gives 404.

### 4.3 Layout, menu and dashboard

- `layout.html` (Thymeleaf fragment): header, menu, flash messages (success and error, as now), footer. Each page fills in only its own title and content.
- **Menu and dashboard cards** come from one list of modules (School, Expense, Health). A module's link appears when the module registers itself with one line (a `NavModule` bean with its label and path), the same idea as `NAV_MODULES` now.
- The site stays **always light** (no dark-mode rules), with the same `style.css`.

### 4.4 Database and migrations

- **Flyway** owns the tables from now on.
  - `V1__baseline.sql` creates exactly today's tables: `users` and every `school_…` table, with the same columns, types (scores `DOUBLE`), keys and indexes as after Alembic revision `7d2f4b9c1e30`. It is written so it runs on MySQL and on H2 in MySQL mode (tests).
  - `spring.flyway.baseline-on-migrate=true`, `baseline-version=1`: on the student's existing database Flyway records V1 as done and changes nothing; on an empty database (a teammate's laptop, tests) V1 creates everything. So the teammates don't need Python to run the website.
  - New tables come in migrations named by date, e.g. `V20261001_1__expense_tables.sql`; `spring.flyway.out-of-order=true` lets files that teammates made in parallel arrive in any order.
- JPA checks the tables at startup (`ddl-auto=validate`) and never changes them.
- **During stages 1–2 the Python website changes no tables**, so Alembic and Flyway never compete. The `alembic_version` table is dropped at the switch.

### 4.5 README: adding a module in Java

The README's "Adding your module" section is rewritten for Java, with Expense as the example:
1. Create package `vn.edu.hcmiu.sla.expense` with a controller: `@Controller @RequestMapping("/expense")`, pages as methods (`@GetMapping`), login required automatically.
2. Create the table as an entity class `Expense` (with a `userId`), a repository, and `V…__expense_tables.sql` for `expense_…` tables.
3. Create templates in `templates/expense/` that use the shared layout.
4. Register the menu entry: one `NavModule` bean.
The rules stay: URLs and tables start with the module name; every user table has `user_id` → `users.id`; every query is filtered by the current user.

### 4.6 Stage 1 tests

JUnit 5 with Spring's MockMvc, on H2 in MySQL mode with the Flyway migrations:
- register (success, duplicate email, short password, mismatched confirm), login (success, wrong password, `next`), logout, pages need login, CSRF required on forms
- a Werkzeug scrypt hash made by the Python website (fixed test value with a known password) is accepted; a new hash is accepted by Werkzeug (checked once by a Python snippet while building, recorded in the test's comment)
- settings: missing `DATABASE_URL`, the `@` hint, `%40` decoding
- layout: menu shows registered modules; dashboard cards
- migrations: V1 creates every table on an empty database; JPA validation passes

---

## 5. School module (stage 2)

### 5.1 Sync API: exactly as now

The laptop agent must not notice the change.
- `GET /api/school/sync/check`, `POST /api/school/sync/runs`, `POST /api/school/sync/runs/{id}/finish`
- Device key in `Authorization: Bearer <key>`; the key's SHA-256 is looked up among the user's devices that aren't revoked; the device's `last_seen_at` is updated on every call. Wrong, missing or revoked key → `401 {"error": "invalid_device_key"}`.
- Bodies up to **5 MB**; larger → `413 {"error": "payload_too_large"}`.
- Invalid body → `422 {"error": "invalid_payload", "details": [{"loc": [...], "msg": "..."}]}`, never echoing the input.
- `POST /runs` → `201 {"run_id": …}`; while another run of that user is still running → `409 {"error": "run_in_progress"}`.
- `POST /runs/{id}/finish` → `200 {"status": "success" | "partial" | "failed"}`; another user's or an unknown run → 404; a run that isn't running (finished twice) → `409 {"error": "run_not_running"}`.
- `GET /check` → `{"due": …, "reason": …, "interval_hours": …}`.
- Scheduling rules as now: interval hours (6/12/24), "Sync now" requests, a run older than 15 minutes counts as stuck, "Sync now" runs at least 5 minutes apart, failed scheduled runs retried at most hourly.

### 5.2 Data format in Java

- Java records mirror `contract/sla_contract/schema.py` (schema version 1): the same fields, limits (lengths, list sizes, ranges), unknown fields rejected, text trimmed, aware times, Blackboard links must start with `https://blackboard.hcmiu.edu.vn/`, error codes as listed there.
- **Shared samples:** `contract/samples/` holds example uploads (every section ok, parts failed, Blackboard, and invalid ones). The Python tests check them against the pydantic contract and the Java tests check them against the Java records, so the two sides can't drift.

### 5.3 Behaviour ported unchanged

- **Saving** a finished run: each part that arrived replaces its rows for that term; a failed part keeps its old rows; the "What changed" lines are made by the same rules (timetable, exams, tuition, Blackboard, one line per new course and per batch of materials).
- **Status:** the headline and detail texts, laptop warning, one line per system (EduSoft, Blackboard), paused systems.
- **Devices:** a key shown once (`sla_…`, `Cache-Control: no-store`), stored only as a SHA-256 hash; rename; cancel (hidden from the list).
- **Schedule and calendar feed:** Vietnam day boundaries, the same JSON events (classes, exams, deadlines with ✓, changed classes with `url`), 62-day range limit.
- **Class changes:** the announcement reader, ported with every current test case. Java patterns use Unicode mode (`Pattern.UNICODE_CHARACTER_CLASS`, `CASE_INSENSITIVE`, `UNICODE_CASE`) and `Normalizer.Form.NFC`.
- **Pages, same content and look:** Overview (status card, Today/Tomorrow with change tags, To submit, Latest announcements, Next exam, What changed), Timetable (calendar), Courses and course page, Exams, Tuition (IUPay link), Devices; school sub-menu; `/school/sync-now`.

### 5.4 Stage 2 tests

Each current website test (about 245, in `tests/`) gets a Java twin with the same inputs and expected results, including every class-changes reader case, every calendar-feed case, the per-user isolation tests, and the API's 401/404/409/413/422 cases.

---

## 6. Changeover rules (stages 1–2)

- Both websites use the same database. The Python website changes no tables; the Java website adds tables only through Flyway (`V2…`).
- The laptop agent keeps syncing into the Python website (port 5000) until the switch.
- An account or device made on either website works on both.

---

## 7. The switch (stage 3)

Only when all of these hold:
- every Java test passes;
- a **real sync** from the student's laptop into the Java website works (Java on port 5000, `sla-agent sync-now`), and the Overview, Timetable (including the 4 real class changes) and Courses show the student's data correctly;
- a browser check in Edge of every page at 1400×1000 and 390×844, with the device in dark and light mode.

Then remove the Python website: `app/`, the website tests in `tests/`, `migrations/`, `wsgi.py`, `.flaskenv`, and the Flask packages from the requirements; drop the `alembic_version` table (a Flyway `V…__drop_alembic_version.sql`). Keep `agent/`, `contract/` (the agent's data format and the shared samples), and the agent's requirements. Rewrite the README for Java (running, tests, adding a module) and the laptop agent section. The agent's saved server address (`http://localhost:5000`) stays valid.

---

## 8. GitHub checks

On every pull request: the Java tests (`web/mvnw test`) on H2, the same tests once on a real MySQL service, and the laptop agent's Python tests (`pytest agent/tests`), plus the shared-sample check on both sides.

---

## 9. Going online later (readiness only)

- Render's free plan runs the Java website from a small `Dockerfile` (build with the Maven wrapper, run with Java 17; JVM limited to about 300 MB to fit the free plan's 512 MB).
- A sleeping site wakes more slowly than the Python one (about 10–20 seconds).
- Aiven MySQL stays; Flyway creates the tables on the empty online database.

---

## 10. What the teammates install

Java 17 (Temurin), MySQL 8, and the `.env` file (only `DATABASE_URL` is needed by the Java website). Python only if they want to run the laptop agent for their own EduSoft and Blackboard.

---

## 11. Risks

- **Size of stage 2** (about 2,000 lines, 15 templates, 245 tests): the Java twins of every test show what is missing.
- **Pattern differences between Python and Java** (word boundaries with Vietnamese letters, lookbehinds): Unicode mode and the full reader test list.
- **Time zones:** UTC fixed in the JVM and Hibernate; the calendar and Overview tests catch a 7-hour slip.
- **Two migration tools on one database** during stages 1–2: the "Python changes no tables" rule; the switch drops Alembic's table.
- **Free-plan memory on Render:** JVM memory limit; checked when deployment is planned.
