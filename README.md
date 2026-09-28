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
| Read your Inbox through classic Outlook | `sla-agent setup --outlook` |
| Remove the saved passwords, key, schedule and `sla-mail:` link type | `sla-agent forget` |

**Mailbox (Outlook).** IU doesn't let students approve apps that read mail, so the agent reads your Inbox through the classic Outlook app on your laptop (Windows only). Open **Outlook (classic)**, sign in with your IU account, wait for "All folders are up to date", then run `sla-agent setup --outlook`. Each sync then reads your Inbox since the start of the semester, sorts every email on your laptop, and uploads only the results (sender, subject, time, categories, dates, event times, class changes), **never the text**. School → Mailbox shows them, one row per email with its category first. Clicking the subject opens the email in Outlook on this laptop (Edge asks once to open `sla-agent`), and **Web ↗** opens Outlook on the web anywhere. Opening an email marks it Done, unless it is an event or school task still ahead; untick "Mark emails as done when I open them" to press ✓ Done yourself. Events show the times the laptop found in them, each marked **Conflict** or **No conflict** against your timetable, and **Join…** puts the sessions you pick into your Timetable. A check-in time counts as part of its event, and an event whose registration has closed moves to Past unless you joined it. If Outlook shows a security warning or blocks the agent, the agent never clicks past it; Mailbox says so at the top.

### The laptop agent's data format

The laptop agent uploads its data in the format set by `contract/sla_contract/schema.py`. The Java site reads it with `web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncContract.java`, so change the two together. `contract/samples/` holds example uploads that both the Python and the Java tests check: every file there must be accepted, every file in `contract/samples/invalid/` refused. When the format changes, update or add a sample.

`contract/samples/class-changes/sentences.json` lists example announcements and the class changes each must give. The agent's Python reader (lecturers' emails) and the site's Java reader (Blackboard announcements) both check every one, so the two stay the same.

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
