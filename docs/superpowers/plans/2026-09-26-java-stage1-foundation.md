# Java Website Stage 1 (Foundation) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Spring Boot website in `web/` with login, register, logout, the shared layout and menu, the dashboard, and Flyway on the existing MySQL, so the teammates can start Expense and Health in Java.

**Architecture:** A Maven project (with the Maven wrapper) next to the Python site, on the same MySQL database and `.env`. Flyway's `V1__baseline.sql` describes today's tables and is only recorded as done on the student's database. Passwords use Werkzeug's scrypt format, so accounts work on both sites during the changeover.

**Tech Stack:** Java 17, Spring Boot 4.1.1 (Spring MVC, Thymeleaf + Spring Security dialect, Spring Security 7, Spring Data JPA / Hibernate 7, Flyway, Bean Validation), MySQL Connector/J, BouncyCastle 1.86 (scrypt), JUnit 5 + MockMvc, H2 (tests only).

**Spec:** docs/superpowers/specs/2026-09-26-java-website-design.md (stage 1: §3, §4, §8, §10)

**Notes against the spec:** "go to the page that was asked for" (§4.2) is Spring Security's saved request (it redirects to `/that/page?continue`), not a `?next=` address; it only ever returns to this site. The Vietnam-time display helpers (§4.1) come in stage 2 with the School pages, their first user. Migrations are named by date with `spring.flyway.out-of-order=true` (so two teammates' files never collide); Task 6 updates the spec to say so.

The code in this plan was run as a prototype before the plan was written: all 36 tests passed, a password hash made by the Java code was accepted by Werkzeug (and the reverse), and the site served login → register → dashboard over HTTP.

## Global Constraints

- Java 17; Spring Boot **4.1.1** (the Maven version string is `4.1.1`, not `4.1.1.RELEASE`); base package `vn.edu.hcmiu.sla`; everything under `web/`.
- The Python site (`app/`, `tests/`, `migrations/`, `agent/`, `contract/`) is not changed, and no Python code changes any table.
- Port **8080** (the Python site keeps 5000).
- The same `.env` in the repository root; the Java site needs only `DATABASE_URL` (plus `SESSION_COOKIE_SECURE=false` on localhost).
- The site is always light: `style.css` is copied unchanged from `app/static/css/style.css`; never add `prefers-color-scheme: dark` rules.
- Passwords: read `scrypt:N:r:p$salt$hex` and `pbkdf2:sha256:iterations$salt$hex`; write `scrypt:32768:8:1$<16 letters/digits>$<128 hex>`.
- Tests never touch the real database: `web/src/test/resources/config/application.properties` points them at an in-memory H2 database in MySQL mode.
- Run Maven from `web/`: `./mvnw -B test` in Git Bash, or `.\mvnw.cmd test` in PowerShell. Read and write files as UTF-8.

## Review Focus

1. **A `.env` saved on Windows** (spaces, quotes, a carriage return at the end of the line) must still give a working `DATABASE_URL`. Tests: Task 1 (`quotesAroundTheValueAreIgnored`, `spacesAndAWindowsLineEndAroundTheValueAreIgnored`).
2. **An email typed with capitals or spaces** must reach the same account at register and at login. Tests: Task 4 (`anAccountFromThePythonSiteCanLogIn`), Task 5 (`registeringLogsYouInAndSavesAWerkzeugStylePassword`).
3. **Too-long email or display name** must give the form's message, not a database error. Test: Task 5 (`tooLongFieldsGetAFormMessageNotADatabaseError`).
4. **A password with Vietnamese letters** must work on both sites. Tests: Task 3 (`vietnameseLettersInAPasswordAreReadTheSameWay` + the Werkzeug cross-check step), Task 4 (`aVietnamesePasswordWorksEndToEnd`).
5. **The student's existing database** must be recorded as the baseline, never recreated or changed. Check: Task 6, Step 5 (one BASELINE row in `flyway_schema_history`, the row counts unchanged).

---

## File Structure

- `web/pom.xml`, `web/mvnw`, `web/mvnw.cmd`, `web/.mvn/wrapper/maven-wrapper.properties`, `web/.gitignore`, `web/.gitattributes`: the Maven project (Task 1)
- `web/src/main/java/vn/edu/hcmiu/sla/`
  - `SlaWebApplication.java`: starts the site; UTC as the JVM time zone (Task 1)
  - `core/DatabaseUrl.java`, `core/DatabaseSettings.java`: `.env`'s `DATABASE_URL` → the database connection (Task 1)
  - `core/SecurityConfig.java`: which pages need login, the login form, logout, the password format (Task 4)
  - `core/NavModule.java`, `core/Navigation.java`: the menu (Task 4)
  - `core/Flash.java`: one-time messages (Task 4)
  - `auth/User.java`, `auth/UserRepository.java`: the `users` table (Task 2)
  - `auth/WerkzeugPasswordEncoder.java`: passwords in Werkzeug's format (Task 3)
  - `auth/AppUser.java`, `auth/AppUserDetailsService.java`: the logged-in user (Task 4)
  - `auth/AuthController.java`: the login page (Task 4), register (Task 5)
  - `auth/RegisterForm.java`: the register form's fields and rules (Task 5)
  - `main/HomeController.java`: the dashboard (Task 4)
- `web/src/main/resources/`
  - `application.properties`, `META-INF/spring.factories` (Task 1)
  - `db/migration/V1__baseline.sql` (Task 2)
  - `templates/layout.html`, `templates/auth/login.html`, `templates/main/index.html`, `templates/error.html`, `static/css/style.css` (Task 4)
  - `templates/auth/register.html` (Task 5)
- `web/src/test/`: `resources/config/application.properties` (Task 2) and one test class per unit
- `README.md`, `.github/workflows/ci.yml`, the spec (Task 6)

---

### Task 1: Project skeleton and settings

**Files:**
- Create: the Maven project in `web/` (generated), `web/pom.xml` (replaced), `web/src/main/java/vn/edu/hcmiu/sla/SlaWebApplication.java` (replaced), `web/src/main/java/vn/edu/hcmiu/sla/core/DatabaseUrl.java`, `web/src/main/java/vn/edu/hcmiu/sla/core/DatabaseSettings.java`, `web/src/main/resources/META-INF/spring.factories`, `web/src/main/resources/application.properties` (replaced)
- Test: `web/src/test/java/vn/edu/hcmiu/sla/core/DatabaseUrlTest.java`, `web/src/test/java/vn/edu/hcmiu/sla/core/DatabaseSettingsTest.java`

**Interfaces:**
- Produces: `record DatabaseUrl(String jdbcUrl, String username, String password)` with `static DatabaseUrl parse(String url)`; `DatabaseSettings` (an `EnvironmentPostProcessor`) with `static void apply(ConfigurableEnvironment)` that sets `spring.datasource.url/username/password` from `DATABASE_URL` unless `spring.datasource.url` is already set.

- [ ] **Step 1: Generate the project**

From the repository root (Git Bash):

```bash
curl -s -o web.zip "https://start.spring.io/starter.zip?type=maven-project&language=java&bootVersion=4.1.1.RELEASE&baseDir=web&groupId=vn.edu.hcmiu&artifactId=sla-web&name=sla-web&description=School-Life-Assistant%20website&packageName=vn.edu.hcmiu.sla&packaging=jar&javaVersion=17&dependencies=web,thymeleaf,security,data-jpa,validation,flyway,mysql,h2"
.venv/Scripts/python.exe -c "import zipfile; zipfile.ZipFile('web.zip').extractall('.')"
rm web.zip web/HELP.md web/src/test/java/vn/edu/hcmiu/sla/SlaWebApplicationTests.java
chmod +x web/mvnw
```

Expected: `web/` holds `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`, `.gitignore`, `.gitattributes`, `pom.xml`, `src/`.

