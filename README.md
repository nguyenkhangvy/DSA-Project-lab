# School-Life-Assistant

One web app for IU students, built by a team of 3 for the Web Application Development course:

- **School** (Vy): EduSoft timetable, exams and tuition, synced automatically, on one calendar.
- **Expense**: expense management.
- **Health**: health management.

Design: [docs/superpowers/specs/2026-09-25-edusoft-first-phase1-design.md](docs/superpowers/specs/2026-09-25-edusoft-first-phase1-design.md)

Stack: Python 3.12, Flask, Jinja templates, MySQL 8, a little JavaScript.

---

## First-time setup (Windows)

1. **Get the code** (skip this if you already have the project folder):

   ```powershell
   git clone https://github.com/nguyenkhangvy/School-Life-Assistant.git
   cd School-Life-Assistant
   ```

   **Then, inside the project folder, install the libraries:**

   ```powershell
   py -3.12 -m venv .venv
   .venv\Scripts\activate
   pip install -r requirements-dev.txt
   ```

   If `activate` fails with "running scripts is disabled on this system", run
   `Set-ExecutionPolicy -Scope Process -ExecutionPolicy RemoteSigned` first (it only affects that window).
   Your prompt starts with `(.venv)` once it worked.

2. **Create your local MySQL database.** Open a MySQL prompt with `mysql -u root -p`, then run the following, using a password of your own:

   ```sql
   CREATE DATABASE school_life CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
   CREATE USER 'sla_app'@'localhost' IDENTIFIED BY 'pick-your-own-password';
   GRANT ALL PRIVILEGES ON school_life.* TO 'sla_app'@'localhost';
   ```

3. **Create your settings file.** Copy `.env.example` to `.env` (`copy .env.example .env`), then:
   - set `SECRET_KEY` to the output of `python -c "import secrets; print(secrets.token_hex(32))"`
   - put your database password in `DATABASE_URL`

   `.env` is in `.gitignore`. **Never commit it.**

4. **Create the tables**, then **start the site**:

   ```powershell
   flask db upgrade
   flask run
   ```

   Open http://localhost:5000, create an account and log in.

## Everyday commands

| What | Command |
|---|---|
| Start the site | `flask run` (add `--debug` to reload on every change) |
| Run the tests | `pytest` |
| Run the tests on your MySQL | `$env:TEST_DATABASE_URL="mysql+pymysql://sla_app:...@localhost:3306/school_life_test?charset=utf8mb4"; pytest` (needs a separate, empty `school_life_test` database; the tests delete its tables) |
| After pulling new code | `pip install -r requirements-dev.txt` and `flask db upgrade` |
| Set up or change the Blackboard login | `sla-agent setup --blackboard` |

The tests use a throwaway in-memory database by default, so they never touch your real data. GitHub runs them again on real MySQL for every pull request.

---

## The Java website (`web/`)

The website is moving to Java (Spring Boot), so the whole team can work in Java. See [the design](docs/superpowers/specs/2026-09-26-java-website-design.md). Until the switch, the Python site above is the one in daily use; the Java site runs next to it on port **8080**, on the same database and `.env`. So far it has login, the shared layout, and the School module's sync API for the laptop agent; the School pages come next.

### What you need

- **Java 17** (Temurin). `java -version` should say 17.
- **MySQL 8** and the **`.env`** file from "First-time setup". The Java site only needs `DATABASE_URL`, plus `SESSION_COOKIE_SECURE=false` on your laptop. You don't need Python for the website.

### Run it

PowerShell:

```powershell
cd web
.\mvnw.cmd spring-boot:run
```

Git Bash: `cd web && ./mvnw spring-boot:run`. Open http://127.0.0.1:8080 and stop it with Ctrl+C. The first run downloads Maven and the libraries (a few minutes). On an empty database the site creates every table itself.

### Tests

In `web/`: `.\mvnw.cmd test` (PowerShell) or `./mvnw test` (Git Bash). They use an in-memory database, never yours.

### The laptop agent's data format

The laptop agent uploads its data in the format set by `contract/sla_contract/schema.py`. The Java site reads it with `web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncContract.java`, so change the two together. `contract/samples/` holds example uploads that both the Python and the Java tests check: every file there must be accepted, every file in `contract/samples/invalid/` refused. When the format changes, update or add a sample.

### Adding your module in Java

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
5. During the changeover, the Python side changes no tables (no `flask db migrate`).

---

## Adding your module

Each module is a Flask **Blueprint** in its own folder. Example for Expense:

1. **Create `app/expense/__init__.py`** (empty) and **`app/expense/routes.py`**:

   ```python
   from flask import Blueprint, render_template
   from flask_login import login_required

   bp = Blueprint("expense", __name__, url_prefix="/expense")


   @bp.route("/")
   @login_required
   def index():
       return render_template("expense/index.html")
   ```

2. **Register it** in `create_app()` in `app/__init__.py`, next to the others:

   ```python
   from app.expense.routes import bp as expense_bp
   app.register_blueprint(expense_bp)
   ```

   The menu link and the dashboard card turn on by themselves (see `NAV_MODULES` in `app/__init__.py`).

3. **Templates** go in `app/templates/expense/` and start with `{% extends "base.html" %}`.

### Rules every module follows

1. **URLs** start with the module name: `/school/...`, `/expense/...`, `/health/...`.
2. **Table names** start with the module name: `school_...`, `expense_...`, `health_...`.
3. **Every table** holding user data has `user_id = mapped_column(ForeignKey("users.id"), nullable=False)`.
4. **Every page** has `@login_required` and only shows the current user's rows. To load one row, filter by both id and owner so another user's row gives 404:

   ```python
   item = db.first_or_404(select(Expense).filter_by(id=expense_id, user_id=current_user.id))
   ```

5. **Every form** is a `FlaskForm` and its template contains `{{ form.hidden_tag() }}` (CSRF protection). A hand-written `<form method="post">` needs `<input type="hidden" name="csrf_token" value="{{ csrf_token() }}">`.
6. **Times** are stored in UTC (`app.timeutil.utcnow()`) and shown in Vietnam time.
7. **Changing data** (create/edit/delete) only happens on POST, never on a plain link.

### Changing the database

1. `git pull` on `main` first.
2. Change or add your models. Make sure the models file is imported (for example from your `routes.py`), or the migration won't see them.
3. `flask db migrate -m "add expense table"`, read the new file in `migrations/versions/`, then `flask db upgrade`.
4. Commit the migration file together with the model change. A test fails if a model and the migrations don't match.
5. If you get "multiple heads" after merging, run `flask db merge heads -m "merge"` and commit the result.

## Team workflow

- Work on a branch, open a pull request, and get one teammate's review before merging to `main`.
- The tests must pass (GitHub shows a green check on the pull request).
- Never commit `.env`, passwords or keys.
