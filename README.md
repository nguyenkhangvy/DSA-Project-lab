# School-Life-Assistant

One web app for IU students, built by a team of 3 for the Web Application Development course:

- **School** (Vy): EduSoft timetable and exams, IUPay tuition bills, Blackboard courses, and your own events (once or repeating, with conflict checks), synced automatically from a laptop, on one calendar.

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
| After renaming or deleting a migration or a Java class (yours or pulled) | `.\mvnw.cmd clean`, or the old copy stays in `target/`; a deleted entity left there stops the site from starting |
| Run the laptop agent's tests (project folder, agent installed) | `pytest` |

Git Bash works too: `cd web && ./mvnw spring-boot:run`. GitHub runs the website's tests on an in-memory database and on MySQL, and the agent's tests, for every pull request.

---

## The laptop agent (School sync)

Only needed to sync your own EduSoft and Blackboard into the School pages. It runs on your laptop, keeps your passwords in Windows Credential Manager, reads EduSoft and Blackboard there, and uploads only your timetable, exams, IUPay tuition bills and Blackboard courses to the site, with a device key. It checks every minute: new mail in an open Outlook reaches Mailbox within about a minute, and everything else syncs every 30 minutes. After moving the project folder, run `sla-agent schedule` once. IUPay needs only your student ID: the agent makes the same requests as IUPay's search page and keeps only the bills.

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

3. **Set it up:** double-click `School-Life-Assistant.cmd` in the project folder. A window opens: the web app address is filled in (`http://localhost:5000`); paste the device key, enter your EduSoft student ID and password, and if you like your Blackboard login and the Outlook account to read, then press **Check and save**. Each login is checked once, and nothing is saved if one is wrong. It then turns on automatic sync (every minute while you're logged in to Windows; a full sync every 30 minutes, or soon after you press "Sync now") and adds School-Life-Assistant to your Desktop and Start menu.

**Change an account later:** open School-Life-Assistant from the Start menu, or press **Open Accounts on this laptop** on School → Accounts, then press **Change** next to the account. A wrong new password changes nothing. Prefer the terminal? `sla-agent setup` still works. Set up before this window existed? Double-click `School-Life-Assistant.cmd` once: it adds the Start menu entry and makes the website's button work.

| What | Command |
|---|---|
| Open the School-Life-Assistant window | `sla-agent window` |
| Sync right away | `sla-agent sync-now` |
| See the last result | `sla-agent status` |
| Set up or change the Blackboard login | `sla-agent setup --blackboard` |
| Read your Inbox through classic Outlook | `sla-agent setup --outlook` |
| Remove the saved passwords, key, schedule, the `sla-mail:` and `sla-agent:` link types and the shortcuts | `sla-agent forget` |

**Mailbox (Outlook).** IU doesn't let students approve apps that read mail, so the agent reads your Inbox through the classic Outlook app on your laptop (Windows only). Open **Outlook (classic)**, sign in with your IU account, wait for "All folders are up to date", then choose your account in Accounts (or run `sla-agent setup --outlook`). Each sync then reads your Inbox since the start of the semester, sorts every email on your laptop, and uploads only the results (sender, subject, time, categories, dates, event times, class changes), **never the text**. School → Mailbox shows them, one row per email with its category first. Clicking the subject opens the email in Outlook on this laptop (Edge asks once to open `sla-agent`), and **Web ↗** opens Outlook on the web anywhere. Opening an email marks it Done, unless it is an event or school task still ahead; untick "Mark emails as done when I open them" to press ✓ Done yourself. Events show the times the laptop found in them, each marked **Conflict** or **No conflict** against your timetable, and **Join…** puts the sessions you pick into your Timetable. A check-in time counts as part of its event, and an event whose registration has closed moves to Past unless you joined it. If Outlook shows a security warning or blocks the agent, the agent never clicks past it; Mailbox says so at the top.

### The laptop agent's data format

The laptop agent uploads its data in the format set by `contract/sla_contract/schema.py`. The Java site reads it with `web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncContract.java`, so change the two together. `contract/samples/` holds example uploads that both the Python and the Java tests check: every file there must be accepted, every file in `contract/samples/invalid/` refused. When the format changes, update or add a sample.

`contract/samples/class-changes/sentences.json` lists example announcements and the class changes each must give. The agent's Python reader (lecturers' emails) and the site's Java reader (Blackboard announcements) both check every one, so the two stay the same.

---

## Rules for the Java code

1. URLs start with the module name (`/school/...`), tables with the module name (`school_...`).
2. Every table with user data has `user_id` → `users (id)`.
3. Every query is filtered by the logged-in user (`@AuthenticationPrincipal AppUser user`, then `user.id()`). To load one row, use both id and owner, so another user's row gives 404:

   ```java
   SchoolBbCourse course = bbCourses.findByIdAndUserId(courseId, user.id())
           .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
   ```

4. Forms use `th:action="@{/school/...}"`, which adds the security code (CSRF) by itself. After a change, redirect and show a message with `Flash.success(redirect, "Saved.")`.
5. Changing a table means a new migration file in `web/src/main/resources/db/migration/`; never edit a migration that is already on `main`. Name it `V<date>_1_<number>__<what>.sql` (1 is the School module): `V20261001_1_1__new_table.sql`, then `…_1_2__…`, `…_1_3__…` for more that day. `MigrationNamingTest` checks every name.

---

## Team workflow

- Work on a branch, open a pull request, and get one teammate's review before merging to `main`.
- The tests must pass (GitHub shows a green check on the pull request).
- Never commit `.env`, passwords or keys.