Replace `web/pom.xml` with (the generator writes the version as `4.1.1.RELEASE`, which Maven Central doesn't have; this file fixes that, adds BouncyCastle, and keeps H2 for tests only):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
	xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
	<modelVersion>4.0.0</modelVersion>
	<parent>
		<groupId>org.springframework.boot</groupId>
		<artifactId>spring-boot-starter-parent</artifactId>
		<version>4.1.1</version>
		<relativePath/> <!-- lookup parent from repository -->
	</parent>
	<groupId>vn.edu.hcmiu</groupId>
	<artifactId>sla-web</artifactId>
	<version>0.0.1-SNAPSHOT</version>
	<name>sla-web</name>
	<description>School-Life-Assistant website</description>
	<properties>
		<java.version>17</java.version>
	</properties>
	<dependencies>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-data-jpa</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-flyway</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-thymeleaf</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-validation</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-webmvc</artifactId>
		</dependency>
		<dependency>
			<groupId>org.flywaydb</groupId>
			<artifactId>flyway-mysql</artifactId>
		</dependency>
		<dependency>
			<groupId>org.thymeleaf.extras</groupId>
			<artifactId>thymeleaf-extras-springsecurity6</artifactId>
		</dependency>

		<dependency>
			<groupId>org.bouncycastle</groupId>
			<artifactId>bcprov-jdk18on</artifactId>
			<version>1.86</version>
		</dependency>

		<dependency>
			<groupId>com.h2database</groupId>
			<artifactId>h2</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>com.mysql</groupId>
			<artifactId>mysql-connector-j</artifactId>
			<scope>runtime</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-data-jpa-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-flyway-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-thymeleaf-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-validation-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-webmvc-test</artifactId>
			<scope>test</scope>
		</dependency>
	</dependencies>

	<build>
		<plugins>
			<plugin>
				<groupId>org.springframework.boot</groupId>
				<artifactId>spring-boot-maven-plugin</artifactId>
			</plugin>
		</plugins>
	</build>

</project>
```

Replace `web/src/main/java/vn/edu/hcmiu/sla/SlaWebApplication.java` with:

```java
package vn.edu.hcmiu.sla;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SlaWebApplication {

    static {
        // The database stores times as UTC without a zone; pages convert them to Vietnam time.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    public static void main(String[] args) {
        SpringApplication.run(SlaWebApplication.class, args);
    }
}
```

- [ ] **Step 2: Write the failing tests**

`web/src/test/java/vn/edu/hcmiu/sla/core/DatabaseUrlTest.java`:

```java
package vn.edu.hcmiu.sla.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DatabaseUrlTest {

    @Test
    void theUsualLocalAddressBecomesAJdbcUrlUserAndPassword() {
        DatabaseUrl db = DatabaseUrl.parse("mysql+pymysql://sla_app:secret@localhost:3306/school_life?charset=utf8mb4");

        assertThat(db.jdbcUrl()).isEqualTo("jdbc:mysql://localhost:3306/school_life?characterEncoding=UTF-8");
        assertThat(db.username()).isEqualTo("sla_app");
        assertThat(db.password()).isEqualTo("secret");
    }

    @Test
    void percentCodesAreDecodedButAPlusStaysAPlus() {
        DatabaseUrl db = DatabaseUrl.parse("mysql+pymysql://sla_app:pa%40ss%2Bword+1@db.example.com:3306/sla");

        assertThat(db.password()).isEqualTo("pa@ss+word+1");
        assertThat(db.jdbcUrl()).isEqualTo("jdbc:mysql://db.example.com:3306/sla?characterEncoding=UTF-8");
    }

    @Test
    void anAddressWithoutAPortOrOptionsWorks() {
        assertThat(DatabaseUrl.parse("mysql://u:p@localhost/db").jdbcUrl())
                .isEqualTo("jdbc:mysql://localhost/db?characterEncoding=UTF-8");
    }

    @Test
    void quotesAroundTheValueAreIgnored() {
        assertThat(DatabaseUrl.parse("\"mysql+pymysql://u:p@localhost:3306/db\"").username()).isEqualTo("u");
    }

    @Test
    void spacesAndAWindowsLineEndAroundTheValueAreIgnored() {
        char carriageReturn = 13; // what a .env saved on Windows can leave at the end of a line
        DatabaseUrl db = DatabaseUrl.parse("  mysql+pymysql://u:p@localhost:3306/db?charset=utf8mb4 " + carriageReturn);

        assertThat(db.jdbcUrl()).isEqualTo("jdbc:mysql://localhost:3306/db?characterEncoding=UTF-8");
        assertThat(db.password()).isEqualTo("p");
    }

    @Test
    void aRawAtSignInThePasswordGetsTheSameHintAsThePythonSite() {
        assertThatThrownBy(() -> DatabaseUrl.parse("mysql+pymysql://sla_app:pass@word@localhost:3306/sla"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DATABASE_URL contains more than one '@'. If your database password has an '@' in it, "
                        + "write it as %40 in .env (for example, pass@word becomes pass%40word).");
    }

    @Test
    void anythingButMysqlIsRefusedWithAHint() {
        assertThatThrownBy(() -> DatabaseUrl.parse("sqlite:///school.db"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be a MySQL address");
    }
}
```

`web/src/test/java/vn/edu/hcmiu/sla/core/DatabaseSettingsTest.java`:

```java
package vn.edu.hcmiu.sla.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DatabaseSettingsTest {

    @Test
    void databaseUrlFromDotEnvBecomesTheConnection() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("DATABASE_URL", "mysql+pymysql://sla_app:secret@localhost:3306/school_life?charset=utf8mb4");

        DatabaseSettings.apply(env);

        assertThat(env.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:mysql://localhost:3306/school_life?characterEncoding=UTF-8");
        assertThat(env.getProperty("spring.datasource.username")).isEqualTo("sla_app");
        assertThat(env.getProperty("spring.datasource.password")).isEqualTo("secret");
    }

    @Test
    void aConnectionSetDirectlyWins() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:h2:mem:x")
                .withProperty("DATABASE_URL", "mysql+pymysql://u:p@localhost:3306/db");

        DatabaseSettings.apply(env);

        assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:h2:mem:x");
    }

    @Test
    void withoutDatabaseUrlTheSiteStopsWithAClearMessage() {
        assertThatThrownBy(() -> DatabaseSettings.apply(new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing settings: DATABASE_URL. Copy .env.example to .env and fill them in.");
    }
}
```

- [ ] **Step 3: Run them and watch them fail**

Run: `cd web && ./mvnw -B test`
Expected: compilation error, `cannot find symbol: class DatabaseUrl` / `DatabaseSettings`. (The first run downloads Maven and the libraries; it takes a few minutes.)

- [ ] **Step 4: Implement**

`web/src/main/java/vn/edu/hcmiu/sla/core/DatabaseUrl.java`:

```java
package vn.edu.hcmiu.sla.core;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * The database address from .env's DATABASE_URL, written the way the Python site writes it
 * (mysql+pymysql://user:password@host:3306/db?charset=utf8mb4), turned into what Java needs.
 */
public record DatabaseUrl(String jdbcUrl, String username, String password) {

    static final String MORE_THAN_ONE_AT =
            "DATABASE_URL contains more than one '@'. If your database password has an '@' in it, "
                    + "write it as %40 in .env (for example, pass@word becomes pass%40word).";
    static final String NOT_MYSQL =
            "DATABASE_URL must be a MySQL address such as mysql+pymysql://user:password@localhost:3306/school_life.";

    public static DatabaseUrl parse(String url) {
        String value = unquote(url.strip());
        int schemeEnd = value.indexOf("://");
        if (schemeEnd < 0 || !value.substring(0, schemeEnd).startsWith("mysql")) {
            throw new IllegalStateException(NOT_MYSQL);
        }
        String rest = value.substring(schemeEnd + 3);
        int slash = rest.indexOf('/');
        if (slash < 0) {
            throw new IllegalStateException(NOT_MYSQL);
        }
        String authority = rest.substring(0, slash);
        String database = rest.substring(slash + 1).split("\\?", 2)[0];
        if (authority.chars().filter(c -> c == '@').count() > 1) {
            // In scheme://user:password@host/db, '@' ends the password, so a raw '@' inside it breaks the host.
            throw new IllegalStateException(MORE_THAN_ONE_AT);
        }
        String username = "";
        String password = "";
        String host = authority;
        int at = authority.indexOf('@');
        if (at >= 0) {
            String userInfo = authority.substring(0, at);
            host = authority.substring(at + 1);
            int colon = userInfo.indexOf(':');
            username = decode(colon < 0 ? userInfo : userInfo.substring(0, colon));
            password = colon < 0 ? "" : decode(userInfo.substring(colon + 1));
        }
        if (host.isEmpty() || database.isEmpty()) {
            throw new IllegalStateException(NOT_MYSQL);
        }
        return new DatabaseUrl("jdbc:mysql://" + host + "/" + database + "?characterEncoding=UTF-8", username, password);
    }

    /** Percent-decoding only: a '+' in a password stays a '+'. */
    private static String decode(String text) {
        return URLDecoder.decode(text.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static String unquote(String text) {
        boolean quoted = text.length() >= 2
                && (text.startsWith("\"") && text.endsWith("\"") || text.startsWith("'") && text.endsWith("'"));
        return quoted ? text.substring(1, text.length() - 1) : text;
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/core/DatabaseSettings.java`:

```java
package vn.edu.hcmiu.sla.core;

import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Connects to the database named by DATABASE_URL (from .env or the environment), the same setting the
 * Python site uses. A spring.datasource.url set directly (as the tests do) wins.
 */
public class DatabaseSettings implements EnvironmentPostProcessor {

    static final String MISSING = "Missing settings: DATABASE_URL. Copy .env.example to .env and fill them in.";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        apply(environment);
    }

    static void apply(ConfigurableEnvironment environment) {
        if (environment.containsProperty("spring.datasource.url")) {
            return;
        }
        String url = environment.getProperty("DATABASE_URL");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(MISSING);
        }
        DatabaseUrl database = DatabaseUrl.parse(url);
        environment.getPropertySources().addFirst(new MapPropertySource("DATABASE_URL", Map.of(
                "spring.datasource.url", database.jdbcUrl(),
                "spring.datasource.username", database.username(),
                "spring.datasource.password", database.password())));
    }
}
```

`web/src/main/resources/META-INF/spring.factories` (Spring Boot 4 moved the interface to `org.springframework.boot.EnvironmentPostProcessor`):

```properties
org.springframework.boot.EnvironmentPostProcessor=vn.edu.hcmiu.sla.core.DatabaseSettings
```

Replace `web/src/main/resources/application.properties` with:

```properties
spring.application.name=sla-web

# The same .env file as the Python site: the repository root's, or one next to this project.
spring.config.import=optional:file:../.env[.properties],optional:file:.env[.properties]

# 8080 while the Python site still uses 5000 (stages 1-2 of the Java design).
server.port=8080

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
# Teammates name migrations by date (V20261001_1__expense_tables.sql), so files made in parallel can arrive in any order.
spring.flyway.out-of-order=true
```

- [ ] **Step 5: Run them**

Run: `cd web && ./mvnw -B test`
Expected: `Tests run: 10, Failures: 0, Errors: 0` (7 + 3), BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add web/
git commit -m "feat(web): Spring Boot project that reads DATABASE_URL from .env" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

(`web/.gitignore` from the generator keeps `web/target/` out of git.)

---

### Task 2: Tables: Flyway baseline and the users table

**Files:**
- Create: `web/src/main/resources/db/migration/V1__baseline.sql`, `web/src/main/java/vn/edu/hcmiu/sla/auth/User.java`, `web/src/main/java/vn/edu/hcmiu/sla/auth/UserRepository.java`, `web/src/test/resources/config/application.properties`
- Test: `web/src/test/java/vn/edu/hcmiu/sla/core/MigrationTest.java`

**Interfaces:**
- Consumes: `DatabaseSettings` (Task 1) — it steps aside because the test settings set `spring.datasource.url`.
- Produces: `@Entity User` (`users` table) with `User(String email, String displayName, String passwordHash, LocalDateTime createdAt)` and getters `getId()`, `getEmail()`, `getDisplayName()`, `getPasswordHash()`, `getCreatedAt()`; `UserRepository extends JpaRepository<User, Integer>` with `Optional<User> findByEmail(String)` and `boolean existsByEmail(String)`.

- [ ] **Step 1: Write the failing test and the test settings**

`web/src/test/resources/config/application.properties` (Spring loads `config/application.properties` on top of `application.properties`, so tests keep every other setting):

```properties
# Tests use a fresh in-memory database per test context (H2 pretending to be MySQL), never the real one.
# CI also runs them once on MySQL by overriding spring.datasource.* on the command line.
spring.datasource.url=jdbc:h2:mem:sla-${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1
spring.datasource.username=sa
spring.datasource.password=
```

`web/src/test/java/vn/edu/hcmiu/sla/core/MigrationTest.java`:

```java
package vn.edu.hcmiu.sla.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** On an empty database (a teammate's laptop), Flyway's V1 creates every table the Python site had. */
@SpringBootTest
class MigrationTest {

    @Autowired
    DataSource dataSource;

    @Test
    void anEmptyDatabaseGetsEveryTable() throws Exception {
        Set<String> tables = new HashSet<>();
        try (Connection connection = dataSource.getConnection();
             ResultSet rows = connection.getMetaData().getTables(connection.getCatalog(), null, "%", new String[] {"TABLE"})) {
            while (rows.next()) {
                tables.add(rows.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
            }
        }

        assertThat(tables).contains(
                "users", "school_sync_devices", "school_sync_settings", "school_sync_runs", "school_changes",
                "school_courses", "school_class_meetings", "school_exams", "school_tuition", "school_events",
                "school_bb_courses", "school_bb_announcements", "school_bb_assignments", "school_bb_materials",
                "flyway_schema_history");
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd web && ./mvnw -B test`
Expected: `MigrationTest` fails — the table set doesn't contain `users` and the other tables (there's no migration yet).

- [ ] **Step 3: Implement**

`web/src/main/resources/db/migration/V1__baseline.sql` (today's tables as Alembic revision `7d2f4b9c1e30` left them; `trigger` is quoted because it's a MySQL keyword):

```sql
-- The tables the Python site had created (Alembic revision 7d2f4b9c1e30).
-- On that existing database Flyway only records this file as done (baseline-on-migrate);
-- on an empty database (a teammate's laptop, the tests) it creates them.
-- Written to run on MySQL 8 and on H2 in MySQL mode.

CREATE TABLE users (
    id INT NOT NULL AUTO_INCREMENT,
    email VARCHAR(255) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email)
);

CREATE TABLE school_sync_devices (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    name VARCHAR(100) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    created_at DATETIME NOT NULL,
    last_seen_at DATETIME NULL,
    revoked_at DATETIME NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_school_sync_devices_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_school_sync_devices_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX ix_school_sync_devices_user_id ON school_sync_devices (user_id);

CREATE TABLE school_sync_settings (
    user_id INT NOT NULL,
    interval_hours INT NOT NULL,
    sync_requested_at DATETIME NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_school_sync_settings_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE school_sync_runs (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    device_id INT NULL,
    `trigger` VARCHAR(20) NOT NULL,
    started_at DATETIME NOT NULL,
    finished_at DATETIME NULL,
    status VARCHAR(10) NOT NULL,
    error_code VARCHAR(40) NULL,
    error_message VARCHAR(500) NULL,
    sections JSON NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_school_sync_runs_device FOREIGN KEY (device_id) REFERENCES school_sync_devices (id) ON DELETE SET NULL,
    CONSTRAINT fk_school_sync_runs_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX ix_school_sync_runs_user_id ON school_sync_runs (user_id);
CREATE INDEX ix_school_sync_runs_user_started ON school_sync_runs (user_id, started_at);

CREATE TABLE school_changes (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    sync_run_id INT NOT NULL,
    section VARCHAR(20) NOT NULL,
    kind VARCHAR(10) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    created_at DATETIME NOT NULL,
    seen_at DATETIME NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_school_changes_run FOREIGN KEY (sync_run_id) REFERENCES school_sync_runs (id) ON DELETE CASCADE,
    CONSTRAINT fk_school_changes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX ix_school_changes_sync_run_id ON school_changes (sync_run_id);
CREATE INDEX ix_school_changes_user_id ON school_changes (user_id);

CREATE TABLE school_courses (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    term_code VARCHAR(20) NOT NULL,
    course_code VARCHAR(20) NOT NULL,
    course_name VARCHAR(255) NOT NULL,
    group_code VARCHAR(20) NULL,
    credits DECIMAL(4, 1) NULL,
    lecturer VARCHAR(255) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_school_courses_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX ix_school_courses_user_id ON school_courses (user_id);

CREATE TABLE school_class_meetings (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    course_id INT NOT NULL,
    start_at DATETIME NOT NULL,
    end_at DATETIME NOT NULL,
    room VARCHAR(50) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_school_class_meetings_course FOREIGN KEY (course_id) REFERENCES school_courses (id) ON DELETE CASCADE,
    CONSTRAINT fk_school_class_meetings_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX ix_school_class_meetings_course_id ON school_class_meetings (course_id);
CREATE INDEX ix_school_class_meetings_user_id ON school_class_meetings (user_id);
CREATE INDEX ix_school_class_meetings_user_start ON school_class_meetings (user_id, start_at);

CREATE TABLE school_exams (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    term_code VARCHAR(20) NOT NULL,
    course_code VARCHAR(20) NOT NULL,
    course_name VARCHAR(255) NOT NULL,
    exam_type VARCHAR(10) NOT NULL,
    start_at DATETIME NOT NULL,
    duration_min INT NULL,
    room VARCHAR(50) NULL,
    notes VARCHAR(500) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_school_exams_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX ix_school_exams_user_id ON school_exams (user_id);
CREATE INDEX ix_school_exams_user_start ON school_exams (user_id, start_at);

CREATE TABLE school_tuition (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    term_code VARCHAR(20) NOT NULL,
    amount_due BIGINT NOT NULL,
    amount_paid BIGINT NOT NULL,
    balance BIGINT NOT NULL,
    due_date DATE NULL,
    status_text VARCHAR(255) NULL,
    items JSON NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_school_tuition_user_term UNIQUE (user_id, term_code),
    CONSTRAINT fk_school_tuition_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX ix_school_tuition_user_id ON school_tuition (user_id);

CREATE TABLE school_events (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    title VARCHAR(200) NOT NULL,
    start_at DATETIME NOT NULL,
    end_at DATETIME NOT NULL,
    all_day BOOLEAN NOT NULL,
    location VARCHAR(255) NULL,
    notes TEXT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_school_events_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX ix_school_events_user_id ON school_events (user_id);
CREATE INDEX ix_school_events_user_start ON school_events (user_id, start_at);

CREATE TABLE school_bb_courses (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    bb_id VARCHAR(64) NOT NULL,
    course_code VARCHAR(20) NULL,
    name VARCHAR(255) NOT NULL,
    url VARCHAR(500) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_school_bb_courses_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX ix_school_bb_courses_user_id ON school_bb_courses (user_id);

CREATE TABLE school_bb_announcements (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    course_id INT NOT NULL,
    bb_id VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL,
    text TEXT NOT NULL,
    posted_at DATETIME NULL,
    url VARCHAR(500) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_school_bb_announcements_course FOREIGN KEY (course_id) REFERENCES school_bb_courses (id) ON DELETE CASCADE,
    CONSTRAINT fk_school_bb_announcements_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX ix_school_bb_announcements_course_id ON school_bb_announcements (course_id);
CREATE INDEX ix_school_bb_announcements_user_id ON school_bb_announcements (user_id);

CREATE TABLE school_bb_assignments (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    course_id INT NOT NULL,
    bb_id VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    due_at DATETIME NULL,
    points_possible DOUBLE NULL,
    score DOUBLE NULL,
    grade_text VARCHAR(50) NULL,
    status VARCHAR(20) NOT NULL,
    feedback TEXT NULL,
    url VARCHAR(500) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_school_bb_assignments_course FOREIGN KEY (course_id) REFERENCES school_bb_courses (id) ON DELETE CASCADE,
    CONSTRAINT fk_school_bb_assignments_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX ix_school_bb_assignments_course_id ON school_bb_assignments (course_id);
CREATE INDEX ix_school_bb_assignments_user_due ON school_bb_assignments (user_id, due_at);
CREATE INDEX ix_school_bb_assignments_user_id ON school_bb_assignments (user_id);

CREATE TABLE school_bb_materials (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    course_id INT NOT NULL,
    bb_id VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL,
    kind VARCHAR(10) NOT NULL,
    path VARCHAR(500) NOT NULL,
    created_at DATETIME NULL,
    url VARCHAR(500) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_school_bb_materials_course FOREIGN KEY (course_id) REFERENCES school_bb_courses (id) ON DELETE CASCADE,
    CONSTRAINT fk_school_bb_materials_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX ix_school_bb_materials_course_id ON school_bb_materials (course_id);
CREATE INDEX ix_school_bb_materials_user_id ON school_bb_materials (user_id);
```

`web/src/main/java/vn/edu/hcmiu/sla/auth/User.java`:

```java
package vn.edu.hcmiu.sla.auth;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** An account. Shared by every module: their tables point at users.id. */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt; // UTC

    protected User() {
    }

    public User(String email, String displayName, String passwordHash, LocalDateTime createdAt) {
        this.email = email;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.createdAt = createdAt;
    }

    public Integer getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/auth/UserRepository.java`:

```java
package vn.edu.hcmiu.sla.auth;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Integer> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
```

- [ ] **Step 4: Run the tests**

Run: `cd web && ./mvnw -B test`
Expected: 11 tests, all pass (JPA's `validate` also checks `User` against the `users` table at startup).

- [ ] **Step 5: Commit**

```bash
git add web/
git commit -m "feat(web): Flyway baseline of today's tables and the users table" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Passwords in Werkzeug's format

**Files:**
- Create: `web/src/main/java/vn/edu/hcmiu/sla/auth/WerkzeugPasswordEncoder.java`
- Test: `web/src/test/java/vn/edu/hcmiu/sla/auth/WerkzeugPasswordEncoderTest.java`

**Interfaces:**
- Produces: `WerkzeugPasswordEncoder implements PasswordEncoder` (`encode`, `matches`); the test's package-private constants `WerkzeugPasswordEncoderTest.SCRYPT` (password `correct-horse-8`) and `SCRYPT_VIETNAMESE` (password `mật khẩu 123`), used by later tests.

- [ ] **Step 1: Write the failing test**

`web/src/test/java/vn/edu/hcmiu/sla/auth/WerkzeugPasswordEncoderTest.java` (the three hashes were made with the Python site's `werkzeug.security.generate_password_hash`):

```java
package vn.edu.hcmiu.sla.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class WerkzeugPasswordEncoderTest {

    // Made by the Python site's Werkzeug: generate_password_hash("correct-horse-8"), and so on.
    static final String SCRYPT = "scrypt:32768:8:1$ueZCDJ4RK5S9GaVe$d6d8d224601f811c03b2dd57870ec046e8bbda5e2b46338f"
            + "7ff949552d22cda2eddbb16a3ed5c96508def3c501f70558543cd0c0f39ba3d360d24867ccce091d";
    static final String SCRYPT_VIETNAMESE = "scrypt:32768:8:1$OS9zvd2XxLG2foqc$14334d0a6981235b7d78aff4e99e2bdc21ebb5"
            + "f2989171df35dc58e7084829c9498997633cb3afa2e71d9457a6c3e5848e1d5d1fd3d2f9fd0051616a941afe75";
    static final String PBKDF2 = "pbkdf2:sha256:1000$JuajhCPVQgbn0zAC$380b7e5da69ae11feba310b3a30a87a62478c4bee78676"
            + "c3273554b8163dec35";

    private final WerkzeugPasswordEncoder encoder = new WerkzeugPasswordEncoder();

    @Test
    void aPasswordSavedByThePythonSiteStillWorks() {
        assertThat(encoder.matches("correct-horse-8", SCRYPT)).isTrue();
        assertThat(encoder.matches("correct-horse-9", SCRYPT)).isFalse();
    }

    @Test
    void vietnameseLettersInAPasswordAreReadTheSameWay() {
        assertThat(encoder.matches("mật khẩu 123", SCRYPT_VIETNAMESE)).isTrue();
        assertThat(encoder.matches("mat khau 123", SCRYPT_VIETNAMESE)).isFalse();
    }

    @Test
    void olderPbkdf2PasswordsWorkToo() {
        assertThat(encoder.matches("correct-horse-8", PBKDF2)).isTrue();
    }

    @Test
    void newPasswordsAreSavedInWerkzeugsScryptFormat() {
        String hash = encoder.encode("correct-horse-8");

        assertThat(hash).matches("scrypt:32768:8:1\\$[A-Za-z0-9]{16}\\$[0-9a-f]{128}");
        assertThat(encoder.matches("correct-horse-8", hash)).isTrue();
        assertThat(encoder.encode("correct-horse-8")).isNotEqualTo(hash); // a new salt every time
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "scrypt:x$salt$00", "scrypt:a:b:c$salt$00", "md5$salt$00"})
    void aDamagedHashIsJustAWrongPassword(String hash) {
        assertThat(encoder.matches("correct-horse-8", hash)).isFalse();
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd web && ./mvnw -B test`
Expected: compilation error, `cannot find symbol: class WerkzeugPasswordEncoder`.

- [ ] **Step 3: Implement**

`web/src/main/java/vn/edu/hcmiu/sla/auth/WerkzeugPasswordEncoder.java`:

```java
package vn.edu.hcmiu.sla.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.HexFormat;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.bouncycastle.crypto.generators.SCrypt;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Passwords in the format of Python's Werkzeug, which the Python site used: "scrypt:N:r:p$salt$hex" and
 * "pbkdf2:sha256:iterations$salt$hex". Existing accounts keep their passwords, and new ones are saved as
 * scrypt:32768:8:1 so either site accepts them during the changeover.
 */
public class WerkzeugPasswordEncoder implements PasswordEncoder {

    private static final String SALT_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int SALT_LENGTH = 16;
    private final SecureRandom random = new SecureRandom();

    @Override
    public String encode(CharSequence rawPassword) {
        StringBuilder salt = new StringBuilder(SALT_LENGTH);
        for (int i = 0; i < SALT_LENGTH; i++) {
            salt.append(SALT_CHARS.charAt(random.nextInt(SALT_CHARS.length())));
        }
        String method = "scrypt:32768:8:1";
        return method + "$" + salt + "$" + hash(method, salt.toString(), rawPassword.toString());
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null) {
            return false;
        }
        String[] parts = encodedPassword.split("\\$", 3);
        if (parts.length != 3) {
            return false;
        }
        try {
            String expected = hash(parts[0], parts[1], rawPassword.toString());
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    parts[2].getBytes(StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static String hash(String method, String salt, String password) {
        byte[] passwordBytes = password.getBytes(StandardCharsets.UTF_8);
        byte[] saltBytes = salt.getBytes(StandardCharsets.UTF_8);
        String[] m = method.split(":");
        if (m[0].equals("scrypt") && m.length == 4) {
            int n = Integer.parseInt(m[1]);
            int r = Integer.parseInt(m[2]);
            int p = Integer.parseInt(m[3]);
            return HexFormat.of().formatHex(SCrypt.generate(passwordBytes, saltBytes, n, r, p, 64));
        }
        if (m[0].equals("pbkdf2") && m.length == 3 && m[1].equals("sha256")) {
            int iterations = Integer.parseInt(m[2]);
            try {
                KeySpec spec = new PBEKeySpec(password.toCharArray(), saltBytes, iterations, 256);
                byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
                return HexFormat.of().formatHex(key);
            } catch (java.security.GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalArgumentException("Unsupported password hash method: " + m[0]);
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `cd web && ./mvnw -B test`
Expected: 20 tests, all pass (9 new).

- [ ] **Step 5: Check the other direction with Werkzeug**

A hash made by the Java code must be accepted by the Python site. From the repository root (Git Bash):

```bash
cat > /tmp/MakeHash.java <<'EOF'
public class MakeHash {
    public static void main(String[] args) {
        System.out.println(new vn.edu.hcmiu.sla.auth.WerkzeugPasswordEncoder().encode("correct-horse-8"));
    }
}
EOF
BC=$(cygpath -w "$(find ~/.m2/repository/org/bouncycastle/bcprov-jdk18on/1.86 -name '*.jar' | head -1)")
SEC=$(cygpath -w "$(find ~/.m2/repository/org/springframework/security/spring-security-crypto -name '*.jar' | head -1)")
HASH=$(java -cp "web/target/classes;$BC;$SEC" "$(cygpath -w /tmp/MakeHash.java)")
.venv/Scripts/python.exe -c "import sys; from werkzeug.security import check_password_hash as c; print(c(sys.argv[1], 'correct-horse-8'), not c(sys.argv[1], 'wrong'))" "$HASH"
```

Expected: `True True`.

- [ ] **Step 6: Commit**

```bash
git add web/
git commit -m "feat(web): passwords in Werkzeug's scrypt format, readable by both sites" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Login, layout, menu, messages and dashboard

**Files:**
- Create: `web/src/main/java/vn/edu/hcmiu/sla/auth/AppUser.java`, `.../auth/AppUserDetailsService.java`, `.../auth/AuthController.java` (login page only), `.../core/SecurityConfig.java`, `.../core/NavModule.java`, `.../core/Navigation.java`, `.../core/Flash.java`, `.../main/HomeController.java`, `web/src/main/resources/templates/layout.html`, `.../templates/auth/login.html`, `.../templates/main/index.html`, `.../templates/error.html`, `web/src/main/resources/static/css/style.css` (copied)
- Test: `web/src/test/java/vn/edu/hcmiu/sla/auth/LoginTest.java`, `web/src/test/java/vn/edu/hcmiu/sla/core/LayoutTest.java`

**Interfaces:**
- Consumes: `User`, `UserRepository` (Task 2), `WerkzeugPasswordEncoder`, `WerkzeugPasswordEncoderTest.SCRYPT`/`SCRYPT_VIETNAMESE` (Task 3).
- Produces: `record AppUser(Integer id, String email, String displayName, String passwordHash) implements UserDetails` with `static AppUser of(User)`; `AppUserDetailsService.normalizeEmail(String)` (package-private static: trim + lower-case); beans `SecurityFilterChain`, `PasswordEncoder` (Werkzeug), `SecurityContextRepository` (HTTP session); `record NavModule(String label, String path)`; `Navigation` puts `navItems` (list of `Navigation.NavItem(label, path-or-null)`) in every page's model; `record Flash(String category, String text)` with `Flash.success(RedirectAttributes, String)` and `Flash.error(...)`; templates use `<html th:replace="~{layout :: page(~{::title}, ~{::main})}">`.

- [ ] **Step 1: Write the failing tests**

`web/src/test/java/vn/edu/hcmiu/sla/auth/LoginTest.java`:

```java
package vn.edu.hcmiu.sla.auth;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LoginTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    User savedUser(String email, String passwordHash) {
        return users.save(new User(email, "An", passwordHash, LocalDateTime.of(2026, 9, 1, 0, 0)));
    }

    @Test
    void anAccountFromThePythonSiteCanLogIn() throws Exception {
        savedUser("an@example.com", WerkzeugPasswordEncoderTest.SCRYPT);

        mvc.perform(post("/auth/login").with(csrf())
                        .param("email", "  AN@example.com ").param("password", "correct-horse-8"))
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void aVietnamesePasswordWorksEndToEnd() throws Exception {
        savedUser("an@example.com", WerkzeugPasswordEncoderTest.SCRYPT_VIETNAMESE);

        mvc.perform(post("/auth/login").with(csrf()).param("email", "an@example.com").param("password", "mật khẩu 123"))
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void aWrongPasswordShowsTheSameMessageAsBefore() throws Exception {
        savedUser("an@example.com", WerkzeugPasswordEncoderTest.SCRYPT);

        mvc.perform(post("/auth/login").with(csrf()).param("email", "an@example.com").param("password", "wrong-password"))
                .andExpect(redirectedUrl("/auth/login?error"));
        mvc.perform(get("/auth/login").param("error", ""))
                .andExpect(content().string(containsString("Email or password is incorrect.")));
    }

    @Test
    void pagesNeedLogin() throws Exception {
        mvc.perform(get("/")).andExpect(redirectedUrl("/auth/login"));
    }

    @Test
    void afterLoginYouGoBackToThePageYouAskedFor() throws Exception {
        savedUser("an@example.com", WerkzeugPasswordEncoderTest.SCRYPT);
        MockHttpSession session = new MockHttpSession();

        mvc.perform(get("/expense/report").session(session)).andExpect(redirectedUrl("/auth/login"));
        mvc.perform(post("/auth/login").session(session).with(csrf())
                        .param("email", "an@example.com").param("password", "correct-horse-8"))
                .andExpect(redirectedUrl("http://localhost/expense/report?continue"));
    }

    @Test
    void logOutNeedsAFormWithItsSecurityCode() throws Exception {
        AppUser an = new AppUser(1, "an@example.com", "An", "x");

        mvc.perform(post("/auth/logout").with(user(an))).andExpect(status().isForbidden());
        mvc.perform(post("/auth/logout").with(user(an)).with(csrf())).andExpect(redirectedUrl("/auth/login"));
    }
}
```

`web/src/test/java/vn/edu/hcmiu/sla/core/LayoutTest.java`:

```java
package vn.edu.hcmiu.sla.core;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import vn.edu.hcmiu.sla.auth.AppUser;

class LayoutTest {

    static final AppUser AN = new AppUser(1, "an@example.com", "An", "x");

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    class WithoutModules {

        @Autowired
        MockMvc mvc;

        @Test
        void theDashboardGreetsYouAndShowsEveryModuleAsComingSoon() throws Exception {
            mvc.perform(get("/").with(user(AN)))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Hi, An")))
                    .andExpect(content().string(containsString("<span class=\"nav-soon\" title=\"Coming soon\">Expense</span>")))
                    .andExpect(content().string(containsString("Coming soon")))
                    .andExpect(content().string(containsString("href=\"/css/style.css\"")));
        }

        @Test
        void theMenuIsHiddenUntilYouLogIn() throws Exception {
            mvc.perform(get("/auth/login"))
                    .andExpect(content().string(not(containsString("Log out"))));
        }

        @Test
        void oneTimeMessagesAppearAtTheTop() throws Exception {
            mvc.perform(get("/auth/login").flashAttr("flashes", List.of(new Flash("error", "Something broke."))))
                    .andExpect(content().string(containsString("<p class=\"flash flash-error\">Something broke.</p>")));
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @Import(WithSchool.SchoolNav.class)
    class WithSchool {

        @TestConfiguration
        static class SchoolNav {
            @Bean
            NavModule schoolNav() {
                return new NavModule("School", "/school");
            }
        }

        @Autowired
        MockMvc mvc;

        @Test
        void aModuleThatRegistersItselfGetsALinkAndACard() throws Exception {
            mvc.perform(get("/").with(user(AN)))
                    .andExpect(content().string(containsString("<a href=\"/school\">School</a>")))
                    .andExpect(content().string(containsString("<a class=\"card module-card\" href=\"/school\">")))
                    .andExpect(content().string(containsString("title=\"Coming soon\">Health</span>")));
        }
    }

    @Test
    void flashHelpersCollectMessagesForTheNextPage() {
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        Flash.success(redirect, "Device renamed.");
        Flash.error(redirect, "A device name is required.");

        org.assertj.core.api.Assertions.assertThat(redirect.getFlashAttributes().get("flashes")).isEqualTo(List.of(
                new Flash("message", "Device renamed."), new Flash("error", "A device name is required.")));
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd web && ./mvnw -B test`
Expected: compilation errors (`AppUser`, `Flash`, `NavModule` don't exist).

- [ ] **Step 3: Implement the Java classes**

`web/src/main/java/vn/edu/hcmiu/sla/auth/AppUser.java`:

```java
package vn.edu.hcmiu.sla.auth;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The logged-in user as Spring Security keeps it. Controllers get it with
 * {@code @AuthenticationPrincipal AppUser user} and filter every query by {@code user.id()}.
 */
public record AppUser(Integer id, String email, String displayName, String passwordHash) implements UserDetails {

    public static AppUser of(User user) {
        return new AppUser(user.getId(), user.getEmail(), user.getDisplayName(), user.getPasswordHash());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    /** For templates: {@code ${#authentication.principal.displayName}}. */
    public String getDisplayName() {
        return displayName;
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/auth/AppUserDetailsService.java`:

```java
package vn.edu.hcmiu.sla.auth;

import java.util.Locale;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Finds the account for the email typed on the login page (trimmed and lower-cased, as at register). */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public AppUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        return users.findByEmail(normalizeEmail(email))
                .map(AppUser::of)
                .orElseThrow(() -> new UsernameNotFoundException("No account for that email"));
    }

    static String normalizeEmail(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/auth/AuthController.java` (the login page only; Task 5 replaces this file with the full version):

```java
package vn.edu.hcmiu.sla.auth;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** The login page. Spring Security itself handles POST /auth/login and POST /auth/logout. */
@Controller
@RequestMapping("/auth")
public class AuthController {

    @GetMapping("/login")
    String login() {
        return "auth/login";
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/core/SecurityConfig.java`:

```java
package vn.edu.hcmiu.sla.core;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

import vn.edu.hcmiu.sla.auth.WerkzeugPasswordEncoder;

/** Every page needs login except login, register and static files. Every form carries a CSRF token. */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain pages(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(pages -> pages
                        .requestMatchers("/auth/login", "/auth/register", "/css/**", "/js/**", "/error").permitAll()
                        .anyRequest().authenticated())
                .formLogin(login -> login
                        .loginPage("/auth/login")
                        .usernameParameter("email")
                        .passwordParameter("password")
                        .failureUrl("/auth/login?error")
                        .defaultSuccessUrl("/", false) // back to the page that asked for login, else home
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/auth/logout")
                        .logoutSuccessUrl("/auth/login"));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new WerkzeugPasswordEncoder();
    }

    /** Where a login is kept between requests; the register page uses it to log the new account in. */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/core/NavModule.java`:

```java
package vn.edu.hcmiu.sla.core;

/**
 * A module's menu entry. A module turns its link on by declaring one bean, e.g. in its package:
 * <pre>
 * &#64;Bean NavModule expenseNav() { return new NavModule("Expense", "/expense"); }
 * </pre>
 * Until then the menu shows it as "coming soon".
 */
public record NavModule(String label, String path) {
}
```

`web/src/main/java/vn/edu/hcmiu/sla/core/Navigation.java`:

```java
package vn.edu.hcmiu.sla.core;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Gives every page the menu: the three modules in a fixed order, with a link for each one that exists. */
@ControllerAdvice
public class Navigation {

    static final List<String> MODULES = List.of("School", "Expense", "Health");

    /** One menu entry; {@code path} is null while the module doesn't exist yet. */
    public record NavItem(String label, String path) {
    }

    private final Map<String, String> paths;

    public Navigation(List<NavModule> modules) {
        this.paths = modules.stream().collect(Collectors.toMap(NavModule::label, NavModule::path, (a, b) -> a));
    }

    @ModelAttribute("navItems")
    public List<NavItem> navItems() {
        return MODULES.stream().map(label -> new NavItem(label, paths.get(label))).toList();
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/core/Flash.java`:

```java
package vn.edu.hcmiu.sla.core;

import java.util.ArrayList;
import java.util.List;

import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * A one-time message shown at the top of the next page, like "Device renamed." Use it before a redirect:
 * <pre>
 * Flash.success(redirect, "Device renamed.");
 * return "redirect:/school/devices";
 * </pre>
 */
public record Flash(String category, String text) {

    public static void success(RedirectAttributes redirect, String text) {
        add(redirect, new Flash("message", text));
    }

    public static void error(RedirectAttributes redirect, String text) {
        add(redirect, new Flash("error", text));
    }

    @SuppressWarnings("unchecked")
    private static void add(RedirectAttributes redirect, Flash flash) {
        List<Flash> flashes = (List<Flash>) redirect.getFlashAttributes().get("flashes");
        if (flashes == null) {
            flashes = new ArrayList<>();
            redirect.addFlashAttribute("flashes", flashes);
        }
        flashes.add(flash);
    }
}
```

`web/src/main/java/vn/edu/hcmiu/sla/main/HomeController.java`:

```java
package vn.edu.hcmiu.sla.main;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import vn.edu.hcmiu.sla.auth.AppUser;

/** The dashboard: a greeting and one card per module. */
@Controller
public class HomeController {

    @GetMapping("/")
    String index(@AuthenticationPrincipal AppUser user, Model model) {
        model.addAttribute("displayName", user.displayName());
        return "main/index";
    }
}
```

- [ ] **Step 4: Implement the templates and copy the stylesheet**

`web/src/main/resources/templates/layout.html`:

```html
<!doctype html>
<!--/* The shared page: header, menu, one-time messages. A page uses it with
     <html th:replace="~{layout :: page(~{::title}, ~{::main})}"> and fills in its own <title> and <main>. */-->
<html lang="en" xmlns:th="http://www.thymeleaf.org" xmlns:sec="http://www.thymeleaf.org/extras/spring-security"
      th:fragment="page(title, content)">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title th:replace="${title}">School-Life-Assistant</title>
  <link rel="stylesheet" th:href="@{/css/style.css}">
</head>
<body>
  <header class="site-header">
    <a class="brand" th:href="@{/}">School-Life-Assistant</a>
    <nav class="site-nav" sec:authorize="isAuthenticated()">
      <th:block th:each="item : ${navItems}">
        <a th:if="${item.path != null}" th:href="@{${item.path}}" th:text="${item.label}">School</a>
        <span th:if="${item.path == null}" class="nav-soon" title="Coming soon" th:text="${item.label}">Health</span>
      </th:block>
      <form method="post" th:action="@{/auth/logout}" class="inline">
        <button type="submit" class="link-button">Log out</button>
      </form>
    </nav>
  </header>

  <main class="container">
    <p th:each="flash : ${flashes}" th:class="|flash flash-${flash.category}|" th:text="${flash.text}">Saved.</p>
    <th:block th:replace="${content}"></th:block>
  </main>
</body>
</html>
```

`web/src/main/resources/templates/auth/login.html`:

```html
<!doctype html>
<html xmlns:th="http://www.thymeleaf.org" th:replace="~{layout :: page(~{::title}, ~{::main})}">
<head>
  <title>Log in · School-Life-Assistant</title>
</head>
<body>
<main>
  <section class="card narrow">
    <h1>Log in</h1>
    <p th:if="${param.error}" class="flash flash-error">Email or password is incorrect.</p>
    <form method="post" th:action="@{/auth/login}" novalidate>
      <div class="field">
        <label for="email">Email</label>
        <input id="email" name="email" type="email" autocomplete="email" required>
      </div>
      <div class="field">
        <label for="password">Password</label>
        <input id="password" name="password" type="password" autocomplete="current-password" required>
      </div>
      <button type="submit" class="button">Log in</button>
    </form>
    <p>No account yet? <a th:href="@{/auth/register}">Create one</a>.</p>
  </section>
</main>
</body>
</html>
```

`web/src/main/resources/templates/main/index.html`:

```html
<!doctype html>
<html xmlns:th="http://www.thymeleaf.org" th:replace="~{layout :: page(~{::title}, ~{::main})}">
<head>
  <title>School-Life-Assistant</title>
</head>
<body>
<main>
  <h1 th:text="|Hi, ${displayName}|">Hi, An</h1>
  <div class="module-grid">
    <th:block th:each="item : ${navItems}">
      <a th:if="${item.path != null}" class="card module-card" th:href="@{${item.path}}">
        <h2 th:text="${item.label}">School</h2>
      </a>
      <div th:if="${item.path == null}" class="card module-card is-soon">
        <h2 th:text="${item.label}">Health</h2>
        <p>Coming soon</p>
      </div>
    </th:block>
  </div>
</main>
</body>
</html>
```

`web/src/main/resources/templates/error.html`:

```html
<!doctype html>
<html xmlns:th="http://www.thymeleaf.org" th:replace="~{layout :: page(~{::title}, ~{::main})}">
<head>
  <title>Something went wrong · School-Life-Assistant</title>
</head>
<body>
<main>
  <section class="card narrow">
    <h1 th:text="${status == 404} ? 'Page not found' : 'Something went wrong'">Page not found</h1>
    <p th:if="${status == 404}">That page doesn't exist, or it isn't yours.</p>
    <p th:unless="${status == 404}">Please go back and try again.</p>
    <p><a th:href="@{/}">Back to the start page</a></p>
  </section>
</main>
</body>
</html>
```

Copy the stylesheet unchanged:

```bash
mkdir -p web/src/main/resources/static/css
cp app/static/css/style.css web/src/main/resources/static/css/style.css
```

- [ ] **Step 5: Run the tests**

Run: `cd web && ./mvnw -B test`
Expected: 31 tests, all pass (6 login + 5 layout new).

- [ ] **Step 6: Commit**

```bash
git add web/
git commit -m "feat(web): login, logout, shared layout, menu, messages and dashboard" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Register

**Files:**
- Create: `web/src/main/java/vn/edu/hcmiu/sla/auth/RegisterForm.java`, `web/src/main/resources/templates/auth/register.html`
- Modify: `web/src/main/java/vn/edu/hcmiu/sla/auth/AuthController.java` (replace the whole file)
- Test: `web/src/test/java/vn/edu/hcmiu/sla/auth/RegisterTest.java`

**Interfaces:**
- Consumes: `User`, `UserRepository`, `AppUser`, `AppUserDetailsService.normalizeEmail`, the `PasswordEncoder` and `SecurityContextRepository` beans (Tasks 2–4).
- Produces: `GET/POST /auth/register`; `RegisterForm` (getters/setters `email`, `displayName`, `password`, `confirm`, normalised in the setters).

- [ ] **Step 1: Write the failing test**

`web/src/test/java/vn/edu/hcmiu/sla/auth/RegisterTest.java`:

```java
package vn.edu.hcmiu.sla.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RegisterTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    MockHttpServletRequestBuilder register(String email, String name, String password, String confirm) {
        return post("/auth/register").with(csrf())
                .param("email", email).param("displayName", name)
                .param("password", password).param("confirm", confirm);
    }

    User savedUser(String email, String passwordHash) {
        return users.save(new User(email, "An", passwordHash, LocalDateTime.of(2026, 9, 1, 0, 0)));
    }

    @Test
    void registeringLogsYouInAndSavesAWerkzeugStylePassword() throws Exception {
        MvcResult result = mvc.perform(register("  An@Example.COM ", " An ", "correct-horse-8", "correct-horse-8"))
                .andExpect(redirectedUrl("/"))
                .andReturn();

        User user = users.findByEmail("an@example.com").orElseThrow();
        assertThat(user.getDisplayName()).isEqualTo("An");
        assertThat(user.getPasswordHash()).startsWith("scrypt:32768:8:1$");
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        mvc.perform(get("/").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Hi, An")));
    }

    @Test
    void anEmailThatIsAlreadyRegisteredIsRefused() throws Exception {
        savedUser("an@example.com", WerkzeugPasswordEncoderTest.SCRYPT);

        mvc.perform(register("AN@example.com", "An again", "correct-horse-8", "correct-horse-8"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("This email is already registered.")));
        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void registerChecksEveryField() throws Exception {
        mvc.perform(register("not-an-email", "  ", "short", "different"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Invalid email address.")))
                .andExpect(content().string(containsString("This field is required.")))
                .andExpect(content().string(containsString("Field must be between 8 and 128 characters long.")))
                .andExpect(content().string(containsString("Passwords don&#39;t match.")));
        assertThat(users.count()).isZero();
    }

    @Test
    void tooLongFieldsGetAFormMessageNotADatabaseError() throws Exception {
        mvc.perform(register("a".repeat(250) + "@x.com", "n".repeat(101), "correct-horse-8", "correct-horse-8"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Field cannot be longer than 255 characters.")))
                .andExpect(content().string(containsString("Field cannot be longer than 100 characters.")));
        assertThat(users.count()).isZero();
    }

    @Test
    void formsWithoutTheirSecurityCodeAreRefused() throws Exception {
        mvc.perform(post("/auth/register").param("email", "an@example.com"))
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd web && ./mvnw -B test`
Expected: `RegisterTest` fails (the register page doesn't exist: 404 instead of the expected redirect or form messages); `formsWithoutTheirSecurityCodeAreRefused` already passes (Spring Security refuses it).

- [ ] **Step 3: Implement**

`web/src/main/java/vn/edu/hcmiu/sla/auth/RegisterForm.java`:

```java
package vn.edu.hcmiu.sla.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The register page's fields, with the same rules and messages as the Python site. */
public class RegisterForm {

    static final String REQUIRED = "This field is required.";

    @NotBlank(message = REQUIRED)
    @Email(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", message = "Invalid email address.")
    @Size(max = 255, message = "Field cannot be longer than 255 characters.")
    private String email = "";

    @NotBlank(message = REQUIRED)
    @Size(max = 100, message = "Field cannot be longer than 100 characters.")
    private String displayName = "";

    @Size(min = 8, max = 128, message = "Field must be between 8 and 128 characters long.")
    private String password = "";

    @NotBlank(message = REQUIRED)
    private String confirm = "";

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = AppUserDetailsService.normalizeEmail(email);
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName == null ? "" : displayName.strip();
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password == null ? "" : password;
    }

    public String getConfirm() {
        return confirm;
    }

    public void setConfirm(String confirm) {
        this.confirm = confirm == null ? "" : confirm;
    }
}
```

Replace `web/src/main/java/vn/edu/hcmiu/sla/auth/AuthController.java` with:

```java
package vn.edu.hcmiu.sla.auth;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** Register and the login page. Spring Security itself handles POST /auth/login and POST /auth/logout. */
@Controller
@RequestMapping("/auth")
public class AuthController {

    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final SecurityContextRepository logins;

    public AuthController(UserRepository users, PasswordEncoder passwords, SecurityContextRepository logins) {
        this.users = users;
        this.passwords = passwords;
        this.logins = logins;
    }

    @GetMapping("/login")
    String login() {
        return "auth/login";
    }

    @GetMapping("/register")
    String registerPage(Model model) {
        model.addAttribute("form", new RegisterForm());
        return "auth/register";
    }

    @PostMapping("/register")
    String register(@Valid @ModelAttribute("form") RegisterForm form, BindingResult errors,
                    HttpServletRequest request, HttpServletResponse response) {
        if (!form.getConfirm().isEmpty() && !form.getConfirm().equals(form.getPassword())) {
            errors.rejectValue("confirm", "mismatch", "Passwords don't match.");
        }
        if (!errors.hasFieldErrors("email") && users.existsByEmail(form.getEmail())) {
            errors.rejectValue("email", "taken", "This email is already registered.");
        }
        if (errors.hasErrors()) {
            return "auth/register";
        }
        User user = users.save(new User(form.getEmail(), form.getDisplayName(),
                passwords.encode(form.getPassword()), LocalDateTime.now(ZoneOffset.UTC)));
        logIn(AppUser.of(user), request, response);
        return "redirect:/";
    }

    private void logIn(AppUser user, HttpServletRequest request, HttpServletResponse response) {
        if (request.getSession(false) != null) {
            request.changeSessionId(); // a new session id after login
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
        SecurityContextHolder.setContext(context);
        logins.saveContext(context, request, response);
    }
}
```

`web/src/main/resources/templates/auth/register.html`:

```html
<!doctype html>
<html xmlns:th="http://www.thymeleaf.org" th:replace="~{layout :: page(~{::title}, ~{::main})}">
<head>
  <title>Create account · School-Life-Assistant</title>
</head>
<body>
<main>
  <section class="card narrow">
    <h1>Create account</h1>
    <form method="post" th:action="@{/auth/register}" th:object="${form}" novalidate>
      <div class="field">
        <label for="email">Email</label>
        <input id="email" type="email" th:field="*{email}" autocomplete="email">
        <p class="field-error" th:each="error : ${#fields.errors('email')}" th:text="${error}">Invalid email address.</p>
      </div>
      <div class="field">
        <label for="displayName">Display name</label>
        <input id="displayName" type="text" th:field="*{displayName}" autocomplete="nickname">
        <p class="field-error" th:each="error : ${#fields.errors('displayName')}" th:text="${error}">This field is required.</p>
      </div>
      <div class="field">
        <label for="password">Password</label>
        <input id="password" type="password" th:field="*{password}" autocomplete="new-password">
        <p class="field-error" th:each="error : ${#fields.errors('password')}" th:text="${error}">Too short.</p>
      </div>
      <div class="field">
        <label for="confirm">Confirm password</label>
        <input id="confirm" type="password" th:field="*{confirm}" autocomplete="new-password">
        <p class="field-error" th:each="error : ${#fields.errors('confirm')}" th:text="${error}">Passwords don't match.</p>
      </div>
      <button type="submit" class="button">Create account</button>
    </form>
    <p>Already have an account? <a th:href="@{/auth/login}">Log in</a>.</p>
  </section>
</main>
</body>
</html>
```

- [ ] **Step 4: Run the tests**

Run: `cd web && ./mvnw -B test`
Expected: `Tests run: 36, Failures: 0, Errors: 0`, BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add web/
git commit -m "feat(web): register, logging the new account in" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: README, GitHub checks, spec, and checks on a real browser and the real database

**Files:**
- Modify: `README.md`, `.github/workflows/ci.yml`, `docs/superpowers/specs/2026-09-26-java-website-design.md`

- [ ] **Step 1: README**

In `README.md`, add this section right before `## Adding your module` (the Python instructions stay until the switch):

## The Java website (`web/`)

The website is moving to Java (Spring Boot), so the whole team can work in Java. See [the design](docs/superpowers/specs/2026-09-26-java-website-design.md). Until the switch, the Python site above is the one in daily use; the Java site runs next to it on port **8080**, on the same database and `.env`.

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

### Adding your module in Java

Example: Expense. Everything goes under `web/src/main/`.

1. **A table.** A migration named by today's date, `resources/db/migration/V20261001_1__expense_tables.sql` (`_2`, `_3` for more on the same day):

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

- [ ] **Step 2: GitHub checks**

Append this job to `.github/workflows/ci.yml` under `jobs:` (after the existing `test` job, same indentation):

```yaml
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

- [ ] **Step 3: Spec**

In `docs/superpowers/specs/2026-09-26-java-website-design.md`:
- §3: replace "**Spring Boot 3.3**, dependencies: `spring-boot-starter-web`, `-thymeleaf`, `-security`, `-data-jpa`, `-validation`, `flyway-core` + `flyway-mysql`, `mysql-connector-j`, `bcprov` (BouncyCastle, for scrypt passwords, §4.2). Tests: `spring-boot-starter-test`, `spring-security-test`, `h2`." with "**Spring Boot 4.1.1** (the current release; Spring Boot 4 splits its starters): `spring-boot-starter-webmvc`, `-thymeleaf` (with `thymeleaf-extras-springsecurity6`), `-security`, `-data-jpa`, `-validation`, `-flyway` + `flyway-mysql`, `mysql-connector-j`, `bcprov-jdk18on` 1.86 (BouncyCastle, for scrypt passwords, §4.2). Tests: the matching `-test` starters and `h2`."
- §4.4: replace "New tables come as `V2__…`, `V3__…` (e.g. `V2__expense_tables.sql`)." with "New tables come in migrations named by date, e.g. `V20261001_1__expense_tables.sql`; `spring.flyway.out-of-order=true` lets files that teammates made in parallel arrive in any order."
- Status line: `**Status:** Stage 1 built (see docs/superpowers/plans/2026-09-26-java-stage1-foundation.md); stages 2–3 to come`.

- [ ] **Step 4: Browser check (throwaway database)**

Start the site on an in-memory database with the test settings (it never touches MySQL):

```bash
cd web && ./mvnw -B spring-boot:test-run -Dspring-boot.run.arguments="--server.port=8099 --server.servlet.session.cookie.secure=false"
```

(in the background). With Playwright and Edge (the scratch environment used for earlier browser checks), at 1400×1000 and 390×844, with the device in dark and in light mode: open `/` (expect a redirect to `/auth/login`), submit `/auth/register` once with every field wrong (expect the four messages) and once correctly (expect the dashboard "Hi, <name>" with School, Expense and Health as "Coming soon"), log out (back to the login page), log in again, and try a wrong password ("Email or password is incorrect."). Look at every screenshot: light grey page, white cards, black text, the same look as the Python site; no page wider than the screen (`document.documentElement.scrollWidth`); no console errors except a missing `/favicon.ico`. Stop the site.

- [ ] **Step 5: Start once on the student's database**

This is the first time Flyway sees the real database: it must only record the baseline.

```bash
.venv/Scripts/python.exe -c "
from dotenv import dotenv_values; from sqlalchemy import create_engine, text
with create_engine(dotenv_values('.env')['DATABASE_URL']).connect() as c:
    print({t: c.execute(text(f'SELECT COUNT(*) FROM {t}')).scalar() for t in ('users', 'school_courses', 'school_sync_runs', 'school_bb_courses')})"
cd web && ./mvnw -B spring-boot:run
```

(the second command in the background). Wait for `Started SlaWebApplication`; `curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8080/auth/login` → `200`. Then run the count command again and:

```bash
.venv/Scripts/python.exe -c "
from dotenv import dotenv_values; from sqlalchemy import create_engine, text
with create_engine(dotenv_values('.env')['DATABASE_URL']).connect() as c:
    print(list(c.execute(text('SELECT version, type, success FROM flyway_schema_history'))))"
```

Expected: the counts are unchanged, and `flyway_schema_history` has exactly one row, `('1', 'BASELINE', 1)`. Stop the site. (The Python site on port 5000 keeps working; Flyway's history table is the only addition.)

- [ ] **Step 6: Full tests and commit**

Run: `cd web && ./mvnw -B test` → 36 pass; and from the repository root `.venv/Scripts/python.exe -m pytest` → all pass (the Python site is unchanged).

```bash
git add README.md .github/workflows/ci.yml docs/superpowers/specs/2026-09-26-java-website-design.md
git commit -m "docs(web): run the Java site and add a module in Java; Java tests on GitHub" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
