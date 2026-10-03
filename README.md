# School-Life-Assistant

One web app for IU students, built by a team of 3 for the Web Application Development course:

- **School** (Vy): EduSoft timetable and exams, IUPay tuition bills, Blackboard courses, and your own events (once or repeating, with conflict checks), synced automatically from a laptop, on one calendar.

Design: [the website](docs/superpowers/specs/2026-09-26-java-website-design.md) and [the School sync](docs/superpowers/specs/2026-09-25-edusoft-first-phase1-design.md).

Stack: Java 17 and Spring Boot with Thymeleaf pages, MySQL 8, a little JavaScript. The laptop sync agent for the School module is written in Python.

## Install on your laptop (students)

1. Open the download page, https://nguyenkhangvy.github.io/School-Life-Assistant/ (English or Tiếng Việt), and press **Download for Windows**. School → Devices on the website has the same button.
2. Open the downloaded `School-Life-Assistant.exe`. If the browser warns about it, keep it; if Windows says “Windows protected your PC”, press **More info**, then **Run anyway**. It needs no administrator rights.
3. The School-Life-Assistant window walks you through four steps:
   1. Connect to the website. It shows how to get a device key from School → Devices, and fills the key in when you copy it.
   2. EduSoft.
   3. Blackboard, if you want it.
   4. Outlook (classic), if you want it. If Outlook (classic) isn't on your laptop, it shows how to install it.

   At the end it asks whether to put an icon on your Desktop, then opens School-Life-Assistant as an app.

That's all. It syncs by itself every minute, and it installs new versions by itself within a day of their release.
- **School-Life-Assistant**, in the Start menu (and on the Desktop if you chose the icon), opens it again: the website, in its own window.
- **School-Life-Assistant Accounts**, in the Start menu, changes your accounts. It can also add the Desktop icon later.

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

## Putting the site online

Online, the site is https://school-life-assistant.duckdns.org. It runs from `web/Dockerfile` on an AWS Lightsail server, with its database on Aiven's free MySQL. Students then only need the link: no Java, no MySQL. This is done once.

