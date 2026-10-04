# Connect This Laptop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A student connects a laptop by pressing **Connect** in the app, logging in (or creating an account) in the browser and pressing **Connect** there; the app receives its device key by itself, with no key to copy.

**Architecture:** The app listens on `127.0.0.1` (`connect.py`) and opens the website's new Connect page (`ConnectController`) with a port, a random state, a challenge and the computer's name. Pressing Connect makes a one-time code (`ConnectCodes`, in memory, 2 minutes, one try) and sends the browser back to the app; the app trades the code and its secret verifier at `POST /api/school/sync/connect` for a device key made exactly as **Add device** makes one. Setup step 1 and Accounts → Website use it; pasting a key stays as a backup.

**Tech Stack:** Java 17 / Spring Boot 4 / Spring Security / Thymeleaf / Jackson 3 (`tools.jackson`), JUnit 5 + MockMvc. Python 3.12: standard library `http.server`, `requests`, pydantic (`contract/`), Tkinter/ttk, pytest + `responses`.

**Spec:** `docs/superpowers/specs/2026-10-04-connect-button-design.md`, committed on branch `connect-button` (ae221d1). Read it before starting.

**Where to run things:** PowerShell, in the repository root (`School-Life-Assistant\`, the folder with `README.md`), on branch `connect-button`. Python is the project's `.venv`. Baselines before Task 1:
- `.\.venv\Scripts\python.exe -m pytest` → **837 passed** (a window test may now and then be skipped when Tk can't start; run again).
- `cd web; .\mvnw.cmd test; cd ..` → the last summary line says **Tests run: 654, Failures: 0, Errors: 0, Skipped: 0**.

## Global Constraints

- **Platform:** Windows 10 and 11. No new dependencies on either side: the standard library and what `requirements-dev.txt` and `web/pom.xml` already have.
- **Secret shapes:** Connect's code, state, verifier and challenge are each exactly 43 characters from `A–Z a–z 0–9 _ -` (32 random bytes, URL-safe Base64 without padding). A device key is `sla_` followed by 43 such characters.
- **Challenge:** URL-safe Base64 without padding of SHA-256(verifier as ASCII). RFC 7636's pair pins it on both sides: verifier `dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk` → challenge `E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM`.
- **Where the browser goes back:** only `http://127.0.0.1:<port>/callback`, built by the website from the port number (1024–65535); never `localhost`, never an address taken from the link.
- **Times:** a code lasts 2 minutes and one try (it is removed at the first trade-in attempt, right or wrong). The app waits 5 minutes for the browser.
- **The listener:** binds `127.0.0.1` at port 0 (Windows picks), with `allow_reuse_address = False`; answers only `GET /callback` carrying its own state, compared in constant time; everything else gets 404 and changes nothing; it never writes anything to a console or log.
- **A laptop is added only at the trade-in** (`DeviceKeys.create`), never when Connect is pressed.
- **Secrets in the app:** the state, verifier, code and key go to `log.protect()` as soon as they exist. The website never logs codes, verifiers or keys.
- **Threads:** every wait and check in the window runs through `app.run`; Tk is touched only on its own thread. `App.listen()` keeps one listener at a time; leaving step 1, rebuilding the window, cancelling the Website editor and closing the window all stop it.
- **Tests:** window tests fake the listener, the browser and the trade-in (`accounts_fakes.Fakes`); only `test_connect.py` opens a real listener on `127.0.0.1`.
- **Words on screen:** exactly these.
  - App: `CONNECT_LINE` "Press Connect. Your browser opens: log in (or create an account), then press Connect there."; button "Connect"; `WAITING` "Finish in your browser…"; `PASTE_INSTEAD` "Paste a key instead"; success "Connected to <email>."; `CONNECT_CANCELLED` "You pressed Cancel on the website. Nothing was saved."; `CONNECT_TIMED_OUT` "Nothing came back from the website. Press Connect to try again."; `CONNECT_REFUSED` "The website didn't accept the connection. Press Connect to try again. Nothing was saved."; `CONNECT_UNAVAILABLE` "Connect isn't working on this laptop. Use Paste a key instead."; `SITE_SAVED` "Saved. This laptop now syncs with {}." (today's words).
  - The tab after the browser comes back: `DONE` "✓ Done. You can close this tab and go back to School-Life-Assistant."; `CANCELLED` "Cancelled. You can close this tab."
  - Website: title "Connect this laptop"; "Connect this laptop? <name> will upload your timetable, exams and tuition to the account <email>."; buttons "Connect" and "Cancel"; "Not your account? Press Cancel, log out, then press Connect in School-Life-Assistant again."; a bad link: "This link didn't come from School-Life-Assistant. Press Connect in the app again."; Devices: "Open it and press <strong>Connect</strong>: there's no key to copy."; a laptop without a usable name: "My laptop".
- **Version:** `0.4.0`. `ONLINE_ADDRESS` stays `https://school-life-assistant.duckdns.org`.
- **Release order:** the website goes online (`update.sh` on the server) before `v0.4.0` is tagged. Pushing, running `update.sh` and tagging are outward-facing: ask your human partner before each.
- **Commits:** the repo's style (`feat(agent): …`, `feat(web): …`, `docs: …`). Every message ends with the line `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Never commit `docs/WA_Report_Group.docx`.

## Review Focus

The five situations a student is most likely to hit that the spec implies but doesn't spell out. Each has a test in the task named.

1. **The student closes the window while Connect waits** (gave up, lost the tab): the app quits at once instead of staying in the background for up to 5 minutes, because its worker thread isn't a daemon. *(Task 4: `test_closing_meanwhile_raises_closed`; Task 6: `test_closing_the_window_stops_listening`)*
2. **The browser asks the listener for other things** (`/favicon.ico`, a request without the state, another path): "not found", and the wait goes on to the real answer. *(Task 4: `test_anything_else_is_not_found_and_the_wait_goes_on`)*
3. **The student presses Connect twice** because the first tab looked slow: only the newest press counts; the first listener stops and its late answer changes nothing. *(Task 6: `test_only_the_newest_press_of_connect_counts`)*
4. **A classmate without an account presses Create one in the middle of Connect:** after registering they are back on the Connect page, not the home page. *(Task 3: `registeringGoesBackToThePageThatAskedForLogin`)*
5. **The code comes back twice or too late** (the tab was refreshed, two minutes passed, the server restarted): the trade-in is refused, the app says "Press Connect to try again", and no second laptop appears on Devices. *(Task 2: `aCodeWorksOnce`, `anExpiredCodeIsRefused`; Task 5: `test_a_trade_in_that_fails_says_why_and_saves_nothing`)*

---

## File map

| File | Responsibility | Task |
|---|---|---|
| `contract/sla_contract/schema.py` | `ConnectRequest`, `ConnectResult` | 1 |
| `contract/samples/connect/request.json`, `result.json` (new) | samples both test suites read | 1 |
| `contract/tests/test_contract.py` | their Python tests | 1 |
| `web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncContract.java` | `ConnectRequest` record, `CONNECT_SECRET` | 1 |
| `web/src/test/java/vn/edu/hcmiu/sla/school/sync/Payloads.java` | `sample(name)`, `parse(json)` helpers | 1 |
| `web/src/test/java/vn/edu/hcmiu/sla/school/sync/SyncContractTest.java` | their Java tests | 1 |
| `web/src/main/java/vn/edu/hcmiu/sla/school/sync/ConnectCodes.java` (new) | one-time codes: issue, redeem, challenge | 2 |
| `web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncApiController.java` | `POST /api/school/sync/connect` | 2 |
| `web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncApiConfig.java` | the device-key check skips the trade-in | 2 |
| `web/src/test/java/vn/edu/hcmiu/sla/school/sync/ConnectCodesTest.java`, `ConnectApiTest.java` (new) | tests | 2 |
| `web/src/main/java/vn/edu/hcmiu/sla/school/pages/ConnectController.java` (new) | the Connect page and its two buttons | 3 |
| `web/src/main/resources/templates/school/connect.html` (new) | its template | 3 |
| `web/src/main/java/vn/edu/hcmiu/sla/auth/AuthController.java` | register returns to the page that asked for login | 3 |
| `web/src/main/resources/templates/school/devices.html` | "press Connect" on the install card | 3 |
| `web/src/test/java/vn/edu/hcmiu/sla/school/pages/ConnectPageTest.java` (new); `auth/RegisterTest.java`; `pages/DevicesPageTest.java` | tests | 3 |
| `agent/sla_agent/connect.py` (new) | `Connection`: secrets, listener, `wait`, `close` | 4 |
| `agent/tests/test_connect.py` (new) | tests, with a real listener | 4 |
| `agent/sla_agent/errors.py` | `ConnectRefused` | 5 |
| `agent/sla_agent/server_client.py` | a client without a key; `connect()` | 5 |
| `agent/sla_agent/accounts.py` | `Tools.listen/open_browser/connect`; `connect_laptop`, `connect_site`; `_trouble`; `SITE_SAVED` | 5 |
| `agent/sla_agent/cli.py` | the real `Tools` | 5 |
| `agent/tests/accounts_fakes.py` | `FakeConnection`, `Fakes` for Connect | 5 |
| `agent/tests/test_server_client.py`, `test_accounts.py` | tests | 5 |
| `agent/sla_agent/window.py` | `App.listen/stop_listening/bring_forward/close`; Website editor's Connect | 6 |
| `agent/sla_agent/setup_steps.py` | step 1: Connect, with the key box under Paste a key instead | 6 |
| `agent/tests/test_setup_steps.py`, `test_window.py` | tests | 6 |
| `README.md`, `pages/index.html`, `agent/sla_agent/__init__.py`, the spec | texts, version 0.4.0, Status: Built | 7 |

---

### Task 1: The trade-in's data format, on both sides

The app sends `{"code", "verifier"}` and gets `{"key", "email"}`. Like every sync message, the format lives in `contract/` (Python, pydantic) and in `SyncContract.java`, and shared samples keep the two in step. Both sample loops read only the `*.json` files directly in `contract/samples/` (all of them are `FinishRun` uploads), so the new samples go in a subfolder, `contract/samples/connect/`.

**Files:**
- Modify: `contract/sla_contract/schema.py` (append at the end)
- Create: `contract/samples/connect/request.json`, `contract/samples/connect/result.json`
- Modify: `contract/tests/test_contract.py` (import line; append tests)
- Modify: `web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncContract.java` (after `record StartRun`)
- Modify: `web/src/test/java/vn/edu/hcmiu/sla/school/sync/Payloads.java` (two helpers)
- Modify: `web/src/test/java/vn/edu/hcmiu/sla/school/sync/SyncContractTest.java` (imports; append tests)

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - Python `sla_contract.schema.ConnectRequest(code: str, verifier: str)` (strict: no other fields; each 43 URL-safe characters) and `ConnectResult(key: str, email: str)` (`key` shaped `sla_` + 43).
  - Java `SyncContract.CONNECT_SECRET` (`public static final String`, the regex `[A-Za-z0-9_-]{43}`) and `SyncContract.ConnectRequest(String code, String verifier)`.
  - Test helpers `Payloads.sample(String name) → Map<String, Object>` and `Payloads.parse(String json) → Map<String, Object>`.

- [ ] **Step 1: Write the samples**

`contract/samples/connect/request.json`:

```json
{
  "code": "c0de-0123456789_abcdefghijklmnopqrstuvwxyzA",
  "verifier": "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
}
```

`contract/samples/connect/result.json`:

```json
{
  "key": "sla_device-key-0123456789-abcdefghijklmnopqrstu",
  "email": "an@example.com"
}
```

- [ ] **Step 2: Write the failing Python tests**

In `contract/tests/test_contract.py`, change the import line

```python
from sla_contract.schema import FinishRun, StartRun
```

to

```python
from sla_contract.schema import ConnectRequest, ConnectResult, FinishRun, StartRun
```

and append at the end of the file:

```python


# ---- Connect this laptop: the trade-in (contract/samples/connect/; the Java tests read the same files) ---------


def test_the_connect_samples_are_accepted():
    request = ConnectRequest.model_validate(_sample("connect/request.json"))
    answer = ConnectResult.model_validate(_sample("connect/result.json"))

    assert len(request.code) == len(request.verifier) == 43
    assert answer.key.startswith("sla_")


@pytest.mark.parametrize("change", [
    {"code": "short"},
    {"verifier": "v" * 42 + "!"},
    {"device_key": "sla_" + "k" * 43},  # nothing else may come along
], ids=["short-code", "bad-verifier", "extra-field"])
def test_a_connect_request_with_anything_else_is_refused(change):
    with pytest.raises(ValidationError):
        ConnectRequest.model_validate({**_sample("connect/request.json"), **change})


def test_a_connect_answer_without_a_device_key_is_refused():
    with pytest.raises(ValidationError):
        ConnectResult.model_validate({**_sample("connect/result.json"), "key": "not-a-key"})
```

- [ ] **Step 3: Run them to see them fail**

Run: `.\.venv\Scripts\python.exe -m pytest contract/tests/test_contract.py`
Expected: FAIL, collection error `ImportError: cannot import name 'ConnectRequest' from 'sla_contract.schema'`.

- [ ] **Step 4: Add the Python models**

Append to `contract/sla_contract/schema.py`:

```python


# ---- Connect this laptop (spec 2026-10-04-connect-button-design.md, 3) --------------------------------------
# The trade-in: the app sends the one-time code the browser brought back and the verifier only it knows; the website
# answers with this laptop's new device key and the account it belongs to. The Java twin is SyncContract.ConnectRequest.

Secret = Annotated[str, Field(pattern=r"^[A-Za-z0-9_-]{43}$")]  # 32 random bytes, URL-safe Base64 without padding
DeviceKey = Annotated[str, Field(pattern=r"^sla_[A-Za-z0-9_-]{43}$")]


class ConnectRequest(_Strict):
    code: Secret
    verifier: Secret


class ConnectResult(BaseModel):
    key: DeviceKey
    email: str
```

- [ ] **Step 5: Run the Python tests to see them pass**

Run: `.\.venv\Scripts\python.exe -m pytest contract/tests/test_contract.py`
Expected: PASS (every test in the file, the 5 new ones included).

- [ ] **Step 6: Write the failing Java tests**

In `web/src/test/java/vn/edu/hcmiu/sla/school/sync/Payloads.java`, add these two helpers next to `bytes(...)` (inside the class):

```java
    /** A file in contract/samples/ as a map, e.g. "connect/request.json". */
    static Map<String, Object> sample(String name) {
        return read(name);
    }

    /** A JSON object from the website, e.g. an API answer, as a map. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parse(String json) {
        return JSON.readValue(json, Map.class);
    }
```

(`JSON` is Payloads' Jackson 3 `JsonMapper`; its exceptions are unchecked, so no `throws` is needed.)

In `SyncContractTest.java`, add the imports

```java
import java.util.LinkedHashMap;
```

and

```java
import vn.edu.hcmiu.sla.school.sync.SyncContract.ConnectRequest;
```

then append inside the class, before its closing brace:

```java
    // ---- Connect this laptop: contract/samples/connect/, the Python tests read the same files --------

    @Test
    void theConnectSampleIsAccepted() {
        ConnectRequest request = json.read(bytes(Payloads.sample("connect/request.json")), ConnectRequest.class);

        assertThat(request.code()).hasSize(43);
        assertThat(request.verifier()).hasSize(43);
    }

    static Stream<Arguments> badConnectRequests() {
        return Stream.of(
                Arguments.of("short-code", Map.of("code", "short")),
                Arguments.of("bad-verifier", Map.of("verifier", "v".repeat(42) + "!")),
                Arguments.of("extra-field", Map.of("device_key", "sla_" + "k".repeat(43))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badConnectRequests")
    void aConnectRequestWithAnythingElseIsRefused(String name, Map<String, Object> change) {
        Map<String, Object> request = new LinkedHashMap<>(Payloads.sample("connect/request.json"));
        request.putAll(change);

        assertThatThrownBy(() -> json.read(bytes(request), ConnectRequest.class))
                .isInstanceOf(SyncJson.Invalid.class);
    }
```

- [ ] **Step 7: Run them to see them fail**

Run: `cd web; .\mvnw.cmd -q test "-Dtest=SyncContractTest"; cd ..`
Expected: FAIL, compilation error `cannot find symbol ... class ConnectRequest`.

- [ ] **Step 8: Add the Java record**

In `SyncContract.java`, right after

```java
    public record StartRun(@NotNull @Pattern(regexp = "scheduled|manual|import|mail") String trigger) {
    }
```

add:

```java

    // ---- Connect this laptop (spec 2026-10-04-connect-button-design.md, 3) --------------

    /** A Connect code, state, verifier or challenge: 32 random bytes, URL-safe Base64 without padding. */
    public static final String CONNECT_SECRET = "[A-Za-z0-9_-]{43}";

    /** The trade-in: the one-time code the browser brought back, and the verifier only the app knows. */
    public record ConnectRequest(@NotNull @Pattern(regexp = CONNECT_SECRET) String code,
            @NotNull @Pattern(regexp = CONNECT_SECRET) String verifier) {
    }
```

- [ ] **Step 9: Run the Java tests to see them pass**

Run: `cd web; .\mvnw.cmd -q test "-Dtest=SyncContractTest"; cd ..`
Expected: PASS (no failures; `-q` prints nothing on success).

- [ ] **Step 10: Run everything**

Run: `.\.venv\Scripts\python.exe -m pytest` → **842 passed**.
Run: `cd web; .\mvnw.cmd test; cd ..` → **Tests run: 658, Failures: 0, Errors: 0, Skipped: 0**.

- [ ] **Step 11: Commit**

```powershell
git add contract/sla_contract/schema.py contract/samples/connect contract/tests/test_contract.py web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncContract.java web/src/test/java/vn/edu/hcmiu/sla/school/sync/Payloads.java web/src/test/java/vn/edu/hcmiu/sla/school/sync/SyncContractTest.java
git commit -m "feat(contract): the Connect trade-in's request and answer, with samples both sides check" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: The website's one-time codes and the trade-in

The Connect page (Task 3) will ask `ConnectCodes` for a code; the app trades it at `POST /api/school/sync/connect`. Codes live in memory by their SHA-256 (the same hex hash `DeviceKeys.hashKey` makes), for 2 minutes and one try. The trade-in is the only sync address that needs no device key.

**Files:**
- Create: `web/src/main/java/vn/edu/hcmiu/sla/school/sync/ConnectCodes.java`
- Modify: `web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncApiController.java` (whole file below)
- Modify: `web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncApiConfig.java` (whole file below)
- Test: `web/src/test/java/vn/edu/hcmiu/sla/school/sync/ConnectCodesTest.java`, `ConnectApiTest.java` (new)

**Interfaces:**
- Consumes: `SyncContract.ConnectRequest`, `SyncContract.CONNECT_SECRET`, `Payloads.sample`, `Payloads.parse` (Task 1); `DeviceKeys.create(Integer userId, String name, LocalDateTime now) → NewDevice(device, rawKey)` and `DeviceKeys.hashKey(String) → String` (existing); `vn.edu.hcmiu.sla.school.TestClock` (existing test clock: `set(LocalDateTime utc)`, `reset()`, `TestClock.Config`).
- Produces (Task 3 relies on these):
  - `@Component ConnectCodes(Clock clock)`, with `public String issue(Integer userId, String email, String name, String challenge)` returning the raw code, `public Optional<ConnectCodes.Pending> redeem(String code, String verifier)`, and `static String challenge(String verifier)`.
  - `public record ConnectCodes.Pending(Integer userId, String email, String name, String challenge, Instant expiresAt)`.
  - `POST /api/school/sync/connect`: 200 `{"key", "email"}` with `Cache-Control: no-store`; 400 `{"error": "invalid_code"}`; a body that breaks the contract gets 422 `{"error": "invalid_payload", …}`, as on every sync address.

- [ ] **Step 1: Write the failing tests for the codes**

`web/src/test/java/vn/edu/hcmiu/sla/school/sync/ConnectCodesTest.java`:

```java
package vn.edu.hcmiu.sla.school.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import vn.edu.hcmiu.sla.school.TestClock;

/** Connect's one-time codes on their own: no Spring, and a clock the test moves. */
class ConnectCodesTest {

    // RFC 7636's example verifier and its challenge. The app's tests (test_connect.py) pin the same pair.
    static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
    static final LocalDateTime NOON = LocalDateTime.of(2026, 10, 4, 12, 0);

    final TestClock clock = new TestClock();
    final ConnectCodes codes = new ConnectCodes(clock);

    @BeforeEach
    void atNoon() {
        clock.set(NOON);
    }

    String issue() {
        return codes.issue(7, "an@example.com", "LAPTOP-AN", CHALLENGE);
    }

    @Test
    void theChallengeIsTheVerifiersSha256InUrlSafeBase64() {
        assertThat(ConnectCodes.challenge(VERIFIER)).isEqualTo(CHALLENGE);
    }

    @Test
    void aCodeWithItsVerifierGivesItsConnectOnce() {
        String code = issue();

        assertThat(codes.redeem(code, VERIFIER)).hasValueSatisfying(pending -> {
            assertThat(pending.userId()).isEqualTo(7);
            assertThat(pending.email()).isEqualTo("an@example.com");
            assertThat(pending.name()).isEqualTo("LAPTOP-AN");
        });
        assertThat(codes.redeem(code, VERIFIER)).isEmpty();
    }

    @Test
    void aWrongVerifierFailsAndUsesTheCodeUp() {
        String code = issue();

        assertThat(codes.redeem(code, "x".repeat(43))).isEmpty();
        assertThat(codes.redeem(code, VERIFIER)).isEmpty();
    }

    @Test
    void aCodeLastsTwoMinutes() {
        String early = issue();
        String late = issue();

        clock.set(NOON.plusSeconds(119));
        assertThat(codes.redeem(early, VERIFIER)).isPresent();
        clock.set(NOON.plusMinutes(2));
        assertThat(codes.redeem(late, VERIFIER)).isEmpty();
    }

    @Test
    void anUnknownCodeFails() {
        assertThat(codes.redeem("c".repeat(43), VERIFIER)).isEmpty();
    }

    @Test
    void eachCodeIsNewAndShapedForALink() {
        String first = issue();
        String second = issue();

        assertThat(first).isNotEqualTo(second).matches(SyncContract.CONNECT_SECRET);
        assertThat(second).matches(SyncContract.CONNECT_SECRET);
    }
}
```

- [ ] **Step 2: Write the failing tests for the trade-in**

`web/src/test/java/vn/edu/hcmiu/sla/school/sync/ConnectApiTest.java`:

```java
package vn.edu.hcmiu.sla.school.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static vn.edu.hcmiu.sla.school.sync.Payloads.bytes;

import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.hcmiu.sla.auth.User;
import vn.edu.hcmiu.sla.auth.UserRepository;
import vn.edu.hcmiu.sla.school.TestClock;
import vn.edu.hcmiu.sla.school.model.SchoolSyncDeviceRepository;

/** Connect's trade-in, POST /api/school/sync/connect (spec 2026-10-04-connect-button-design.md, 3, step 5). */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(TestClock.Config.class)
class ConnectApiTest {

    static final String VERIFIER = ConnectCodesTest.VERIFIER;
    static final String CHALLENGE = ConnectCodesTest.CHALLENGE;

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    ConnectCodes codes;

    @Autowired
    SchoolSyncDeviceRepository devices;

    @Autowired
    TestClock clock;

    Integer an;

    @BeforeEach
    void anAccount() {
        an = users.save(new User("an@example.com", "An", "x", LocalDateTime.of(2026, 9, 1, 0, 0))).getId();
    }

    @AfterEach
    void realTime() {
        clock.reset();
    }

    String code() {
        return codes.issue(an, "an@example.com", "LAPTOP-AN", CHALLENGE);
    }

    ResultActions trade(String code, String verifier) throws Exception {
        return mvc.perform(post("/api/school/sync/connect").contentType(MediaType.APPLICATION_JSON)
                .content(bytes(Map.of("code", code, "verifier", verifier))));
    }

    static Map<String, Object> answer(ResultActions result) throws Exception {
        return Payloads.parse(result.andReturn().getResponse().getContentAsString());
    }

    @Test
    void theRightCodeAndVerifierGiveAWorkingKeyForTheAccount() throws Exception {
        ResultActions result = trade(code(), VERIFIER)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.email").value("an@example.com"));
        String key = (String) answer(result).get("key");

        assertThat(key).matches("sla_" + SyncContract.CONNECT_SECRET);
        mvc.perform(get("/api/school/sync/check").header("Authorization", "Bearer " + key))
                .andExpect(status().isOk());
        assertThat(devices.findAll()).singleElement().satisfies(device -> {
            assertThat(device.getUserId()).isEqualTo(an);
            assertThat(device.getName()).isEqualTo("LAPTOP-AN");
        });
    }

    @Test
    void theAnswerHasTheSamplesFieldsAndNoOthers() throws Exception {
        assertThat(answer(trade(code(), VERIFIER)).keySet())
                .isEqualTo(Payloads.sample("connect/result.json").keySet());
    }

    @Test
    void aWrongVerifierIsRefusedAndAddsNothing() throws Exception {
        String code = code();

        trade(code, "x".repeat(43))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_code"));
        trade(code, VERIFIER).andExpect(status().isBadRequest());  // one try per code
        assertThat(devices.count()).isZero();
    }

    @Test
    void aCodeWorksOnce() throws Exception {  // Review Focus 5
        String code = code();

        trade(code, VERIFIER).andExpect(status().isOk());
        trade(code, VERIFIER)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_code"));
        assertThat(devices.count()).isEqualTo(1);
    }

    @Test
    void anExpiredCodeIsRefused() throws Exception {  // Review Focus 5
        LocalDateTime noon = LocalDateTime.of(2026, 10, 4, 12, 0);
        clock.set(noon);
        String code = code();
        clock.set(noon.plusMinutes(2));

        trade(code, VERIFIER).andExpect(status().isBadRequest());
        assertThat(devices.count()).isZero();
    }

    @Test
    void theTradeInNeedsNoDeviceKeyButEverythingElseStillDoes() throws Exception {
        trade(code(), VERIFIER).andExpect(status().isOk());

        mvc.perform(get("/api/school/sync/check")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/school/sync/runs").contentType(MediaType.APPLICATION_JSON)
                        .content(bytes(Map.of("trigger", "manual"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aBodyThatBreaksTheContractIsRefusedAsOnEverySyncAddress() throws Exception {
        mvc.perform(post("/api/school/sync/connect").contentType(MediaType.APPLICATION_JSON)
                        .content(bytes(Map.of("code", "short", "verifier", VERIFIER))))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error").value("invalid_payload"));
        assertThat(devices.count()).isZero();
    }
}
```

- [ ] **Step 3: Run them to see them fail**

Run: `cd web; .\mvnw.cmd -q test "-Dtest=ConnectCodesTest,ConnectApiTest"; cd ..`
Expected: FAIL, compilation error `cannot find symbol ... class ConnectCodes`.

- [ ] **Step 4: Write `ConnectCodes`**

`web/src/main/java/vn/edu/hcmiu/sla/school/sync/ConnectCodes.java`:

```java
package vn.edu.hcmiu.sla.school.sync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Connect this laptop (spec 2026-10-04-connect-button-design.md, 3 and 4.2): the one-time codes the Connect page
 * hands the browser, which the laptop's app trades for its device key. A code lasts {@link #LIFETIME} and one try,
 * and is kept only as its SHA-256, in memory: one server runs the site, and a restart only means pressing Connect
 * again.
 */
@Component
public class ConnectCodes {

    static final Duration LIFETIME = Duration.ofMinutes(2);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_SAFE = Base64.getUrlEncoder().withoutPadding();

    /** A Connect waiting for its trade-in: whose account, the laptop's name, and the app's challenge. */
    public record Pending(Integer userId, String email, String name, String challenge, Instant expiresAt) {
    }

    private final Clock clock;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public ConnectCodes(Clock clock) {
        this.clock = clock;
    }

    /** A new code for this account and laptop. The raw code goes to the browser and is never kept. */
    public String issue(Integer userId, String email, String name, String challenge) {
        Instant now = clock.instant();
        pending.values().removeIf(old -> !now.isBefore(old.expiresAt()));
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String code = URL_SAFE.encodeToString(bytes);
        pending.put(DeviceKeys.hashKey(code), new Pending(userId, email, name, challenge, now.plus(LIFETIME)));
        return code;
    }

    /**
     * The Connect this code belongs to, if the code is still there, hasn't expired, and the verifier matches its
     * challenge. The code is gone after this call whatever the answer: one try per code.
     */
    public Optional<Pending> redeem(String code, String verifier) {
        Pending found = pending.remove(DeviceKeys.hashKey(code));
        if (found == null || !clock.instant().isBefore(found.expiresAt())) {
            return Optional.empty();
        }
        boolean matches = MessageDigest.isEqual(found.challenge().getBytes(StandardCharsets.US_ASCII),
                challenge(verifier).getBytes(StandardCharsets.US_ASCII));
        return matches ? Optional.of(found) : Optional.empty();
    }

    /** What the app sends as its challenge: the verifier's SHA-256, URL-safe Base64 without padding. */
    static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return URL_SAFE.encodeToString(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
```

- [ ] **Step 5: Add the trade-in to `SyncApiController`**

Replace `web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncApiController.java` with (only the javadoc, the two new fields, the constructor, the imports and `connect(...)` change; the rest is today's code):

```java
package vn.edu.hcmiu.sla.school.sync;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.edu.hcmiu.sla.school.model.SchoolSyncDevice;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRun;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRunRepository;
import vn.edu.hcmiu.sla.school.sync.SyncContract.ConnectRequest;
import vn.edu.hcmiu.sla.school.sync.SyncContract.FinishRun;
import vn.edu.hcmiu.sla.school.sync.SyncContract.StartRun;

/**
 * The addresses the laptop agent calls (JSON; the device key instead of a login): "is a sync due?", "I'm starting a
 * sync", and "here is the data, or what went wrong"; and, before the laptop has a key, Connect's trade-in of a
 * one-time code for one (spec 2026-10-04-connect-button-design.md, 3).
 */
@RestController
@RequestMapping("/api/school/sync")
public class SyncApiController {

    private final SyncJson json;
    private final SyncRuns syncRuns;
    private final SchoolSyncRunRepository runs;
    private final Ingest ingest;
    private final ConnectCodes connectCodes;
    private final DeviceKeys deviceKeys;

    public SyncApiController(SyncJson json, SyncRuns syncRuns, SchoolSyncRunRepository runs, Ingest ingest,
            ConnectCodes connectCodes, DeviceKeys deviceKeys) {
        this.json = json;
        this.syncRuns = syncRuns;
        this.runs = runs;
        this.ingest = ingest;
        this.connectCodes = connectCodes;
        this.deviceKeys = deviceKeys;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(Map.of("error", code));
    }

    @GetMapping("/check")
    Map<String, Object> check(@RequestAttribute(DeviceKeyInterceptor.DEVICE) SchoolSyncDevice device) {
        SyncRuns.Check check = syncRuns.check(device.getUserId(), now());
        return Map.of(
                "due", check.decision().due(),
                "reason", check.decision().reason(),
                "interval_hours", check.settings().getIntervalHours());
    }

    @PostMapping("/runs")
    ResponseEntity<Map<String, Object>> start(@RequestAttribute(DeviceKeyInterceptor.DEVICE) SchoolSyncDevice device,
            HttpServletRequest request) throws IOException {
        StartRun body = json.read(json.body(request), StartRun.class);
        SchoolSyncRun run = syncRuns.start(device, body.trigger(), now());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("run_id", run.getId()));
    }

    @PostMapping("/runs/{runId}/finish")
    ResponseEntity<Map<String, Object>> finish(@PathVariable int runId,
            @RequestAttribute(DeviceKeyInterceptor.DEVICE) SchoolSyncDevice device, HttpServletRequest request)
            throws IOException {
        SchoolSyncRun run = runs.findById(runId).orElse(null);
        if (run == null || !run.getUserId().equals(device.getUserId())) {
            return error(HttpStatus.NOT_FOUND, "not_found");
        }
        if (!run.getStatus().equals(SchoolSyncRun.RUNNING)) {
            return error(HttpStatus.CONFLICT, "run_not_running");
        }
        FinishRun body = json.read(json.body(request), FinishRun.class);
        String status = ingest.finishRun(run.getId(), body, now());
        return ResponseEntity.ok(Map.of("status", status));
    }

    /**
     * Connect's trade-in: the one-time code from the Connect page and the app's verifier, for a new device key and the
     * account's email. No device key is needed here (SyncApiConfig); anything wrong with the code is 400 invalid_code.
     */
    @PostMapping("/connect")
    ResponseEntity<Map<String, Object>> connect(HttpServletRequest request) throws IOException {
        ConnectRequest body = json.read(json.body(request), ConnectRequest.class);
        Optional<ConnectCodes.Pending> pending = connectCodes.redeem(body.code(), body.verifier());
        if (pending.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "invalid_code");
        }
        String key = deviceKeys.create(pending.get().userId(), pending.get().name(), now()).rawKey();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("key", key, "email", pending.get().email()));
    }

    @ExceptionHandler(SyncRuns.RunInProgress.class)
    ResponseEntity<Map<String, Object>> runInProgress() {
        return error(HttpStatus.CONFLICT, "run_in_progress");
    }

    @ExceptionHandler(SyncJson.TooLarge.class)
    ResponseEntity<Map<String, Object>> tooLarge() {
        return error(HttpStatus.CONTENT_TOO_LARGE, "payload_too_large");
    }

    @ExceptionHandler(SyncJson.Invalid.class)
    ResponseEntity<Map<String, Object>> invalid(SyncJson.Invalid invalid) {
        return ResponseEntity.unprocessableContent()
                .body(Map.of("error", "invalid_payload", "details", invalid.getDetails()));
    }
}
```

- [ ] **Step 6: Let the trade-in through without a device key**

Replace `web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncApiConfig.java` with:

```java
package vn.edu.hcmiu.sla.school.sync;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The sync API at /api/school/sync/** is for the laptop agent, not a browser: no login page, no session
 * and no CSRF token. Instead {@link AgentVersionInterceptor} turns away agents too old for the format, then
 * {@link DeviceKeyInterceptor} checks the device key on every request but {@link #CONNECT}: Connect's trade-in, which
 * the laptop makes before it has a key (spec 2026-10-04-connect-button-design.md, 4.2).
 */
@Configuration
public class SyncApiConfig implements WebMvcConfigurer {

    static final String PATHS = "/api/school/sync/**";
    static final String CONNECT = "/api/school/sync/connect";

    private final AgentVersionInterceptor agentVersionInterceptor;
    private final DeviceKeyInterceptor deviceKeyInterceptor;

    public SyncApiConfig(AgentVersionInterceptor agentVersionInterceptor, DeviceKeyInterceptor deviceKeyInterceptor) {
        this.agentVersionInterceptor = agentVersionInterceptor;
        this.deviceKeyInterceptor = deviceKeyInterceptor;
    }

    @Bean
    @Order(1) // before the pages' filter chain, which covers every other address
    SecurityFilterChain syncApi(HttpSecurity http) throws Exception {
        http
                .securityMatcher(PATHS)
                .authorizeHttpRequests(api -> api.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable());
        return http.build();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // In this order: an agent too old for the format is told so before its key is even looked at.
        registry.addInterceptor(agentVersionInterceptor).addPathPatterns(PATHS);
        registry.addInterceptor(deviceKeyInterceptor).addPathPatterns(PATHS).excludePathPatterns(CONNECT);
    }
}
```

- [ ] **Step 7: Run the tests to see them pass**

Run: `cd web; .\mvnw.cmd -q test "-Dtest=ConnectCodesTest,ConnectApiTest"; cd ..`
Expected: PASS.

- [ ] **Step 8: Run all of the website's tests**

Run: `cd web; .\mvnw.cmd test; cd ..` → **Tests run: 671, Failures: 0, Errors: 0, Skipped: 0**.

- [ ] **Step 9: Commit**

```powershell
git add web/src/main/java/vn/edu/hcmiu/sla/school/sync/ConnectCodes.java web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncApiController.java web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncApiConfig.java web/src/test/java/vn/edu/hcmiu/sla/school/sync/ConnectCodesTest.java web/src/test/java/vn/edu/hcmiu/sla/school/sync/ConnectApiTest.java
git commit -m "feat(web): Connect's one-time codes, traded once within two minutes for a device key, with no key needed" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: The website's Connect page, and register returning to it

The app opens `/school/devices/connect?port=…&state=…&challenge=…&name=…`. The page asks for login like every page (login already returns to it); registering must return to it too. **Connect** sends the browser to `http://127.0.0.1:<port>/callback?code=…&state=…`; **Cancel** to `…?error=cancelled&state=…`. Nothing is added to Devices here.

**Files:**
- Create: `web/src/main/java/vn/edu/hcmiu/sla/school/pages/ConnectController.java`
- Create: `web/src/main/resources/templates/school/connect.html`
- Modify: `web/src/main/java/vn/edu/hcmiu/sla/auth/AuthController.java` (whole file below)
- Modify: `web/src/main/resources/templates/school/devices.html` (the "Install on your laptop" card)
- Test: `web/src/test/java/vn/edu/hcmiu/sla/school/pages/ConnectPageTest.java` (new); `web/src/test/java/vn/edu/hcmiu/sla/auth/RegisterTest.java`; `web/src/test/java/vn/edu/hcmiu/sla/school/pages/DevicesPageTest.java`

**Interfaces:**
- Consumes: `ConnectCodes.issue(Integer userId, String email, String name, String challenge) → String` and `ConnectCodes.redeem(String code, String verifier) → Optional<Pending>` (Task 2); `SyncContract.CONNECT_SECRET` (Task 1); `AppUser` (`id()`, `email()`); `vn.edu.hcmiu.sla.school.SchoolTestData(EntityManager).user(String email) → AppUser` (existing test helper).
- Produces: `GET` and `POST /school/devices/connect` (form fields `port`, `state`, `challenge`, `name`, `action` = `connect` or `cancel`, plus the CSRF token). The app (Tasks 4–6) relies on the redirect shapes `http://127.0.0.1:<port>/callback?code=<43 characters>&state=<state>` and `http://127.0.0.1:<port>/callback?error=cancelled&state=<state>`.

- [ ] **Step 1: Write the failing tests for the page**

`web/src/test/java/vn/edu/hcmiu/sla/school/pages/ConnectPageTest.java`:

```java
package vn.edu.hcmiu.sla.school.pages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.hcmiu.sla.auth.AppUser;
import vn.edu.hcmiu.sla.school.SchoolTestData;
import vn.edu.hcmiu.sla.school.model.SchoolSyncDeviceRepository;
import vn.edu.hcmiu.sla.school.sync.ConnectCodes;

/** The Connect page (spec 2026-10-04-connect-button-design.md, 2 and 4.1). */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ConnectPageTest {

    static final String STATE = "state-0123456789_abcdefghijklmnopqrstuvwxyz";
    // RFC 7636's example verifier and its challenge (ConnectCodesTest and test_connect.py use the same pair).
    static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
    static final Pattern BACK_WITH_A_CODE =
            Pattern.compile("http://127\\.0\\.0\\.1:51234/callback\\?code=([A-Za-z0-9_-]{43})&state=" + STATE);

    @Autowired
    MockMvc mvc;

    @Autowired
    EntityManager db;

    @Autowired
    ConnectCodes codes;

    @Autowired
    SchoolSyncDeviceRepository devices;

    AppUser an;

    @BeforeEach
    void anAccount() {
        an = new SchoolTestData(db).user("an@example.com");
    }

    static MockHttpServletRequestBuilder link(MockHttpServletRequestBuilder request, String port, String state,
            String challenge, String name) {
        return request.param("port", port).param("state", state).param("challenge", challenge).param("name", name);
    }

    MockHttpServletRequestBuilder page(String name) {
        return link(get("/school/devices/connect"), "51234", STATE, CHALLENGE, name).with(user(an));
    }

    MockHttpServletRequestBuilder press(String action) {
        return link(post("/school/devices/connect"), "51234", STATE, CHALLENGE, "LAPTOP-AN")
                .param("action", action).with(user(an)).with(csrf());
    }

    String html(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void theAppsLinkAsksForLoginFirst() throws Exception {
        mvc.perform(link(get("/school/devices/connect"), "51234", STATE, CHALLENGE, "LAPTOP-AN"))
                .andExpect(redirectedUrl("/auth/login"));
    }

    @Test
    void thePageNamesTheLaptopAndTheAccountAndCantBeFramed() throws Exception {
        mvc.perform(page("LAPTOP-AN")).andExpect(header().string("X-Frame-Options", "DENY"));

        assertThat(html(page("LAPTOP-AN"))).contains("Connect this laptop?", "LAPTOP-AN", "an@example.com",
                "value=\"connect\"", "value=\"cancel\"", "value=\"51234\"", "value=\"" + STATE + "\"",
                "value=\"" + CHALLENGE + "\"", "Not your account?");
    }

    static Stream<Arguments> linksTheAppCantHaveMade() {
        return Stream.of(
                Arguments.of("80", STATE, CHALLENGE),
                Arguments.of("65536", STATE, CHALLENGE),
                Arguments.of("5123a", STATE, CHALLENGE),
                Arguments.of("51234", "short", CHALLENGE),
                Arguments.of("51234", STATE, CHALLENGE.substring(0, 42) + "!"),
                Arguments.of(null, null, null));
    }

    @ParameterizedTest
    @MethodSource("linksTheAppCantHaveMade")
    void aLinkTheAppCantHaveMadeOffersNothingToPress(String port, String state, String challenge) throws Exception {
        MockHttpServletRequestBuilder request = get("/school/devices/connect").with(user(an));
        if (port != null) {
            request = link(request, port, state, challenge, "LAPTOP-AN");
        }

        assertThat(html(request)).contains("This link didn't come from School-Life-Assistant.")
                .doesNotContain("value=\"connect\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void aLaptopWithoutANameIsMyLaptop(String name) throws Exception {
        assertThat(html(page(name))).contains("My laptop");
    }

    @Test
    void aNameTooLongIsMyLaptop() throws Exception {
        assertThat(html(page("x".repeat(101)))).contains("My laptop").doesNotContain("x".repeat(101));
    }

    @Test
    void connectSendsTheBrowserBackToTheAppWithACodeAndAddsNoLaptopYet() throws Exception {
        String location = mvc.perform(press("connect")).andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        Matcher back = BACK_WITH_A_CODE.matcher(location);
        assertThat(back.matches()).as(location).isTrue();
        assertThat(devices.count()).isZero();
        assertThat(codes.redeem(back.group(1), VERIFIER)).hasValueSatisfying(pending -> {
            assertThat(pending.userId()).isEqualTo(an.id());
            assertThat(pending.email()).isEqualTo("an@example.com");
            assertThat(pending.name()).isEqualTo("LAPTOP-AN");
        });
    }

    @Test
    void cancelSendsTheBrowserBackWithNoCode() throws Exception {
        mvc.perform(press("cancel"))
                .andExpect(redirectedUrl("http://127.0.0.1:51234/callback?error=cancelled&state=" + STATE));
        assertThat(devices.count()).isZero();
    }

    @Test
    void aPressWithABadLinkGoesNowhere() throws Exception {
        String html = html(link(post("/school/devices/connect"), "80", STATE, CHALLENGE, "LAPTOP-AN")
                .param("action", "connect").with(user(an)).with(csrf()));

        assertThat(html).contains("This link didn't come from School-Life-Assistant.");
    }

    @Test
    void theAddressBackIsBuiltFromThePortAlone() throws Exception {
        String location = mvc.perform(press("connect")
                        .param("redirect", "https://evil.example/steal")
                        .param("callback", "https://evil.example/steal"))
                .andReturn().getResponse().getRedirectedUrl();

        assertThat(location).startsWith("http://127.0.0.1:51234/callback?");
    }

    @Test
    void connectNeedsTheFormsSecurityCode() throws Exception {
        mvc.perform(link(post("/school/devices/connect"), "51234", STATE, CHALLENGE, "LAPTOP-AN")
                        .param("action", "connect").with(user(an)))
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Write the failing tests for register and the Devices card**

Add to `web/src/test/java/vn/edu/hcmiu/sla/auth/RegisterTest.java` (it already imports `get`, `redirectedUrl` and `MockHttpSession`):

```java
    @Test
    void registeringGoesBackToThePageThatAskedForLogin() throws Exception {  // Review Focus 4
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/school/devices/connect").param("port", "51234").session(session))
                .andExpect(redirectedUrl("/auth/login"));

        mvc.perform(register("an@example.com", "An", "correct-horse-8", "correct-horse-8").session(session))
                .andExpect(redirectedUrl("http://localhost/school/devices/connect?port=51234&continue"));
    }
```

(`registeringLogsYouInAndSavesAWerkzeugStylePassword`, with no page waiting, keeps expecting `/`.)

Add to `web/src/test/java/vn/edu/hcmiu/sla/school/pages/DevicesPageTest.java`:

```java
    @Test
    void theInstallCardSaysThereIsNoKeyToCopy() throws Exception {
        assertThat(devicesPage()).contains("Open it and press <strong>Connect</strong>: there's no key to copy.");
    }
```

- [ ] **Step 3: Run them to see them fail**

Run: `cd web; .\mvnw.cmd -q test "-Dtest=ConnectPageTest,RegisterTest,DevicesPageTest"; cd ..`
Expected: FAIL. `ConnectPageTest` gets 404s instead of pages and redirects; `registeringGoesBackToThePageThatAskedForLogin` gets `/`; `theInstallCardSaysThereIsNoKeyToCopy` doesn't find the line.

- [ ] **Step 4: Write the controller**

`web/src/main/java/vn/edu/hcmiu/sla/school/pages/ConnectController.java`:

```java
package vn.edu.hcmiu.sla.school.pages;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import vn.edu.hcmiu.sla.auth.AppUser;
import vn.edu.hcmiu.sla.school.sync.ConnectCodes;
import vn.edu.hcmiu.sla.school.sync.SyncContract;

/**
 * The Connect page (spec 2026-10-04-connect-button-design.md, 2 and 4.1). The laptop's app opens it with its port,
 * state, challenge and the laptop's name. Connect sends the browser back to the app on this laptop,
 * http://127.0.0.1:&lt;port&gt;/callback, with a one-time code; Cancel sends it back with error=cancelled. Nothing is
 * added here: the laptop appears on Devices when the app trades the code (SyncApiController.connect).
 */
@Controller
@RequestMapping("/school/devices/connect")
public class ConnectController {

    static final String DEFAULT_NAME = "My laptop";
    static final int MAX_NAME = 100;

    private final ConnectCodes codes;

    public ConnectController(ConnectCodes codes) {
        this.codes = codes;
    }

    /** The app's link, checked: {@link #of} gives null for a link the app can't have made. */
    record Link(int port, String state, String challenge, String name) {

        static Link of(String port, String state, String challenge, String name) {
            if (port == null || !port.matches("[0-9]{4,5}") || state == null
                    || !state.matches(SyncContract.CONNECT_SECRET) || challenge == null
                    || !challenge.matches(SyncContract.CONNECT_SECRET)) {
                return null;
            }
            int number = Integer.parseInt(port);
            if (number < 1024 || number > 65535) {
                return null;
            }
            String laptop = name == null ? "" : name.strip();
            return new Link(number, state, challenge,
                    laptop.isEmpty() || laptop.length() > MAX_NAME ? DEFAULT_NAME : laptop);
        }

        /** Back to the app: always this laptop, at the app's port; never an address from the link. */
        String back(String answer) {
            return "http://127.0.0.1:" + port + "/callback?" + answer + "&state=" + state;
        }
    }

    private static String page(AppUser user, Link link, Model model) {
        model.addAttribute("email", user.email());
        model.addAttribute("fromApp", link != null);
        if (link != null) {
            model.addAttribute("appPort", link.port());
            model.addAttribute("appState", link.state());
            model.addAttribute("appChallenge", link.challenge());
            model.addAttribute("laptop", link.name());
        }
        return "school/connect";
    }

    @GetMapping
    String show(@AuthenticationPrincipal AppUser user, @RequestParam(required = false) String port,
            @RequestParam(required = false) String state, @RequestParam(required = false) String challenge,
            @RequestParam(required = false) String name, Model model) {
        return page(user, Link.of(port, state, challenge, name), model);
    }

    @PostMapping
    String answer(@AuthenticationPrincipal AppUser user, @RequestParam(required = false) String port,
            @RequestParam(required = false) String state, @RequestParam(required = false) String challenge,
            @RequestParam(required = false) String name, @RequestParam(defaultValue = "cancel") String action,
            Model model) {
        Link link = Link.of(port, state, challenge, name);
        if (link == null) {
            return page(user, null, model);
        }
        if (!action.equals("connect")) {
            return "redirect:" + link.back("error=cancelled");
        }
        return "redirect:" + link.back("code=" + codes.issue(user.id(), user.email(), link.name(), link.challenge()));
    }
}
```

(The template reads plain model attributes rather than the record: this site's templates read getters, which records don't have; see `AppUser.getDisplayName()`.)

- [ ] **Step 5: Write the template**

`web/src/main/resources/templates/school/connect.html`:

```html
<!doctype html>
<html xmlns:th="http://www.thymeleaf.org" th:replace="~{layout :: page(~{::title}, ~{::main})}">
<head>
  <title>Connect this laptop · School-Life-Assistant</title>
</head>
<body>
<main>
  <h1>Connect this laptop</h1>

  <section class="card" th:if="${fromApp}">
    <p>Connect this laptop? <strong th:text="${laptop}">LAPTOP-AN</strong> will upload your timetable, exams and
       tuition to the account <strong th:text="${email}">an@example.com</strong>.</p>
    <form method="post" th:action="@{/school/devices/connect}">
      <input type="hidden" name="port" th:value="${appPort}">
      <input type="hidden" name="state" th:value="${appState}">
      <input type="hidden" name="challenge" th:value="${appChallenge}">
      <input type="hidden" name="name" th:value="${laptop}">
      <div class="form-actions">
        <button type="submit" class="button" name="action" value="connect">Connect</button>
        <button type="submit" class="link-button" name="action" value="cancel">Cancel</button>
      </div>
    </form>
    <p class="muted">Not your account? Press Cancel, log out, then press Connect in School-Life-Assistant again.</p>
  </section>

  <section class="card" th:unless="${fromApp}">
    <p>This link didn't come from School-Life-Assistant. Press Connect in the app again.</p>
  </section>
</main>
</body>
</html>
```

(`th:action` adds the CSRF token, as on the Devices page.)

- [ ] **Step 6: Make register return to the page that asked for login**

Replace `web/src/main/java/vn/edu/hcmiu/sla/auth/AuthController.java` with (new: the class comment, three imports, the `asked` field, and the last two lines of `register`):

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
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Register and the login page. Spring Security itself handles POST /auth/login and POST /auth/logout. A new account
 * is logged in and goes back to the page that asked for login, as login does: the Connect page, for a classmate who
 * had no account yet (spec 2026-10-04-connect-button-design.md, 4.3).
 */
@Controller
@RequestMapping("/auth")
public class AuthController {

    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final SecurityContextRepository logins;
    /** Where Spring Security keeps the page that asked for login: its default, the one login reads. */
    private final RequestCache asked = new HttpSessionRequestCache();

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
        SavedRequest page = asked.getRequest(request, response);
        return "redirect:" + (page != null ? page.getRedirectUrl() : "/");
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

- [ ] **Step 7: Tell the Devices page's install card about Connect**

In `web/src/main/resources/templates/school/devices.html`, in the "Install on your laptop" card, right after the paragraph that ends `It needs no administrator rights and installs new versions by itself.</p>`, add:

```html
    <p>Open it and press <strong>Connect</strong>: there's no key to copy.</p>
```

- [ ] **Step 8: Run the tests to see them pass**

Run: `cd web; .\mvnw.cmd -q test "-Dtest=ConnectPageTest,RegisterTest,DevicesPageTest"; cd ..`
Expected: PASS.

- [ ] **Step 9: Run all of the website's tests**

Run: `cd web; .\mvnw.cmd test; cd ..` → **Tests run: 689, Failures: 0, Errors: 0, Skipped: 0**.

- [ ] **Step 10: Commit**

```powershell
git add web/src/main/java/vn/edu/hcmiu/sla/school/pages/ConnectController.java web/src/main/resources/templates/school/connect.html web/src/main/java/vn/edu/hcmiu/sla/auth/AuthController.java web/src/main/resources/templates/school/devices.html web/src/test/java/vn/edu/hcmiu/sla/school/pages/ConnectPageTest.java web/src/test/java/vn/edu/hcmiu/sla/auth/RegisterTest.java web/src/test/java/vn/edu/hcmiu/sla/school/pages/DevicesPageTest.java
git commit -m "feat(web): the Connect page sends the browser back to the app with a one-time code; registering returns to it" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: The app's listener (`connect.py`)

One `Connection` per press of **Connect**: it makes the state, verifier and challenge, listens on `127.0.0.1` at a port Windows picks, gives the Connect page's address, and waits for the browser to come back. It knows nothing of the window or the website, so it is tested on its own with a real listener; `requests` plays the browser.

**Files:**
- Create: `agent/sla_agent/connect.py`
- Test: `agent/tests/test_connect.py` (new)

**Interfaces:**
- Consumes: `sla_agent.log.protect(secret)` and `sla_agent.log.redact(text)` (existing: `redact` replaces each protected secret with `***`).
- Produces (Tasks 5 and 6 rely on these):
  - `class Connection` — `Connection()` starts listening or raises `OSError`; attributes `state`, `verifier`, `challenge` (43-character strings), `host` (`"127.0.0.1"`), `port` (int); `url(address: str, name: str) -> str`; `wait(timeout: float) -> str` (the code) raising `Cancelled`, `TimedOut` or `Closed`, and always stopping the listener; `close()` (idempotent, any thread).
  - Exceptions `Cancelled`, `TimedOut`, `Closed`; function `challenge_of(verifier: str) -> str`; constants `DONE`, `CANCELLED` (the tab's two sentences).

- [ ] **Step 1: Write the failing tests**

`agent/tests/test_connect.py`:

```python
"""connect.py: what Connect listens with on 127.0.0.1 while the student is in the browser (spec
2026-10-04-connect-button-design.md, 3 and 5.1). requests stands in for the browser coming back from the website."""

import re
import threading
from urllib.parse import parse_qs, urlsplit

import pytest
import requests

from sla_agent import connect
from sla_agent.connect import Cancelled, Closed, Connection, TimedOut
from sla_agent.log import redact

# RFC 7636's example verifier and its challenge: the website's tests (ConnectCodesTest) pin the same pair.
VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
CODE = "c0de-0123456789_abcdefghijklmnopqrstuvwxyzA"
SHAPE = re.compile(r"[A-Za-z0-9_-]{43}")

BROWSER = requests.Session()
BROWSER.trust_env = False  # straight to 127.0.0.1, whatever proxy Windows has


@pytest.fixture
def connection():
    made = Connection()
    yield made
    made.close()


def come_back(connection, path="/callback", **query):
    """The browser, sent back to the app by the website."""
    return BROWSER.get(f"http://127.0.0.1:{connection.port}{path}", params=query, timeout=5)


def test_the_challenge_is_the_verifiers_sha256_in_url_safe_base64():
    assert connect.challenge_of(VERIFIER) == CHALLENGE


def test_each_connection_has_its_own_secrets_in_the_shape_the_website_checks(connection):
    other = Connection()
    try:
        assert (connection.state, connection.verifier) != (other.state, other.verifier)
        for secret in (connection.state, connection.verifier, connection.challenge):
            assert SHAPE.fullmatch(secret)
        assert connection.challenge == connect.challenge_of(connection.verifier)
    finally:
        other.close()


def test_it_listens_on_this_laptop_only(connection):
    assert connection.host == "127.0.0.1"
    assert 1024 <= connection.port <= 65535


def test_the_link_opens_the_connect_page_with_the_port_state_challenge_and_name(connection):
    parts = urlsplit(connection.url("https://sla.example.com/", "LAPTOP-AN"))

    assert (parts.scheme, parts.netloc, parts.path) == ("https", "sla.example.com", "/school/devices/connect")
    assert parse_qs(parts.query) == {"port": [str(connection.port)], "state": [connection.state],
                                     "challenge": [connection.challenge], "name": ["LAPTOP-AN"]}


def test_the_code_that_comes_back_with_the_right_state_is_the_answer(connection):
    page = come_back(connection, code=CODE, state=connection.state)

    assert page.status_code == 200
    assert connect.DONE in page.text
    assert page.headers["Cache-Control"] == "no-store"
    assert connection.wait(5) == CODE


def test_anything_else_is_not_found_and_the_wait_goes_on(connection):  # Review Focus 2
    assert come_back(connection, code=CODE, state="not-the-state").status_code == 404
    assert come_back(connection, code=CODE).status_code == 404
    assert come_back(connection, state=connection.state).status_code == 404  # no code
    assert come_back(connection, path="/favicon.ico").status_code == 404
    assert come_back(connection, path="/other", code=CODE, state=connection.state).status_code == 404

    come_back(connection, code=CODE, state=connection.state)
    assert connection.wait(5) == CODE


def test_cancel_on_the_website_raises_cancelled(connection):
    page = come_back(connection, error="cancelled", state=connection.state)

    assert connect.CANCELLED in page.text
    with pytest.raises(Cancelled):
        connection.wait(5)


def test_only_the_first_answer_counts(connection):
    come_back(connection, code=CODE, state=connection.state)
    come_back(connection, error="cancelled", state=connection.state)

    assert connection.wait(5) == CODE


def test_nothing_back_in_time_raises_timed_out(connection):
    with pytest.raises(TimedOut):
        connection.wait(0.2)


def test_closing_meanwhile_raises_closed(connection):  # Review Focus 1
    threading.Timer(0.2, connection.close).start()

    with pytest.raises(Closed):
        connection.wait(5)


def test_after_the_wait_nothing_listens_on_the_port(connection):
    come_back(connection, code=CODE, state=connection.state)
    connection.wait(5)

    with pytest.raises(requests.ConnectionError):
        come_back(connection, code=CODE, state=connection.state)


def test_close_can_be_called_again(connection):
    connection.close()
    connection.close()


def test_the_code_and_the_secrets_are_kept_out_of_the_log(connection):
    come_back(connection, code=CODE, state=connection.state)
    connection.wait(5)

    assert redact(f"{CODE} {connection.state} {connection.verifier}") == "*** *** ***"
```

- [ ] **Step 2: Run them to see them fail**

Run: `.\.venv\Scripts\python.exe -m pytest agent/tests/test_connect.py`
Expected: FAIL, collection error `ImportError: cannot import name 'connect' from 'sla_agent'` (or `ModuleNotFoundError: No module named 'sla_agent.connect'`).

- [ ] **Step 3: Write `connect.py`**

`agent/sla_agent/connect.py`:

```python
"""Connect this laptop (spec 2026-10-04-connect-button-design.md, 3). While the student logs in and presses Connect in
the browser, the app listens on 127.0.0.1; the website sends the browser back there with a one-time code, which the
app trades, with the verifier only it knows, for its device key (accounts.connect_laptop, server_client.connect).

One Connection per press of Connect. It knows nothing of the window or the website: it makes the secrets, gives the
Connect page's address, and waits for the browser."""

import base64
import hashlib
import hmac
import secrets
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlencode, urlsplit

from sla_agent.log import protect

HOST = "127.0.0.1"  # this laptop only: the network can't reach it, and Windows' firewall doesn't ask about it
DONE = "✓ Done. You can close this tab and go back to School-Life-Assistant."
CANCELLED = "Cancelled. You can close this tab."
PAGE = ('<!doctype html><html lang="en"><head><meta charset="utf-8"><title>School-Life-Assistant</title></head>'
        '<body style="font-family: Segoe UI, sans-serif; margin: 3em"><p>{}</p></body></html>')


class Cancelled(Exception):
    """The student pressed Cancel on the website's Connect page."""


class TimedOut(Exception):
    """Nothing came back from the website in time."""


class Closed(Exception):
    """The listening stopped meanwhile: Connect was pressed again, or the page or the window closed."""


def _secret():
    """32 random bytes, URL-safe Base64 without padding: 43 characters, the shape the website checks."""
    return base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b"=").decode("ascii")


def challenge_of(verifier):
    """What the Connect page keeps for the trade-in: the verifier's SHA-256, URL-safe Base64 without padding (the
    website's ConnectCodes.challenge)."""
    digest = hashlib.sha256(verifier.encode("ascii")).digest()
    return base64.urlsafe_b64encode(digest).rstrip(b"=").decode("ascii")


class _Listener(ThreadingHTTPServer):
    allow_reuse_address = False  # with it, Windows would let another program open the same port


class Connection:
    """Listens on 127.0.0.1, at a port Windows picks, from creation until an answer, a timeout or close(). Creating
    one raises OSError when it can't listen."""

    def __init__(self):
        self.state, self.verifier = _secret(), _secret()
        protect(self.state)
        protect(self.verifier)
        self.challenge = challenge_of(self.verifier)
        self._answer = None  # ("code", code) or ("cancelled", None): the first one only
        self._closed = False
        self._lock = threading.Lock()
        self._event = threading.Event()
        self._server = _Listener((HOST, 0), _callback(self))
        self.host, self.port = self._server.server_address[:2]
        threading.Thread(target=self._server.serve_forever, kwargs={"poll_interval": 0.1},
                         name="connect-listener", daemon=True).start()

    def url(self, address, name):
        """The website's Connect page for this connection, naming this laptop."""
        query = urlencode({"port": self.port, "state": self.state, "challenge": self.challenge, "name": name})
        return f"{address.rstrip('/')}/school/devices/connect?{query}"

    def wait(self, timeout):
        """The code the browser brought back; raises Cancelled, TimedOut or Closed. Stops listening either way."""
        came = self._event.wait(timeout)
        self.close()
        with self._lock:
            answer = self._answer
        if answer is None:
            raise Closed() if came else TimedOut()
        kind, code = answer
        if kind == "cancelled":
            raise Cancelled()
        return code

    def close(self):
        """Stops listening; a wait() in progress raises Closed. From any thread but the listener's, any number of
        times."""
        with self._lock:
            if self._closed:
                return
            self._closed = True
        self._event.set()
        self._server.shutdown()
        self._server.server_close()

    def _answered(self, kind, code=None):
        with self._lock:
            if self._answer is None and not self._closed:
                self._answer = (kind, code)
        self._event.set()


def _callback(connection):
    """The request handler: GET /callback with this connection's state, and a code or error=cancelled. Anything else,
    a stray request or a page that guessed the port, is "not found" and changes nothing."""

    class Callback(BaseHTTPRequestHandler):
        def do_GET(self):
            parts = urlsplit(self.path)
            query = parse_qs(parts.query)
            code = query.get("code", [""])[0]
            protect(code)
            state = query.get("state", [""])[0].encode("utf-8")
            ours = parts.path == "/callback" and hmac.compare_digest(state, connection.state.encode("ascii"))
            if ours and query.get("error") == ["cancelled"]:
                self._page(CANCELLED)
                connection._answered("cancelled")
            elif ours and code:
                self._page(DONE)
                connection._answered("code", code)
            else:
                self.send_error(404)

        def _page(self, text):
            body = PAGE.format(text).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, format, *args):
            """Nothing: the address carries the code, and the built app has no console to write to."""

    return Callback
```

Notes for the implementer: the handler writes the page *before* it records the answer, so the browser has its page before `wait()` shuts the listener down. `close()` must never run on the listener's own thread (`shutdown()` waits for `serve_forever` to stop); only `wait()` (a worker thread), the window (Tk's thread) and tests call it.

- [ ] **Step 4: Run the tests to see them pass**

Run: `.\.venv\Scripts\python.exe -m pytest agent/tests/test_connect.py`
Expected: PASS, 13 tests. (`test_after_the_wait_nothing_listens_on_the_port` takes about 2 seconds: Windows retries a refused connection to 127.0.0.1 before giving up.)

- [ ] **Step 5: Run everything**

Run: `.\.venv\Scripts\python.exe -m pytest` → **855 passed**.

- [ ] **Step 6: Commit**

```powershell
git add agent/sla_agent/connect.py agent/tests/test_connect.py
git commit -m "feat(agent): Connect's listener on 127.0.0.1, which takes only the code that comes back with its own state" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: The app trades the code for its key (`server_client`, `accounts`)

`accounts.connect_laptop` puts Connect together for the window: open the Connect page for a `Connection`, wait up to 5 minutes, trade the code (`server_client.connect`, sent without a device key), check the new key with today's `check_site`, and answer with one sentence. `connect_site` does the same for Accounts and saves at once. Three new `Tools` fields keep the listener, the browser and the trade-in replaceable by fakes.

**Files:**
- Modify: `agent/sla_agent/errors.py` (one class)
- Modify: `agent/sla_agent/server_client.py` (whole file below)
- Modify: `agent/sla_agent/accounts.py` (imports, constants, `Tools`, `check_site`, `change_site`, a new Connect section)
- Modify: `agent/sla_agent/cli.py` (imports, `tools()`)
- Modify: `agent/tests/accounts_fakes.py` (constants, `FakeConnection`, `Fakes`)
- Test: `agent/tests/test_server_client.py`, `agent/tests/test_accounts.py`

**Interfaces:**
- Consumes: `ConnectRequest`, `ConnectResult` (Task 1, Python); `connect.Connection`, `Cancelled`, `TimedOut`, `Closed` (Task 4).
- Produces (Task 6 relies on these):
  - `errors.ConnectRefused(ServerError)`.
  - `server_client.connect(address: str, code: str, verifier: str) -> ConnectResult`; `ServerClient(base_url, None)` sends no `Authorization` header.
  - `accounts.Tools` gains `listen: () -> Connection` (raises `OSError`), `open_browser: (url) -> None`, `connect: (address, code, verifier) -> ConnectResult`.
  - `accounts.connect_laptop(address, connection, tools, wait=CONNECT_WAIT) -> (Result, key)`: the key is `""` unless the result is ok; a connection closed meanwhile gives `Result(False, "")`. Saves nothing.
  - `accounts.connect_site(state, address, connection, tools) -> Result`: connects, then saves the key at once; ok message `SITE_SAVED.format(state.server_url)` (the address as saved: no trailing `/`).
  - `accounts.computer_name() -> str`; constants `CONNECT_WAIT` (300), `CONNECT_CANCELLED`, `CONNECT_TIMED_OUT`, `CONNECT_REFUSED`, `CONNECT_UNAVAILABLE`, `SITE_SAVED`.
  - Test fakes in `accounts_fakes`: `CODE`, `NEW_KEY`, `FakeConnection(answer=CODE)` (attributes `verifier`, `closed`, `waits`; `url`, `wait`, `close`), and on `Fakes`: `answer` (what the next connection's browser brings back: a code, or an exception instance to raise), `connections` (every `FakeConnection` made), `listen_error`, `browsed` (pages opened), `trade` (a `ConnectResult`, or an exception instance to raise), `trades` (`(address, code, verifier)` per trade-in).

- [ ] **Step 1: Write the failing tests for the server client**

In `agent/tests/test_server_client.py`, add `ConnectRefused` to the `from sla_agent.errors import (...)` list, change `from sla_agent.server_client import ServerClient` to

```python
from sla_agent.server_client import ServerClient, connect
```

and append:

```python


# ---- Connect's trade-in (spec 2026-10-04-connect-button-design.md, 3, step 5) ----------------------------------

CODE = "c0de-0123456789_abcdefghijklmnopqrstuvwxyzA"
VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
NEW_KEY = "sla_new-key-0123456789-abcdefghijklmnopqrstuvwx"


@responses.activate
def test_connect_trades_the_code_for_a_key_without_sending_a_device_key():
    responses.post(f"{API}/connect", json={"key": NEW_KEY, "email": "an@example.com"})

    result = connect(BASE, CODE, VERIFIER)

    assert (result.key, result.email) == (NEW_KEY, "an@example.com")
    request = responses.calls[0].request
    assert json.loads(request.body) == {"code": CODE, "verifier": VERIFIER}
    assert "Authorization" not in request.headers
    assert request.headers["User-Agent"].startswith("SchoolLifeAssistant/")


@responses.activate
def test_a_code_the_website_refuses_raises_connect_refused():
    responses.post(f"{API}/connect", status=400, json={"error": "invalid_code"})

    with pytest.raises(ConnectRefused):
        connect(BASE, CODE, VERIFIER)


@responses.activate
def test_something_that_isnt_a_code_is_refused_without_being_sent():
    with pytest.raises(ConnectRefused):
        connect(BASE, "not a code", VERIFIER)

    assert len(responses.calls) == 0


def test_connect_refuses_a_plain_http_address():
    with pytest.raises(ValueError):
        connect("http://sla.example.com", CODE, VERIFIER)
```

- [ ] **Step 2: Run them to see them fail**

Run: `.\.venv\Scripts\python.exe -m pytest agent/tests/test_server_client.py`
Expected: FAIL, `ImportError: cannot import name 'ConnectRefused' from 'sla_agent.errors'`.

- [ ] **Step 3: Add `ConnectRefused` and the trade-in**

In `agent/sla_agent/errors.py`, right after `class UpdateRequired(ServerError): ...` (its docstring line), add:

```python


class ConnectRefused(ServerError):
    """The website refused Connect's one-time code (HTTP 400): expired, already used, or the wrong verifier."""
```

Replace `agent/sla_agent/server_client.py` with (new: the module docstring, the imports, the two `__init__` header lines, `connect` method and function):

```python
"""Talks to our own web app's sync API: with the device key, or, for Connect's trade-in, before there is one."""

from urllib.parse import urlsplit

import requests
from pydantic import ValidationError
from sla_contract.schema import CheckResult, ConnectRequest, ConnectResult, FinishResult, StartResult, StartRun

from sla_agent.edusoft_client import USER_AGENT
from sla_agent.errors import (
    ConnectRefused,
    DeviceKeyRejected,
    RunInProgress,
    ServerUnreachable,
    UnexpectedAnswer,
    UpdateRequired,
)
from sla_agent.log import protect

LOCAL_HOSTS = ("localhost", "127.0.0.1")
# The free host can take about a minute to wake up, so allow a long read.
TIMEOUT = (10, 90)
TOO_OLD = "This version of School-Life-Assistant is too old; it is updating itself."


def check_server_url(url):
    """Raise ValueError unless the device key would travel encrypted."""
    parts = urlsplit(url)
    if parts.scheme == "https" and parts.hostname:
        return
    if parts.scheme == "http" and parts.hostname in LOCAL_HOSTS:
        return
    raise ValueError("The web app address must start with https:// (http:// only for localhost).")


class ServerClient:
    def __init__(self, base_url, device_key, session=None):
        check_server_url(base_url)
        protect(device_key)
        self.api = base_url.rstrip("/") + "/api/school/sync"
        self.session = session or requests.Session()
        self.session.headers.update({"User-Agent": USER_AGENT})
        if device_key:  # Connect's trade-in comes before the laptop has one
            self.session.headers["Authorization"] = f"Bearer {device_key}"

    def _call(self, method, path, body=None):
        try:
            response = self.session.request(
                method, self.api + path, json=body, timeout=TIMEOUT, allow_redirects=False
            )
        except (requests.ConnectionError, requests.Timeout) as error:
            raise ServerUnreachable(f"The web app couldn't be reached ({error.__class__.__name__}).") from None
        if response.status_code == 401:
            raise DeviceKeyRejected(
                "The web app rejected this device key. It may have been cancelled on the Devices page."
            )
        if response.status_code == 409:
            raise RunInProgress("The web app says a sync is already running.")
        if response.status_code == 426:
            raise UpdateRequired(TOO_OLD)
        if response.is_redirect or response.status_code >= 400:
            raise UnexpectedAnswer(f"The web app answered HTTP {response.status_code}: {response.text[:300]}",
                                   response.status_code)
        return response.json()

    def check(self):
        return CheckResult.model_validate(self._call("GET", "/check"))

    def start(self, trigger):
        body = StartRun(trigger=trigger).model_dump()
        return StartResult.model_validate(self._call("POST", "/runs", body)).run_id

    def finish(self, run_id, result):
        body = result.model_dump(mode="json", exclude_none=True)
        return FinishResult.model_validate(self._call("POST", f"/runs/{run_id}/finish", body)).status

    def connect(self, code, verifier):
        """Connect's trade-in (spec 2026-10-04-connect-button-design.md, 3, step 5): the one-time code the browser
        brought back and the verifier, for this laptop's new device key and the account's email."""
        protect(code)
        protect(verifier)
        try:
            body = ConnectRequest(code=code, verifier=verifier).model_dump()
        except ValidationError:
            raise ConnectRefused("The browser brought back something that isn't a Connect code.") from None
        try:
            answer = self._call("POST", "/connect", body)
        except UnexpectedAnswer as error:
            if error.status == 400:
                raise ConnectRefused("The website didn't accept the connection.") from None
            raise
        result = ConnectResult.model_validate(answer)
        protect(result.key)
        return result


def connect(address, code, verifier):
    """Connect's trade-in with the website at `address`, without a device key (accounts.Tools.connect)."""
    return ServerClient(address, None).connect(code, verifier)
```

- [ ] **Step 4: Run the server client's tests to see them pass**

Run: `.\.venv\Scripts\python.exe -m pytest agent/tests/test_server_client.py`
Expected: PASS (the existing tests too: the device key still goes in `Authorization`).

- [ ] **Step 5: Give the fakes Connect**

In `agent/tests/accounts_fakes.py`:

1. Add the import (after the module docstring, before `from agent.tests.fakes import …`):

```python
from sla_contract.schema import ConnectResult

```

2. After the line `PYTHONW = r"C:\IU_SCHOOL\p\.venv\Scripts\pythonw.exe"`, add:

```python
CODE = "c0de-0123456789_abcdefghijklmnopqrstuvwxyzA"  # a Connect code's shape: 43 characters
NEW_KEY = "sla_new-key-0123456789-abcdefghijklmnopqrstuvwx"  # the key Connect brings back


class FakeConnection:
    """connect.Connection without a listener: wait() gives `answer`, a code, or raises it when it is an exception
    instance (connect.Cancelled(), TimedOut(), Closed())."""

    verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"

    def __init__(self, answer=CODE):
        self.answer, self.closed, self.waits = answer, False, []

    def url(self, address, name):
        return f"{address}/school/devices/connect?name={name}"

    def wait(self, timeout):
        self.waits.append(timeout)
        if isinstance(self.answer, Exception):
            raise self.answer
        return self.answer

    def close(self):
        self.closed = True
```

3. In `Fakes.__init__`, after `self.looks = []`, add:

```python
        self.answer = CODE  # what the next Connect's browser brings back (a code, or an exception instance)
        self.connections = []  # every FakeConnection made, oldest first
        self.listen_error = None  # an OSError: Connect can't listen
        self.browsed = []  # the pages Connect opened in the browser
        self.trade = ConnectResult(key=NEW_KEY, email=ME)  # the trade-in's answer, or an exception instance
        self.trades = []  # (address, code, verifier) of each trade-in
```

4. In `Fakes.tools()`, change the last argument line

```python
            open_site=self.opened.append)
```

to

```python
            open_site=self.opened.append, listen=self._listen, open_browser=self.browsed.append,
            connect=self._trade)
```

5. Add these methods to `Fakes` (after `_make_server`):

```python
    def _listen(self):
        if self.listen_error:
            raise self.listen_error
        self.connections.append(FakeConnection(self.answer))
        return self.connections[-1]

    def _trade(self, address, code, verifier):
        self.trades.append((address, code, verifier))
        if isinstance(self.trade, Exception):
            raise self.trade
        return self.trade
```

- [ ] **Step 6: Write the failing tests for `connect_laptop` and `connect_site`**

In `agent/tests/test_accounts.py`, extend the imports:

```python
from agent.tests.accounts_fakes import (
    BB_PASSWORD,
    BB_USER,
    CODE,
    KEY,
    ME,
    NEW_KEY,
    PASSWORD,
    SERVER,
    STUDENT,
    FakeConnection,
    Fakes,
    set_up,
)
from sla_contract.schema import ConnectResult

from sla_agent import accounts, credentials, launcher
from sla_agent.connect import Cancelled, Closed, TimedOut
from sla_agent.errors import (
    BadCredentials,
    ConnectRefused,
    DeviceKeyRejected,
    ExtraVerification,
    OutlookNotSetUp,
    ServerUnreachable,
    UnexpectedAnswer,
)
```

(keep the other existing imports as they are), then append:

```python


# ---- Connect (spec 2026-10-04-connect-button-design.md) -------------------------------------------------------


def read_log(log_path, tmp_path):
    for handler in logging.getLogger().handlers[:]:
        if str(tmp_path) in getattr(handler, "baseFilename", ""):
            handler.flush()
            logging.getLogger().removeHandler(handler)
            handler.close()
    return log_path.read_text(encoding="utf-8")


def test_connect_opens_the_connect_page_and_brings_back_a_checked_key(fakes, monkeypatch):
    monkeypatch.setenv("COMPUTERNAME", "LAPTOP-AN")
    connection = FakeConnection()

    result, key = accounts.connect_laptop(SERVER + "/", connection, fakes.tools())

    assert result == accounts.Result(True, f"Connected to {ME}.")
    assert key == NEW_KEY
    assert fakes.browsed == [f"{SERVER}/school/devices/connect?name=LAPTOP-AN"]
    assert connection.waits == [accounts.CONNECT_WAIT]
    assert fakes.trades == [(SERVER, CODE, FakeConnection.verifier)]
    assert fakes.servers == [(SERVER, NEW_KEY)]  # checked like a pasted key
    assert credentials.load_device_key(SERVER) is None  # nothing saved


def test_a_laptop_without_a_computer_name_is_my_laptop(fakes, monkeypatch):
    monkeypatch.delenv("COMPUTERNAME", raising=False)

    accounts.connect_laptop(SERVER, FakeConnection(), fakes.tools())

    assert fakes.browsed == [f"{SERVER}/school/devices/connect?name=My laptop"]


@pytest.mark.parametrize("answer, message", [
    (Cancelled(), accounts.CONNECT_CANCELLED),
    (TimedOut(), accounts.CONNECT_TIMED_OUT),
    (Closed(), ""),  # an older press of Connect, or a page left: nobody shows it
], ids=["cancelled", "timed-out", "closed"])
def test_no_code_from_the_browser_says_why_and_trades_nothing(fakes, answer, message):
    result, key = accounts.connect_laptop(SERVER, FakeConnection(answer), fakes.tools())

    assert (result, key) == (accounts.Result(False, message), "")
    assert fakes.trades == []


@pytest.mark.parametrize("error, message", [
    (ConnectRefused("The website didn't accept the connection."), accounts.CONNECT_REFUSED),
    (UnexpectedAnswer("The web app answered HTTP 502: <html>Bad gateway</html>", 502),
     "The website at https://sla.example.com isn't working right now (HTTP 502). Try again later. Nothing was saved."),
    (ServerUnreachable("The web app couldn't be reached (ConnectionError)."),
     "Couldn't reach the web app: The web app couldn't be reached (ConnectionError). Nothing was saved."),
], ids=["refused", "error-page", "unreachable"])
def test_a_trade_in_that_fails_says_why_and_saves_nothing(fakes, error, message):  # Review Focus 5
    fakes.trade = error

    result, key = accounts.connect_laptop(SERVER, FakeConnection(), fakes.tools())

    assert (result, key) == (accounts.Result(False, message), "")
    assert credentials.load_device_key(SERVER) is None


def test_a_key_the_check_refuses_is_not_brought_back(fakes):
    fakes.server.check_error = DeviceKeyRejected("rejected")

    result, key = accounts.connect_laptop(SERVER, FakeConnection(), fakes.tools())

    assert not result.ok
    assert key == ""


def test_connect_refuses_a_plain_http_address_without_opening_the_browser(fakes):
    connection = FakeConnection()

    result, key = accounts.connect_laptop("http://sla.example.com", connection, fakes.tools())

    assert not result.ok and "https://" in result.message and key == ""
    assert fakes.browsed == []
    assert connection.closed


def test_connect_in_accounts_saves_the_new_key_at_once(fakes):
    state = set_up()

    result = accounts.connect_site(state, "https://new.example.com", FakeConnection(), fakes.tools())

    assert result == accounts.Result(True, "Saved. This laptop now syncs with https://new.example.com.")
    assert credentials.load_device_key("https://new.example.com") == NEW_KEY
    assert credentials.load_device_key(SERVER) is None
    assert load_state().server_url == "https://new.example.com"


def test_connect_in_accounts_that_fails_keeps_the_old_key(fakes):
    state = set_up()

    result = accounts.connect_site(state, SERVER, FakeConnection(Cancelled()), fakes.tools())

    assert result == accounts.Result(False, accounts.CONNECT_CANCELLED)
    assert credentials.load_device_key(SERVER) == KEY


def test_connects_secrets_never_reach_the_log_file(fakes, tmp_path):
    log_path = setup_logging(tmp_path / "logs")
    fakes.trade = UnexpectedAnswer(f"The web app answered HTTP 502: {CODE} {FakeConnection.verifier}", 502)
    accounts.connect_laptop(SERVER, FakeConnection(), fakes.tools())
    fakes.trade = ConnectResult(key=NEW_KEY, email=ME)
    fakes.server.check_error = UnexpectedAnswer(f"The web app answered HTTP 502: {NEW_KEY}", 502)
    accounts.connect_laptop(SERVER, FakeConnection(), fakes.tools())

    text = read_log(log_path, tmp_path)

    assert "***" in text
    assert CODE not in text and FakeConnection.verifier not in text and NEW_KEY not in text
```

- [ ] **Step 7: Run them to see them fail**

Run: `.\.venv\Scripts\python.exe -m pytest agent/tests/test_accounts.py`
Expected: FAIL. First `TypeError: Tools.__init__() got an unexpected keyword argument 'listen'` (from `Fakes.tools()`), for every test in the file.

- [ ] **Step 8: Add Connect to `accounts.py`**

1. Imports. Change

```python
import logging
import re
```

to

```python
import logging
import os
import re
```

add `ConnectRefused,` to the `from sla_agent.errors import (...)` list (alphabetically, after `BadCredentials,`), and after `from sla_agent import credentials, launcher` add:

```python
from sla_agent.connect import Cancelled, Closed, TimedOut
```

2. Constants. After the line `DEVICE_KEY = re.compile(...)`, add:

```python
SITE_SAVED = "Saved. This laptop now syncs with {}."
CONNECT_WAIT = 5 * 60  # seconds for the student to log in, or to create an account first
CONNECT_CANCELLED = "You pressed Cancel on the website. Nothing was saved."
CONNECT_TIMED_OUT = "Nothing came back from the website. Press Connect to try again."
CONNECT_REFUSED = "The website didn't accept the connection. Press Connect to try again. Nothing was saved."
CONNECT_UNAVAILABLE = "Connect isn't working on this laptop. Use Paste a key instead."
```

3. `Tools`. After its last field, `open_site: Callable  # (address) -> opens the website as an app (app_window.open_app)`, add:

```python
    listen: Callable  # () -> connect.Connection, listening on 127.0.0.1 for Connect; raises OSError
    open_browser: Callable  # (url) -> opens the default browser at url
    connect: Callable  # (address, code, verifier) -> ConnectResult: Connect's trade-in; raises ConnectRefused, ServerError
```

4. Replace the whole `check_site` function with these two functions:

```python
def _trouble(address, error, doing):
    """The sentence for a website that answered with an error page (e.g. a host's "service suspended" page: its HTML
    goes to the log only) or couldn't be reached."""
    if isinstance(error, UnexpectedAnswer):
        log.warning("%s: %s", doing, error)
        return Result(False, f"The website at {address} isn't working right now (HTTP {error.status}). "
                             "Try again later. Nothing was saved.")
    return Result(False, f"Couldn't reach the web app: {error} Nothing was saved.")


def check_site(address, key, tools):
    """Whether the website at `address` accepts this device key."""
    protect(key)
    address = _clean(address)
    try:
        check_server_url(address)
    except ValueError as error:
        return Result(False, str(error))
    if not key:
        return Result(False, "Paste the device key from the Devices page. Nothing was saved.")
    try:
        tools.make_server(address, key).check()
    except DeviceKeyRejected:
        return Result(False, "The web app rejected this device key. Create a new one on the Devices page. "
                             "Nothing was saved.")
    except ServerError as error:
        return _trouble(address, error, "Checking the device key")
    return Result(True, SITE_ACCEPTED)
```

5. Replace `change_site` with this, and add the Connect section right after it:

```python
def change_site(state, address, key, tools):
    """Check, then save, a new website address or device key."""
    checked = check_site(address, key, tools)
    if not checked.ok:
        return checked
    _save(state, lambda current: _keep_site(current, address, key))
    return Result(True, SITE_SAVED.format(state.server_url))


# ---- Connect (spec 2026-10-04-connect-button-design.md) ---------------------------------------------------


def computer_name():
    """This laptop's name on the Devices page: Windows' computer name (e.g. LAPTOP-KHANG), else "My laptop"."""
    return os.environ.get("COMPUTERNAME", "").strip() or "My laptop"


def connect_laptop(address, connection, tools, wait=CONNECT_WAIT):
    """Connect: open the website's Connect page for `connection` (connect.Connection), wait for the browser to come
    back, trade the code for a device key and check it, as a pasted key is checked. Saves nothing.

    Returns (Result, key); the key is "" unless the result is ok. A connection closed meanwhile (Connect pressed
    again, the page left, the window closed) gives an empty message: nobody shows it."""
    address = _clean(address)
    try:
        check_server_url(address)
    except ValueError as error:
        connection.close()
        return Result(False, str(error)), ""
    tools.open_browser(connection.url(address, computer_name()))
    try:
        code = connection.wait(wait)
    except Cancelled:
        return Result(False, CONNECT_CANCELLED), ""
    except TimedOut:
        return Result(False, CONNECT_TIMED_OUT), ""
    except Closed:
        return Result(False, ""), ""
    protect(code)
    protect(connection.verifier)
    try:
        connected = tools.connect(address, code, connection.verifier)
    except ConnectRefused:
        return Result(False, CONNECT_REFUSED), ""
    except ServerError as error:
        return _trouble(address, error, "Connecting"), ""
    protect(connected.key)
    checked = check_site(address, connected.key, tools)
    if not checked.ok:
        return checked, ""
    return Result(True, f"Connected to {connected.email}."), connected.key


def connect_site(state, address, connection, tools):
    """Accounts → Website → Connect: connect, then save the new key at once, as change_site does."""
    result, key = connect_laptop(address, connection, tools)
    if not result.ok:
        return result
    _save(state, lambda current: _keep_site(current, address, key))
    return Result(True, SITE_SAVED.format(state.server_url))
```

- [ ] **Step 9: Give the real app its `Tools`**

In `agent/sla_agent/cli.py`:

1. Add `import webbrowser` after `import sys`.
2. After `from sla_agent.blackboard_reader import read_blackboard`, add `from sla_agent.connect import Connection`.
3. Change `from sla_agent.server_client import ServerClient, check_server_url` to

```python
from sla_agent.server_client import ServerClient, check_server_url
from sla_agent.server_client import connect as trade_in
```

4. In `tools()`, change the last argument line

```python
        has_window_link=mail_link.window_registered, open_site=app_window.open_app)
```

to

```python
        has_window_link=mail_link.window_registered, open_site=app_window.open_app,
        listen=Connection, open_browser=webbrowser.open, connect=trade_in)
```

- [ ] **Step 10: Run the tests to see them pass**

Run: `.\.venv\Scripts\python.exe -m pytest agent/tests/test_accounts.py agent/tests/test_server_client.py`
Expected: PASS (the existing website, key and log tests too: `check_site` says exactly what it said before).

- [ ] **Step 11: Run everything**

Run: `.\.venv\Scripts\python.exe -m pytest` → **872 passed**. (The window tests still pass: nothing on screen uses Connect yet.)

- [ ] **Step 12: Commit**

```powershell
git add agent/sla_agent/errors.py agent/sla_agent/server_client.py agent/sla_agent/accounts.py agent/sla_agent/cli.py agent/tests/accounts_fakes.py agent/tests/test_server_client.py agent/tests/test_accounts.py
git commit -m "feat(agent): Connect opens the website, waits for the browser, and trades the code for a checked device key" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Connect in the window: setup step 1 and Accounts → Website

Step 1 leads with **Connect**; today's guide, **Open the website**, the key box and **Paste** move into a frame that **Paste a key instead** shows. Accounts' Website editor gets **Connect** next to the address and the key box under **Paste a key instead**. `App` holds one listener at a time: a new press of Connect closes the old one, and leaving step 1, rebuilding the window, cancelling the editor and closing the window stop it (the waits run on threads that aren't daemons, so a forgotten listener would keep the app alive for 5 minutes).

**Files:**
- Modify: `agent/sla_agent/setup_steps.py` (docstring, imports, constants, `SetupSteps`, the whole `SitePage` class)
- Modify: `agent/sla_agent/window.py` (one import, `App`, `Editor`)
- Test: `agent/tests/test_setup_steps.py`, `agent/tests/test_window.py`

**Interfaces:**
- Consumes: `accounts.connect_laptop`, `accounts.connect_site`, `accounts.CONNECT_UNAVAILABLE`, `accounts.CONNECT_CANCELLED`, `accounts.Result`; `Tools.listen` (Task 5); the fakes `NEW_KEY`, `Fakes.answer/connections/listen_error/browsed` (Task 5); `connect.Cancelled` (Task 4).
- Produces: `setup_steps.CONNECT_LINE`, `WAITING`, `PASTE_INSTEAD`; `SitePage.connect_button`, `paste_link`, `paste_frame`, `connect()`, `connected(address, connection, answer)`; `App.listen() -> Connection`, `App.stop_listening()`, `App.bring_forward()`, `App.close()`; `Editor.connect_button`, `paste_link`, `key_row`, `connect()`, `connected(connection, result)`.

- [ ] **Step 1: Write the failing tests for step 1**

In `agent/tests/test_setup_steps.py`, change the import line

```python
from agent.tests.accounts_fakes import BB_PASSWORD, BB_USER, KEY, ME, PASSWORD, SERVER, STUDENT, Fakes
```

to

```python
from agent.tests.accounts_fakes import BB_PASSWORD, BB_USER, KEY, ME, NEW_KEY, PASSWORD, SERVER, STUDENT, Fakes
```

add `from sla_agent.connect import Cancelled` after the `from sla_agent import …` line, and add after the existing step 1 tests (before the `# ---- step 2` section, or at the end of the file):

```python
# ---- step 1: Connect (spec 2026-10-04-connect-button-design.md) -------------------------------------------------


def test_step_1_leads_with_connect_and_keeps_paste_a_key_instead(root, fakes):
    _, steps = start(root, fakes)

    assert steps.page.connect_button.cget("text") == "Connect"
    assert steps.page.paste_frame.grid_info() == {}  # the key box waits under Paste a key instead
    steps.page.paste_link.invoke()
    assert steps.page.paste_frame.grid_info()


def test_connect_brings_back_a_key_and_next_turns_on(root, fakes, monkeypatch):
    monkeypatch.setenv("COMPUTERNAME", "LAPTOP-AN")
    _, steps = start(root, fakes)

    steps.page.connect_button.invoke()

    assert fakes.browsed == [f"{SERVER}/school/devices/connect?name=LAPTOP-AN"]
    assert steps.page.answer.get() == f"✓ Connected to {ME}."
    assert steps.accepted == (SERVER, NEW_KEY)
    assert not disabled(steps.page.next_button)


def test_step_2_saves_the_key_connect_brought_back(root, fakes):
    _, steps = start(root, fakes)
    steps.page.connect_button.invoke()
    steps.page.next_button.invoke()
    steps.values["student_id"].set(STUDENT)
    steps.values["password"].set(PASSWORD)

    steps.page.next_button.invoke()

    assert credentials.load_device_key(SERVER) == NEW_KEY


def test_while_connect_waits_the_page_says_to_finish_in_the_browser(root, fakes):
    held = []
    _, steps = start(root, fakes, run=lambda work, done: held.append((work, done)))

    steps.page.connect_button.invoke()

    assert steps.page.answer.get() == setup_steps.WAITING
    assert disabled(steps.page.next_button)


def test_only_the_newest_press_of_connect_counts(root, fakes):  # Review Focus 3
    held = []
    _, steps = start(root, fakes, run=lambda work, done: held.append((work, done)))
    steps.page.connect_button.invoke()
    fakes.answer = Cancelled()
    steps.page.connect_button.invoke()
    (first_work, first_done), (second_work, second_done) = held

    assert fakes.connections[0].closed  # the first listener stopped
    first_done(first_work())  # the first press's late answer is dropped
    assert steps.page.answer.get() == setup_steps.WAITING
    second_done(second_work())
    assert steps.page.answer.get() == "✗ " + accounts.CONNECT_CANCELLED


def test_cancel_on_the_website_says_so_and_saves_nothing(root, fakes):
    fakes.answer = Cancelled()
    _, steps = start(root, fakes)

    steps.page.connect_button.invoke()

    assert steps.page.answer.get() == "✗ " + accounts.CONNECT_CANCELLED
    assert disabled(steps.page.next_button)
    assert steps.accepted is None


def test_a_laptop_that_cant_listen_says_to_paste_a_key(root, fakes):
    fakes.listen_error = OSError("blocked")
    _, steps = start(root, fakes)

    steps.page.connect_button.invoke()

    assert steps.page.answer.get() == "✗ " + accounts.CONNECT_UNAVAILABLE
    assert fakes.browsed == []


def test_leaving_step_1_stops_listening(root, fakes):
    held = []
    _, steps = start(root, fakes, run=lambda work, done: held.append((work, done)))
    steps.page.connect_button.invoke()
    steps.values["key"].set(KEY)  # a key pasted meanwhile
    work, done = held.pop()  # its check
    done(work())

    steps.page.next_button.invoke()

    assert fakes.connections[0].closed


def test_closing_the_window_stops_listening(root, fakes, monkeypatch):  # Review Focus 1
    held = []
    app, steps = start(root, fakes, run=lambda work, done: held.append((work, done)))
    steps.page.connect_button.invoke()
    monkeypatch.setattr(root, "destroy", lambda: None)  # the root fixture destroys it after the test

    app.close()

    assert fakes.connections[0].closed
```

(The existing step 1 tests keep working unchanged: `open_button`, `paste_button` and the `key` box still exist, inside the paste frame; a hidden button still answers `invoke()`.)

- [ ] **Step 2: Write the failing tests for Accounts → Website**

In `agent/tests/test_window.py`, change the import line

```python
from agent.tests.accounts_fakes import BB_PASSWORD, BB_USER, ME, SERVER, STUDENT, Fakes, set_up
```

to

```python
from agent.tests.accounts_fakes import BB_PASSWORD, BB_USER, KEY, ME, NEW_KEY, SERVER, STUDENT, Fakes, set_up
```

add `from sla_agent.connect import Cancelled` after the `from sla_agent import …` line, and append:

```python


# ---- Website: Connect (spec 2026-10-04-connect-button-design.md) -------------------------------------------------


def test_accounts_website_connect_saves_the_new_key_at_once(root, fakes):
    set_up()
    app = open_window(root, fakes)
    app.screen.open("site")

    app.screen.editor.connect_button.invoke()

    assert app.screen.editor is None
    assert app.screen.notice.get() == f"✓ Saved. This laptop now syncs with {SERVER}."
    assert credentials.load_device_key(SERVER) == NEW_KEY


def test_accounts_website_keeps_paste_a_key_instead(root, fakes):
    set_up()
    app = open_window(root, fakes)
    app.screen.open("site")
    editor = app.screen.editor

    assert editor.key_row.grid_info() == {}
    editor.paste_link.invoke()
    assert editor.key_row.grid_info()
    editor.values["key"].set(NEW_KEY)
    editor.save()

    assert credentials.load_device_key(SERVER) == NEW_KEY


def test_a_website_connect_that_fails_keeps_the_old_key_and_says_why(root, fakes):
    set_up()
    fakes.answer = Cancelled()
    app = open_window(root, fakes)
    app.screen.open("site")

    app.screen.editor.connect_button.invoke()

    assert app.screen.answers["site"].get() == "✗ " + accounts.CONNECT_CANCELLED
    assert credentials.load_device_key(SERVER) == KEY


def test_cancelling_the_website_editor_stops_listening(root, fakes):
    set_up()
    held = []
    app = open_window(root, fakes, run=lambda work, done: held.append((work, done)))
    app.screen.open("site")
    app.screen.editor.connect_button.invoke()

    app.screen.editor.cancel()

    assert fakes.connections[0].closed
```

- [ ] **Step 3: Run them to see them fail**

Run: `.\.venv\Scripts\python.exe -m pytest agent/tests/test_setup_steps.py agent/tests/test_window.py`
Expected: FAIL: the new tests raise `AttributeError: 'SitePage' object has no attribute 'connect_button'` and `AttributeError: 'Editor' object has no attribute 'connect_button'` (`connect_site` is there since Task 5, but nothing on screen calls it yet).

- [ ] **Step 4: Step 1 in `setup_steps.py`**

1. In the module docstring, change

```
each with its own guide, instead of one long form. Step 1 connects to the website with a device key and saves
nothing; step 2 checks EduSoft, then saves both and turns on automatic sync; steps 3 and 4 add Blackboard and
Outlook, or skip them; the last page asks about the Desktop icon and opens School-Life-Assistant.
```

to

```
each with its own guide, instead of one long form. Step 1 connects to the website with Connect (spec
2026-10-04-connect-button-design.md) or a pasted device key, and saves nothing; step 2 checks EduSoft, then saves
both and turns on automatic sync; steps 3 and 4 add Blackboard and Outlook, or skip them; the last page asks about
the Desktop icon and opens School-Life-Assistant.
```

2. Make `import logging` the first import, and add after the imports (before `LOCAL_ADDRESS = …`):

```python
log = logging.getLogger(__name__)

```

3. After the line `DESKTOP_QUESTION = "Put a School-Life-Assistant icon on your Desktop, to open it quickly?"`, add:

```python
CONNECT_LINE = "Press Connect. Your browser opens: log in (or create an account), then press Connect there."
WAITING = "Finish in your browser…"
PASTE_INSTEAD = "Paste a key instead"
```

4. In `SetupSteps.__init__`, after `self.accepted = None  # (address, key)`, add:

```python
        self.connecting = None  # the connection of the newest press of Connect
```

5. Replace `SetupSteps.show` with:

```python
    def show(self, page_class):
        if self.page is not None:
            if isinstance(self.page, SitePage):
                self.app.stop_listening()  # leaving step 1 (a key pasted meanwhile): Connect's wait ends
            self.page.frame.destroy()
        page_class(self)  # it makes itself self.page before it builds anything
```

6. Replace the whole `class SitePage(Page):` (everything up to `class EdusoftPage(Page):`) with:

```python
class SitePage(Page):
    """Step 1: Connect (spec 2026-10-04-connect-button-design.md, 2), or a device key pasted under Paste a key instead,
    with today's guide (spec 2026-10-02-easy-install-design.md, 3). Nothing is saved yet: a key the website accepts
    turns Next on."""

    title = "Connect to the website"

    def __init__(self, steps):
        super().__init__(steps)
        self.add_line(CONNECT_LINE)
        self.connect_button = ttk.Button(self.frame, text="Connect", command=self.connect)
        self.connect_button.grid(row=self.next_row(), column=0, columnspan=3, sticky="w", pady=(4, 8))
        self.add_answer()
        self.address_row = self.next_row()
        self.address_box = self.change_button = None
        self.show_address()
        self.paste_link = ttk.Button(self.frame, text=PASTE_INSTEAD, style="Toolbutton", command=self.show_paste)
        self.paste_link.grid(row=self.next_row(), column=0, columnspan=3, sticky="w", pady=(8, 0))
        self.paste_frame = self.paste_area()
        self.paste_frame.grid(row=self.next_row(), column=0, columnspan=3, sticky="ew")
        self.paste_frame.grid_remove()  # until Paste a key instead
        [self.next_button] = self.add_bar(("Next →", lambda: steps.show(EdusoftPage)))
        self.key_changed()  # coming Back from step 2, the accepted key is still there

    def paste_area(self):
        """Today's guide, Open the website, the key box and Paste, in a frame that Paste a key instead shows."""
        area = ttk.Frame(self.frame)
        area.columnconfigure(1, weight=1)
        for row, text in enumerate(SITE_STEPS):
            text_line(area, text, row)
        self.open_button = ttk.Button(area, text="Open the website", command=self.open_site)
        self.open_button.grid(row=len(SITE_STEPS), column=0, columnspan=3, sticky="w", pady=(4, 8))
        key_row = len(SITE_STEPS) + 1
        self.paste_button = ttk.Button(area, text="Paste", command=self.paste)
        self.paste_button.grid(row=key_row, column=2, padx=(8, 0))
        field(area, "Device key", self.steps.values["key"], key_row, secret=True)
        return area

    def show_paste(self):
        self.paste_frame.grid()

    def show_address(self):
        """"Website: <address>" with Change, or, once Change was pressed, the address box. Enter, or leaving the
        box, checks the key with the new address (each keystroke would ask a half-typed one)."""
        if self.address_box is not None:
            self.address_box.destroy()
        self.address_box = ttk.Frame(self.frame)
        self.address_box.grid(row=self.address_row, column=0, columnspan=3, sticky="ew", pady=(8, 0))
        self.address_box.columnconfigure(1, weight=1)
        if self.steps.address_open:
            self.change_button = None
            box = field(self.address_box, "Website", self.steps.values["address"], 0)
            for event in ("<Return>", "<FocusOut>"):
                box.bind(event, lambda event: self.key_changed())
            return
        ttk.Label(self.address_box, text="Website:", foreground="gray").grid(row=0, column=0, sticky="w")
        ttk.Label(self.address_box, textvariable=self.steps.values["address"], foreground="gray").grid(
            row=0, column=1, sticky="w", padx=(4, 0))
        self.change_button = ttk.Button(self.address_box, text="Change", command=self.change)
        self.change_button.grid(row=0, column=2, padx=(8, 0))

    def change(self):
        self.steps.address_open = True
        self.show_address()

    def open_site(self):
        webbrowser.open(self.steps.values["address"].get().strip().rstrip("/") + "/school/devices")

    def paste(self):
        text = read_clipboard(self.app.root).strip()
        if not accounts.is_device_key(text):
            self.answer.set(mark(Result(False, NOT_A_KEY)))
            return
        self.steps.values["key"].set(text)  # checked by key_changed

    def fill_from_clipboard(self):
        """A device key copied meanwhile fills the empty box; anything else on the clipboard is left alone, and the
        clipboard isn't even read while the box holds something (spec 2026-10-02-easy-install-design.md, 9)."""
        if self.steps.values["key"].get().strip():
            return
        text = read_clipboard(self.app.root).strip()
        if accounts.is_device_key(text):
            self.steps.values["key"].set(text)

    def key_changed(self):
        """Check a key-shaped key with the website at once; say so for any other text. Next only once accepted."""
        address = self.steps.values["address"].get().strip()
        key = self.steps.values["key"].get().strip()
        self.next_button.state(["disabled"])
        self.steps.checking = (address, key)
        if not key:
            self.answer.set("")
        elif not accounts.is_device_key(key):
            self.answer.set(mark(Result(False, NOT_A_KEY)))
        elif self.steps.accepted == (address, key):
            self.checked(address, key, Result(True, accounts.SITE_ACCEPTED))
        else:
            protect(key)
            self.answer.set(CHECKING)
            self.app.run(lambda: accounts.check_site(address, key, self.app.tools),
                         lambda result: self.checked(address, key, result))

    def checked(self, address, key, result):
        if not self.current() or self.steps.checking != (address, key):  # left, or changed again meanwhile
            return
        if result.ok:
            self.steps.accepted = (address, key)
            self.free(self.next_button)
        self.answer.set(mark(result))

    def connect(self):
        """Connect: listen, open the browser, and wait for the key (accounts.connect_laptop) on a worker thread."""
        address = self.steps.values["address"].get().strip()
        try:
            connection = self.app.listen()
        except OSError as error:
            log.warning("Connect: can't listen on 127.0.0.1 (%s)", error)
            self.answer.set(mark(Result(False, accounts.CONNECT_UNAVAILABLE)))
            return
        self.steps.connecting = connection
        self.answer.set(WAITING)
        self.app.run(lambda: accounts.connect_laptop(address, connection, self.app.tools),
                     lambda answer: self.connected(address, connection, answer))

    def connected(self, address, connection, answer):
        """Connect's answer: (Result, key), or a bare Result when something unexpected went wrong (window_parts)."""
        if not self.current() or self.steps.connecting is not connection:  # left, or Connect pressed again
            return
        result, key = answer if isinstance(answer, tuple) else (answer, "")
        if not result.ok:
            if result.message:  # an empty one: closed meanwhile, nothing to say
                self.answer.set(mark(result))
            return
        self.steps.accepted = (address, key)
        self.steps.values["key"].set(key)  # key_changed finds it accepted and turns Next on
        self.answer.set(mark(result))
        self.app.bring_forward()
```

(Only `__init__`, `paste_area`, `show_paste`, `connect` and `connected` are new; the other methods are today's, unchanged.)

- [ ] **Step 5: The window in `window.py`**

1. Change `from sla_agent.setup_steps import SetupSteps` to

```python
from sla_agent.setup_steps import PASTE_INSTEAD, WAITING, SetupSteps
```

2. Replace `App`'s docstring and `__init__` with the following, make `self.stop_listening()` the first line of `App.show`, and add the four methods after `show`:

```python
class App:
    """The window: the setup pages (setup_steps.SetupSteps) or Accounts (AccountsScreen), rebuilt after each save. It
    holds Connect's listener, one at a time: a new press of Connect closes the old one, and rebuilding, leaving step 1,
    cancelling the Website editor or closing the window stops it (spec 2026-10-04-connect-button-design.md, 5.3)."""

    def __init__(self, root, tools, run=None, notice=""):
        self.root, self.tools = root, tools
        self.run = run or run_in_background(root)
        self.connection = None  # Connect's listener (connect.Connection), while it waits
        root.title(TITLE)
        root.minsize(560, 0)
        root.columnconfigure(0, weight=1)
        root.protocol("WM_DELETE_WINDOW", self.close)
        self.body = None
        self.screen = None
        self.show(notice)
```

```python
    def listen(self):
        """A new listener for Connect (tools.listen), closing the one before. Raises OSError when it can't listen."""
        self.stop_listening()
        self.connection = self.tools.listen()
        return self.connection

    def stop_listening(self):
        """Ends Connect's wait, if one is running: its answer comes back Closed and is dropped."""
        if self.connection is not None:
            self.connection.close()
            self.connection = None

    def bring_forward(self):
        """Back in front of the browser after Connect (Windows may only flash the taskbar button)."""
        self.root.lift()
        self.root.attributes("-topmost", True)
        self.root.after(200, lambda: self.root.attributes("-topmost", False))

    def close(self):
        """The window's ✕. Connect's wait runs on a thread that isn't a daemon, so it is ended first; otherwise the app
        would stay in the background until the wait ran out."""
        self.stop_listening()
        self.root.destroy()
```

3. In `Editor`, change the Website's fields to the address alone:

```python
        "site": (("Address", "address", False),),
```

4. In `Editor.__init__`, right after the `for index, (label, name, secret) in enumerate(self.FIELDS[row]):` loop (before `if row == "outlook":`), add:

```python
        self.connecting = None  # Website: the connection of the newest press of Connect
        if row == "site":
            self.add_connect()
```

5. Add these methods to `Editor` (after `__init__`):

```python
    def add_connect(self):
        """Website: Connect next to the address, and today's key box under Paste a key instead (spec
        2026-10-04-connect-button-design.md, 2)."""
        self.connect_button = ttk.Button(self.frame, text="Connect", command=self.connect)
        self.connect_button.grid(row=0, column=2, padx=(8, 0))
        self.values["key"] = tk.StringVar(self.frame)
        self.paste_link = ttk.Button(self.frame, text=PASTE_INSTEAD, style="Toolbutton", command=self.show_paste)
        self.paste_link.grid(row=1, column=0, columnspan=3, sticky="w")
        self.key_row = ttk.Frame(self.frame)
        self.key_row.grid(row=2, column=0, columnspan=3, sticky="ew")
        self.key_row.columnconfigure(1, weight=1)
        field(self.key_row, "Device key", self.values["key"], 0, secret=True)
        self.key_row.grid_remove()  # until Paste a key instead

    def show_paste(self):
        self.key_row.grid()

    def connect(self):
        app = self.screen.app
        address = self.values["address"].get().strip()
        try:
            connection = app.listen()
        except OSError as error:
            log.warning("Connect: can't listen on 127.0.0.1 (%s)", error)
            self.screen.answers["site"].set(mark(accounts.Result(False, accounts.CONNECT_UNAVAILABLE)))
            return
        self.connecting = connection
        self.screen.answers["site"].set(WAITING)
        tools, state = app.tools, load_state()
        app.run(lambda: accounts.connect_site(state, address, connection, tools),
                lambda result: self.connected(connection, result))

    def connected(self, connection, result):
        if self.connecting is not connection or not self.frame.winfo_exists():  # pressed again, or closed
            return
        if not result.message:  # closed meanwhile: nothing to say
            return
        if result.ok:
            self.screen.app.bring_forward()
        self.saved(result)
```

6. Make `self.screen.app.stop_listening()` the first line of `Editor.cancel`:

```python
    def cancel(self):
        self.screen.app.stop_listening()
        self.frame.destroy()
        self.screen.answers[self.row].set("")
        self.screen.closed()
```

- [ ] **Step 6: Run the tests to see them pass**

Run: `.\.venv\Scripts\python.exe -m pytest agent/tests/test_setup_steps.py agent/tests/test_window.py`
Expected: PASS (old and new).

- [ ] **Step 7: Run everything**

Run: `.\.venv\Scripts\python.exe -m pytest` → **885 passed**.

- [ ] **Step 8: Look at it**

Start the website on `http://localhost:5000` in another window (`cd web; .\mvnw.cmd spring-boot:run`). Then open the window with a throwaway agent folder, so this laptop's real setup isn't touched:

```powershell
$env:SLA_AGENT_HOME = "$env:TEMP\sla-connect-try"
.\.venv\Scripts\python.exe -m sla_agent window
Remove-Item Env:SLA_AGENT_HOME
```

Press **Connect**: the browser opens the Connect page; log in, press **Connect**; the tab says "✓ Done…", the window says "✓ Connected to …" and **Next** turns on. Press **Paste a key instead**: today's guide and key box appear. Press **Connect** and close the window while it waits: the process ends at once (no `python` left in Task Manager). **Stop at step 1**: don't go through step 2 from the source copy, because it would point this laptop's automatic sync at it. Afterwards, press **Cancel device** next to the test laptop on the site's Devices page.

- [ ] **Step 9: Commit**

```powershell
git add agent/sla_agent/setup_steps.py agent/sla_agent/window.py agent/tests/test_setup_steps.py agent/tests/test_window.py
git commit -m "feat(agent): step 1 and Accounts' Website lead with Connect; pasting a key stays under Paste a key instead" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Texts, version 0.4.0, and the release

The README and the download page still tell students to copy a key. Version 0.4.0 marks the release. Then the website goes online first, and only after that is `v0.4.0` tagged.

**Files:**
- Modify: `README.md` (four places)
- Modify: `pages/index.html` (step 3, in English and Vietnamese)
- Modify: `agent/sla_agent/__init__.py` (version)
- Modify: `docs/superpowers/specs/2026-10-04-connect-button-design.md` (status)

**Interfaces:**
- Consumes: everything above, working.
- Produces: release `v0.4.0` on GitHub (built by the `release` workflow from the tag).

- [ ] **Step 1: The README**

In `README.md`:

1. Change

```
   1. Connect to the website. It shows how to get a device key from School → Devices, and fills the key in when you copy it.
```

to

```
   1. Connect to the website: press **Connect**, log in (or create an account) in the browser, and press **Connect** there. There's no key to copy.
```

2. In "Putting the site online", step 3, change

```
A laptop that runs the agent from source enters the online address (**Change** on the setup's first page) and a device key from School → Devices, or, when already set up, presses **Change** next to Website in Accounts.
```

to

```
A laptop that runs the agent from source enters the online address (**Change** on the setup's first page) and presses **Connect**, or, when already set up, presses **Change** next to Website in Accounts, enters the address and presses **Connect**.
```

3. In "Always on: AWS Lightsail", change step 7

```
7. **Your laptop.** On the new site, create a device key in School → Devices; then in School-Life-Assistant's Accounts, press **Change** next to Website and enter the new address and the key.
```

to

```
7. **Your laptop.** In School-Life-Assistant's Accounts, press **Change** next to Website, enter the new address, and press **Connect**.
```

4. In "The laptop agent from source (developers)", delete step 2 (`2. **Get a device key.** With the site running, open School → Devices, add your laptop and copy its key. It is shown only once.` and the blank line after it), and change the beginning of the next step from

```
3. **Set it up:** double-click `School-Life-Assistant.cmd` in the project folder. The setup pages open, with the web app address `http://localhost:5000`: paste the device key (step 1), enter your EduSoft student ID and password (step 2),
```

to

```
2. **Set it up:** with the site running, double-click `School-Life-Assistant.cmd` in the project folder. The setup pages open, with the web app address `http://localhost:5000`: press **Connect** and log in to your site in the browser (step 1), enter your EduSoft student ID and password (step 2),
```

(the rest of that paragraph stays).

- [ ] **Step 2: The download page, in both languages**

In `pages/index.html`, change the English step 3

```html
      <li><strong>The School-Life-Assistant window opens</strong> and walks you through the rest: a device key from
        the website, your EduSoft login, then Blackboard and Outlook if you want them, and an icon on your Desktop if
        you like.</li>
```

to

```html
      <li><strong>The School-Life-Assistant window opens</strong> and walks you through the rest: connecting to the
        website (press <b>Connect</b> and log in), your EduSoft login, then Blackboard and Outlook if you want them,
        and an icon on your Desktop if you like.</li>
```

and the Vietnamese step 3

```html
      <li><strong>Cửa sổ School-Life-Assistant sẽ mở ra</strong> và hướng dẫn bạn phần còn lại: mã thiết bị
        (device key) từ trang web, tài khoản EduSoft, rồi Blackboard và Outlook nếu bạn muốn, và biểu tượng trên màn
        hình nền (Desktop) nếu bạn thích.</li>
```

to

```html
      <li><strong>Cửa sổ School-Life-Assistant sẽ mở ra</strong> và hướng dẫn bạn phần còn lại: kết nối với trang web
        (nhấn <b>Connect</b> rồi đăng nhập), tài khoản EduSoft, rồi Blackboard và Outlook nếu bạn muốn, và biểu tượng
        trên màn hình nền (Desktop) nếu bạn thích.</li>
```

Run: `.\.venv\Scripts\python.exe -m pytest deploy/tests/test_download_page.py`
Expected: PASS (still three steps and three questions in each language).

- [ ] **Step 3: Version 0.4.0, and the spec is built**

In `agent/sla_agent/__init__.py`, change `__version__ = "0.3.0"` to `__version__ = "0.4.0"`.

In `docs/superpowers/specs/2026-10-04-connect-button-design.md`, change `**Status:** Draft, waiting for review` to `**Status:** Built (see docs/superpowers/plans/2026-10-04-connect-button.md)`.

- [ ] **Step 4: Run everything**

Run: `.\.venv\Scripts\python.exe -m pytest` → **885 passed**.
Run: `cd web; .\mvnw.cmd test; cd ..` → **Tests run: 689, Failures: 0, Errors: 0, Skipped: 0**.

- [ ] **Step 5: Commit**

```powershell
git add README.md pages/index.html
git commit -m "docs: the README and the download page say to press Connect, not to copy a device key" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git add agent/sla_agent/__init__.py docs/superpowers/specs/2026-10-04-connect-button-design.md
git commit -m "chore(agent): version 0.4.0; the Connect spec is built" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 6: Merge into main**

```powershell
git switch main
git merge --no-ff connect-button -m "Merge branch 'connect-button': Connect this laptop, with no device key to copy (0.4.0)" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

Run both suites again on `main` (Step 4's commands): same counts.

- [ ] **Step 7 (outward: ask first): Push**

With your human partner's go-ahead, push to the three repositories, one at a time, and report each:

```powershell
git push origin main
git push dsa HEAD:main
git push ooad HEAD:main
```

If `dsa` or `ooad` refuses a non-fast-forward push, stop and ask; never force-push.

- [ ] **Step 8 (outward: ask first): Put the website online**

On the server (Lightsail → **Connect using SSH**; paste one line at a time):

```bash
bash ~/School-Life-Assistant/deploy/oracle/update.sh
```

About a minute after it ends, open this link in a browser while logged in to the site:

`https://school-life-assistant.duckdns.org/school/devices/connect?port=51234&state=state-0123456789_abcdefghijklmnopqrstuvwxyz&challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&name=TEST`

Expected: "Connect this laptop? **TEST** will upload your timetable, exams and tuition to the account …". Press **Cancel**: the browser then fails to open `127.0.0.1:51234`, which is right (nothing listens there). Don't press Connect on this test link.

- [ ] **Step 9 (outward: ask first): Release 0.4.0**

Only after Step 8 shows the Connect page:

```powershell
git tag -a v0.4.0 -m "School-Life-Assistant 0.4.0: Connect this laptop, with no device key to copy"
git push origin v0.4.0
```

GitHub Actions' `release` workflow builds the app, starts it, and publishes it. When it has passed, `https://github.com/nguyenkhangvy/School-Life-Assistant/releases/latest/download/School-Life-Assistant.exe` should lead to `v0.4.0`. Installed laptops (the student's own runs 0.3.0) update themselves within a day.

- [ ] **Step 10: The by-hand test (spec, section 6)**

With the released `.exe` and the live site:

1. In a fresh Windows account: download, install, **Connect** → **Create one** → register → back on the Connect page → **Connect** → the window says "✓ Connected to …" → finish setup.
2. Accounts → Website → **Change** → **Connect** → "Saved. This laptop now syncs with …".
3. Do 1's Connect once in Edge and once in Chrome.
4. Close the window during a Connect: no School-Life-Assistant process stays in Task Manager.

Then upload `School-Life-Assistant.exe` to virustotal.com. If Microsoft Defender flags it, report it as a false positive at microsoft.com/wdsi/filesubmission (Software developer; Microsoft Defender Antivirus; "Incorrectly detected as malware"), as was done for 0.3.0, before telling classmates.