1. **The database.** On aiven.io, create a MySQL service on the **Free** plan (it only lets you pick an area: choose **Asia Pacific**), wait until it says *Running*, and copy its **Service URI** (`mysql://avnadmin:…@…/defaultdb?ssl-mode=REQUIRED`). Don't pick a paid plan: it only runs while Aiven's trial credit lasts. If the password itself has an `@`, write that one as `%40`; the `@` before the server's name stays.
2. **The site.** On an AWS Lightsail server, as in "Always on: AWS Lightsail" below. Render runs the same site for free with no server to look after, but it sleeps ("Or for free: Render").
3. **The laptops.** Each student creates an account on the online site and installs School-Life-Assistant from School → Devices (see "Install on your laptop"). A laptop that runs the agent from source enters the online address (**Change** on the setup's first page) and a device key from School → Devices, or, when already set up, presses **Change** next to Website in Accounts.

### Always on: AWS Lightsail

A Lightsail server never sleeps, so the site opens at once. Caddy gives it HTTPS, at a free DuckDNS address. The settings are in `deploy/oracle`, named after Oracle Cloud, which was tried first: nothing in it is Oracle's, and it runs on any Ubuntu server. This is done once, in about an hour.

1. **The account.** Sign up at aws.amazon.com and choose the **Free plan**: AWS gives $100–200 of credit and doesn't charge the card, but closes the account after 6 months (see "AWS's limits" below). The card must be a Visa or Mastercard that allows international online payments; AWS takes $1 to check it and gives it back.
2. **The server.** Open Lightsail, check that the region at the top says **Singapore** (near Aiven's database), and **Create instance**: **Linux/Unix**, **OS Only → Ubuntu 24.04 LTS**, network **Dual-stack**, size **$12** (2 GB of memory). Leave automatic snapshots off: the data is on Aiven, not on the server.
3. **A fixed IP and the HTTPS port.** On the instance's **Networking** tab, create a **static IP** and attach it to the instance (free while attached); without one, the IP changes when the server restarts. Under the IPv4 firewall, **Add rule**: application **HTTPS**, from any IP address. SSH and HTTP are open already; Caddy needs both 80 and 443.
4. **The address.** On duckdns.org, sign in, add a subdomain (this site's is `school-life-assistant`), type the static IP in its **current ip** box and press **update ip**. DuckDNS fills in the IP of the computer you're on, not the server's: `nslookup <name>.duckdns.org` should answer with the static IP.
5. **Docker and the code.** On the instance's page, **Connect using SSH** opens a terminal on the server. Paste one line at a time: several lines pasted at once can arrive garbled.

   ```bash
   curl -fsSL https://get.docker.com | sudo sh
   sudo usermod -aG docker ubuntu
   exit
   ```

   Connect again (so `docker` works without `sudo`), then:

   ```bash
   git clone --branch main https://github.com/nguyenkhangvy/School-Life-Assistant.git
   cd School-Life-Assistant/deploy/oracle
   cp .env.example .env
   nano .env
   ```

   (`--branch main`: GitHub's default branch for this repository isn't `main`.) Write the DuckDNS address after `SITE_ADDRESS=` and the Service URI after `DATABASE_URL=`, keeping both names and their `=`, then save with Ctrl+O, Enter, Ctrl+X. `cat .env` should show the two lines.
6. **Start it.** `docker compose up -d --build`. The build takes a few minutes; then the address opens the site. For the first minute Caddy answers *502* while the site starts. If it stays like that, `docker compose logs caddy` (the HTTPS certificate: usually port 443 not open, or DuckDNS pointing elsewhere) and `docker compose logs web` (the site: usually a wrong `DATABASE_URL`) say why.
7. **Your laptop.** On the new site, create a device key in School → Devices; then in School-Life-Assistant's Accounts, press **Change** next to Website and enter the new address and the key.
8. **Backups.** Run `crontab -e` (choose nano if asked) and add this line. Every night at 02:00 in Vietnam (19:00 on the server's UTC clock) it saves the database in `~/sla-backups`, keeping the last 14:

   ```
   0 19 * * * bash ~/School-Life-Assistant/deploy/oracle/backup.sh >> ~/sla-backups.log 2>&1
   ```

   Run `bash ~/School-Life-Assistant/deploy/oracle/backup.sh` once now: it should end with `Saved …`. The top of `backup.sh` says how to restore one.

**New versions** don't go online here by themselves: once a merge to `main` has passed GitHub's tests, run `bash ~/School-Life-Assistant/deploy/oracle/update.sh` on the server. It builds the new version while the old one keeps running, then restarts the site, which logs everyone out.

**AWS's limits.** The Free plan ends 6 months after the account was opened, or sooner if the credit runs out. AWS then closes the account and the site goes offline; it keeps everything for 90 days in case you switch to the paid plan, then deletes it. On the paid plan this server costs $12 a month. Lightsail charges for a server as long as it exists, even when stopped (only deleting it stops that), and for a static IP that isn't attached. The data is on Aiven either way.

### Or for free: Render

Render's free plan runs the same site from `web/Dockerfile`, with no server to look after; `render.yaml` holds its settings. On render.com, sign in with GitHub, choose **New → Blueprint**, and pick this repository and the `main` branch. Render reads `render.yaml` and asks for `DATABASE_URL`: paste the Service URI. The first build takes about 10 minutes; Render then shows the site's address (`https://….onrender.com`). On the empty database the site creates every table.

**New versions** go online by themselves: merge a pull request into `main`, and once GitHub's tests pass, Render builds the new version and switches to it (about 10 minutes). Table changes are applied when it starts, and everyone gets the new version the next time they load a page.

**Free plan limits.** After 15 minutes without visits the site sleeps, and the next visit waits about a minute while it wakes up. A restart or a new version logs everyone out.

## Making a release

A release puts a new `School-Life-Assistant.exe` on GitHub's Releases page. School → Devices downloads it, and every installed laptop updates itself to it within a day.

1. In a pull request, raise `__version__` in `agent/sla_agent/__init__.py` (for example to `0.3.0`), and merge it.
2. Tag the merge on `main` and push the tag to origin (only origin builds releases):

   ```powershell
   git switch main
   git pull
   git tag v0.3.0
   git push origin v0.3.0
   ```

   GitHub Actions (`release`) builds the app and the setup, starts them, and publishes `School-Life-Assistant.exe` and `SHA256SUMS.txt`. If the tag isn't `v` plus `__version__`, nothing is published.
3. Before telling classmates, upload `School-Life-Assistant.exe` to virustotal.com. If Microsoft Defender flags it, report it as a false positive at microsoft.com/wdsi/filesubmission; that usually clears in a day or two.

To build on your own laptop: `pip install -r agent/packaging/requirements.txt`, then `python agent/packaging/build.py`. `agent/packaging/smoke-test.ps1` starts what it built.

### The download page

`pages/` is the download page, https://nguyenkhangvy.github.io/School-Life-Assistant/, in English and Vietnamese. GitHub Actions (`download page`) publishes it whenever a merge to `main` changes `pages/`, or when started by hand from the Actions tab. Once, before the first publish: on GitHub, **Settings → Pages → Source: GitHub Actions**. Until then the workflow fails and says Pages isn't enabled. Screenshots go in `pages/img/`; `pytest` checks that every image the page shows is there and that both languages say the same things.

---

## The laptop agent from source (developers)

Students install the agent from School → Devices (see "Install on your laptop"); this section runs it from the source code, to work on it. It runs on your laptop, keeps your passwords in Windows Credential Manager, reads EduSoft and Blackboard there, and uploads only your timetable, exams, IUPay tuition bills and Blackboard courses to the site, with a device key. It checks every minute: new mail in an open Outlook reaches Mailbox within about a minute, and everything else syncs every 30 minutes. After moving the project folder, run `sla-agent schedule` once. IUPay needs only your student ID: the agent makes the same requests as IUPay's search page and keeps only the bills.

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

3. **Set it up:** double-click `School-Life-Assistant.cmd` in the project folder. The setup pages open, with the web app address `http://localhost:5000`: paste the device key (step 1), enter your EduSoft student ID and password (step 2), then your Blackboard login and the Outlook account to read, or skip them (steps 3 and 4). Each login is checked once, and nothing is saved if one is wrong. Step 2 turns on automatic sync (every minute while you're logged in to Windows; a full sync every 30 minutes, or soon after you press "Sync now") and adds School-Life-Assistant to the Start menu; the last page asks whether to add it to your Desktop too.

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
- Merging to `main` puts the new version online (see "Putting the site online").
- Never commit `.env`, passwords or keys.
