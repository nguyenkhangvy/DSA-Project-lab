# Tuition Bills from IUPay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The laptop agent reads the student's tuition bills from IUPay with only the student ID, instead of EduSoft's tuition report. The website stores them, shows what is left to pay and the paid history on the Tuition page, and tells the student on the Overview page: a notice while something is unpaid, and a Bills list of bills not yet paid and payments from the last 30 days.

**Architecture:** The upload format (Python `schema.py` and its Java twin `SyncContract.java`) gains an `iupay` part holding every bill. The old `tuition` part is still accepted from agents that haven't been updated, but ignored. The agent gets a small `IupayClient` (the three GET requests IUPay's own page makes) and a pure `parse_iupay` reader, and runs it as its own part of each sync, independent of EduSoft. The website replaces the student's rows in `school_tuition_bills` on each good read and records the time in `school_tuition_status`. A pure `TuitionBills` helper sorts and sums them for the Tuition page and the Overview, and `SyncStatus` gets an IUPay line. The old `school_tuition` table and `SchoolTuition` go.

**Tech Stack:** Python 3.12, pydantic, requests, `responses` (tests), pytest (agent and shared format); Java 17, Spring Boot 4.1.1 (Spring MVC, Thymeleaf, Spring Data JPA / Hibernate 7, Flyway), JUnit 5 + MockMvc, H2 for tests.

**Spec:** `docs/superpowers/specs/2026-09-30-iupay-tuition-design.md` (approved 2026-09-30). Section numbers below (§3.3, §4.4 …) are that spec's.

**Starting point:** branch `outlook-mailbox` at `3bcc1bd` plus the spec update committed with this plan. Before this plan, every Python test passes (`.venv/Scripts/python.exe -m pytest -q`, checked 2026-09-30) and so does the Java suite (`(cd web && ./mvnw -B test)`).

## Global Constraints

- **Only bill fields leave the laptop** (§3.3, §6): bill number, term code and name, description, fee type, amount, discount, fee, status, due date, paid date, channel. IUPay's `student` block (name, email, class, faculty), the lookup code (`secretCode`) and every other field are dropped in memory. The upload format refuses unknown fields.
- **The lookup code is a secret:** it goes through `sla_agent.log.protect` before it is used in a URL, and is never saved.
- **Read only:** the agent never pays, never solves a captcha, and makes only the three GETs of §3.1, to `https://api.mybill.aqtech.vn/` only (redirects elsewhere are refused by `guarded_request`).
- **IUPay never pauses anything:** it has no password to lock out. Every IUPay problem fails only the `iupay` part; the next sync tries again.
- Status strings are exactly `unpaid`, `paid`, `paying`, `partly_paid` (from IUPay's `trang_thai` 0, 1, 2, 3). Any other number fails the part with `source_changed`.
- Dates (`due_date`, `paid_on`) are **Vietnam dates** (UTC+7) of IUPay's epoch-millisecond times. `school_tuition_status.checked_at` is UTC, shown in Vietnam time, like every other stored time.
- Every query is filtered by the logged-in user.
- `spring.jpa.hibernate.ddl-auto=validate`: every entity must match a table, so `SchoolTuition` is deleted in the same task that drops `school_tuition` (Task 6).
- Bills never add to "What changed" (`school_changes`).
- The site is always light: white and light-grey backgrounds, black text, no dark mode.
- Java 17, no new dependencies (Java or Python). Migrations are named `V<date>_<module>_<n>__<what>.sql` (School = 1).
- Work on the branch `outlook-mailbox` in the main working tree. Real syncs (Task 9) run in the main session only, never a subagent. Never print `.env` values, the device key, or the student's real student ID in files that get committed.
- Commands are for Git Bash, run from the repository root. Python is `.venv/Scripts/python.exe`. If `.venv/Scripts/python.exe -c "import sla_contract"` fails, run `.venv/Scripts/python.exe -m pip install --no-deps -e ./contract -e ./agent` first. The site may be running from `mvn spring-boot:run` with devtools; running the Java tests recompiles `web/target/classes` and may restart it, which is fine.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

- **A Vietnam midnight is not the UTC day:** IUPay's `ngay_thu` for bill `4073743` is 2025-10-01 00:00 in Vietnam, 2025-09-30 in UTC. The paid date must be 01/10/2025 (Task 2 pins it with the real sample).
- **An old agent's `tuition` part** must neither fail the upload nor count toward success/partial/failed, and must still be refused when it comes with a whole-run error (Task 4 pins all three, in Python and Java).
- **A bill whose `noi_dung` has `<br>` or other tags** must reach the page as plain lines, never as HTML (Task 2 turns tags into text; Task 6 renders with `th:text` and `white-space: pre-line`).
- **IUPay unreachable, or its captcha switched on, while EduSoft is paused** must still upload the EduSoft pause and the IUPay failure, and pause nothing new (Task 3).
- **Another student's bills** must never appear on the Tuition page, the Overview notice or the Bills list (Tasks 6 and 7 each pin it).

## Decided while planning (2026-09-30)

- "Last checked on IUPay" comes from a one-row-per-student table, `school_tuition_status`, like `school_mail_status` for Outlook, instead of searching the sync runs' JSON. The spec was updated to say so.
- `school_tuition` is dropped in its own migration (`V20260930_1_2`), in the task that replaces the Tuition page, because `ddl-auto=validate` needs `SchoolTuition` and its table to go together.
- The "Pay on IUPay ↗" link becomes a fragment in `school/fragments.html`, since the Tuition page shows it in up to three places.
- "Last checked" uses the existing `SchoolFormat.when` ("Wed 30/09 11:14"). Paid dates in the Bills list use `SchoolFormat.dayLabel` ("Paid Wed 30/09:").

## File Structure

**Shared format**
- `contract/sla_contract/schema.py`: `TuitionBill`, `Iupay`, `IupayResult`, `FinishRun.iupay` (Task 1); `EDUSOFT_SECTIONS` without tuition (Task 3); `tuition` ignored (Task 4).
- `contract/samples/finish-iupay.json`, `finish-iupay-unpaid.json` (Task 1), `finish-old-agent-tuition.json`, `invalid/whole-run-error-with-old-tuition.json` (Task 4); `finish-edusoft.json`, `finish-parts-failed.json`, `invalid/ok-part-without-data.json`, `invalid/unknown-field.json`, `invalid/whole-run-error-with-data.json` move off `tuition` (Task 4).
- `contract/tests/test_contract.py`.

**Agent**
- `agent/sla_agent/parsers/iupay.py` (new, Task 2): `parse_iupay(reply) -> Iupay`.
- `agent/sla_agent/iupay_client.py` (new, Task 3): `IupayClient.read_bills(student_id) -> dict`.
- `agent/sla_agent/sync.py` (Task 3): `collect_iupay`, `run_sync(..., iupay=None)`, `everything_paused`.
- `agent/sla_agent/cli.py`, `scheduler.py`, `edusoft_client.py`, `parsers/__init__.py`, `errors.py` (Task 3). Deleted: `parsers/tuition.py`, `tests/fixtures/tuition-report.json`.
- Tests: `agent/tests/test_iupay.py` (new), `fixtures/iupay-bills.json` (new), `fakes.py`, `test_sync.py`, `test_cli.py`, `test_parsers.py`, `test_edusoft_client.py`.

**Website** (`web/src/main/...` and `web/src/test/...`, package `vn.edu.hcmiu.sla.school`)
- `sync/SyncContract.java` (Tasks 1 and 4), `sync/Ingest.java` (Tasks 4 and 5), `sync/Changes.java` (Task 4).
- `resources/db/migration/V20260930_1_1__tuition_bills.sql` (Task 5), `V20260930_1_2__drop_school_tuition.sql` (Task 6).
- `model/SchoolTuitionBill.java`, `SchoolTuitionBillRepository.java`, `SchoolTuitionStatus.java`, `SchoolTuitionStatusRepository.java` (new, Task 5). Deleted: `model/SchoolTuition.java`, `SchoolTuitionRepository.java` (Task 6).
- `pages/TuitionBills.java` (new, Task 6), `pages/SchoolFormat.java`, `pages/SchoolController.java` (Tasks 6 and 7), `pages/SyncStatus.java` (Task 8).
- `resources/templates/school/tuition.html`, `fragments.html` (Task 6), `index.html` (Task 7), `static/css/style.css` (Tasks 6 and 7).
- Tests: `sync/SyncContractTest.java`, `sync/Payloads.java`, `sync/IngestTest.java`, `sync/ChangesTest.java`, `sync/SyncApiTest.java`, `model/SchoolTablesTest.java`, `core/MigrationTest.java`, `SchoolTestData.java`, `pages/TuitionBillsTest.java` (new), `pages/SchoolPagesTest.java`, `pages/SyncStatusTest.java`.

---

### Task 1: The `iupay` part in the upload format

Adds the new part next to the others. Nothing is removed yet, so every existing test keeps passing.

**Files:**
- Modify: `contract/sla_contract/schema.py`
- Create: `contract/samples/finish-iupay.json`, `contract/samples/finish-iupay-unpaid.json`
- Modify: `contract/tests/test_contract.py`
- Modify: `web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncContract.java`
- Modify: `web/src/test/java/vn/edu/hcmiu/sla/school/sync/Payloads.java`, `SyncContractTest.java`

**Interfaces:**
- Produces (Python): `TuitionBill` (fields `bill_no, term_code, term_name, description, fee_type, amount, discount, fee, status, due_date, paid_on, channel`), `Iupay(bills: list[TuitionBill])`, `BillStatus`, `FinishRun.iupay`. `SECTION_NAMES == ("timetable", "exams", "tuition", "iupay", "blackboard", "outlook")`.
- Produces (Java): `SyncContract.TuitionBill` (record, same fields in camelCase; `discount()` and `fee()` never null), `SyncContract.Iupay(List<TuitionBill> bills)`, `FinishRun.iupay()`; `Payloads.iupayPayload()`, `Payloads.iupayUnpaidPayload()` return the `data` maps of the two new samples.

- [ ] **Step 1: Write the two samples**

`contract/samples/finish-iupay.json`: the student's real IUPay bills of 2026-09-30, as the agent will upload them (nothing personal: bill numbers, amounts and dates identify no one):

```json
{
  "schema_version": 1,
  "iupay": {
    "status": "ok",
    "data": {
      "bills": [
        {"bill_no": "E0000014104", "term_code": "20261", "term_name": "Academic year 2026-2027 - Semester 1",
         "description": "Thu Học Phí HK 1 (2026-2027)", "fee_type": "Thu Học Phí", "amount": 65250000,
         "discount": 0, "fee": 0, "status": "paid", "paid_on": "2026-09-30", "channel": "Đóng qua kênh EduBill"},
        {"bill_no": "4073743", "term_code": "20251", "term_name": "Academic year 2025-2026 - Semester 1",
         "description": "Thu học phí học kỳ 1/2025-2026", "fee_type": "Thu Học Phí", "amount": 36900000,
         "discount": 0, "fee": 0, "status": "paid", "paid_on": "2025-10-01", "channel": "Đóng offline"},
        {"bill_no": "E0000000208", "term_code": "20252", "term_name": "Academic year 2025-2026 - Semester 2",
         "description": "Thu Học Phí HK 2 (2025-2026)", "fee_type": "Thu Học Phí", "amount": 40273756,
         "discount": 0, "fee": 0, "status": "paid", "paid_on": "2026-01-16", "channel": "Đóng qua kênh EduBill"},
        {"bill_no": "4073742", "term_code": "20242", "term_name": "Academic year 2024-2025 - Semester 2",
         "description": "Chuyen tien du tu LPTTDN qua HP", "fee_type": "Thu Học Phí", "amount": 216350,
         "discount": 0, "fee": 0, "status": "paid", "paid_on": "2025-05-10", "channel": "Đóng offline"},
        {"bill_no": "4073739", "term_code": "20241", "term_name": "Academic year 2024-2025 - Semester 1",
         "description": "Tạm thu học phi dau nam Khoa 2024", "fee_type": "Thu Học Phí", "amount": 32000000,
         "discount": 0, "fee": 0, "status": "paid", "paid_on": "2024-09-24", "channel": "Đóng offline"},
        {"bill_no": "4073741", "term_code": "20242", "term_name": "Academic year 2024-2025 - Semester 2",
         "description": "Thu Học Phí HK 2 (2024-2025)", "fee_type": "Thu Học Phí", "amount": 10000,
         "discount": 0, "fee": 0, "status": "paid", "paid_on": "2025-03-12", "channel": "Đóng offline"},
        {"bill_no": "4073740", "term_code": "20242", "term_name": "Academic year 2024-2025 - Semester 2",
         "description": "Thu Học Phí HK 2 (2024-2025)", "fee_type": "Thu Học Phí", "amount": 10275000,
         "discount": 0, "fee": 0, "status": "paid", "paid_on": "2025-03-04", "channel": "Liên ngân hàng"}
      ]
    }
  }
}
```

`contract/samples/finish-iupay-unpaid.json` (made up: one bill of each status still to pay):

```json
{
  "schema_version": 1,
  "iupay": {
    "status": "ok",
    "data": {
      "bills": [
        {"bill_no": "E0000020001", "term_code": "20262", "term_name": "Academic year 2026-2027 - Semester 2",
         "description": "Thu Học Phí HK 2 (2026-2027)", "fee_type": "Thu Học Phí", "amount": 40000000,
         "discount": 2000000, "fee": 0, "status": "unpaid", "due_date": "2027-02-15"},
        {"bill_no": "E0000020002", "term_code": "20262", "term_name": "Academic year 2026-2027 - Semester 2",
         "description": "Bảo hiểm y tế 2027", "fee_type": "Thu BHYT", "amount": 1105650,
         "status": "paying", "due_date": "2027-01-31"},
        {"bill_no": "E0000020003", "term_code": "20261", "term_name": "Academic year 2026-2027 - Semester 1",
         "description": "Thu Học Phí học lại\nIT093IU", "amount": 3000000,
         "status": "partly_paid", "due_date": "2026-12-31"}
      ]
    }
  }
}
```

- [ ] **Step 2: Write the failing Python tests**

Append to `contract/tests/test_contract.py`, just before the `# ---- contract/samples/` section:

```python
# ---- IUPay ---------------------------------------------------------------------


# The student's bills as the agent uploads them: every bill IUPay lists, paid or not.
def iupay_payload(name="finish-iupay.json"):
    return _sample(name)["iupay"]["data"]


def test_iupay_bills_are_accepted_next_to_the_others():
    payload = full_payload()
    payload["iupay"] = {"status": "ok", "data": iupay_payload()}

    finish = FinishRun.model_validate(payload)

    assert list(finish.sections()) == ["timetable", "exams", "tuition", "iupay"]
    first = finish.iupay.data.bills[0]
    assert (first.bill_no, first.status, first.paid_on.isoformat(), first.amount, first.discount) == (
        "E0000014104", "paid", "2026-09-30", 65_250_000, 0)
    assert (first.due_date, first.channel) == (None, "Đóng qua kênh EduBill")


def test_bills_still_to_pay_carry_their_due_date():
    finish = FinishRun.model_validate(_sample("finish-iupay-unpaid.json"))

    assert [(b.status, b.due_date.isoformat(), b.paid_on) for b in finish.iupay.data.bills] == [
        ("unpaid", "2027-02-15", None), ("paying", "2027-01-31", None), ("partly_paid", "2026-12-31", None)]
    assert (finish.iupay.data.bills[1].discount, finish.iupay.data.bills[2].fee_type) == (0, None)


def test_no_bills_is_a_valid_answer():
    finish = FinishRun.model_validate({"iupay": {"status": "ok", "data": {"bills": []}}})

    assert finish.overall_status() == "success"


@pytest.mark.parametrize(
    "change",
    [
        lambda p: p["bills"][0].update(amount=-1),
        lambda p: p["bills"][0].update(status="cancelled"),
        lambda p: p["bills"].append(dict(p["bills"][0])),
        lambda p: p["bills"][0].update(student_name="Nguyen Van An"),
        lambda p: p["bills"][0].update(description="   "),
        lambda p: p["bills"][0].update(bill_no="E" * 41),
        lambda p: p["bills"][0].update(paid_on="30/09/2026"),
        lambda p: p["bills"][0].update(amount=12.5),
        lambda p: p.update(bills=p["bills"] * 72),
    ],
    ids=["negative-amount", "unknown-status", "same-bill-twice", "extra-field", "blank-description",
         "bill-number-too-long", "bad-date", "fraction", "too-many-bills"],
)
def test_bad_iupay_data_is_rejected(change):
    data = iupay_payload()
    change(data)

    with pytest.raises(ValidationError):
        FinishRun.model_validate({"iupay": {"status": "ok", "data": data}})


@pytest.mark.parametrize("code", ["network", "extra_verification", "bad_credentials", "source_changed"])
def test_a_failed_iupay_part_says_why(code):
    finish = FinishRun.model_validate({
        "timetable": full_payload()["timetable"],
        "iupay": {"status": "failed", "error_code": code, "error_message": "IUPay problem"},
    })

    assert finish.overall_status() == "partial"
```

(`p["bills"] * 72` is 504 bills, over the 500 limit; the same-number check would also refuse it, and either reason is fine.)

- [ ] **Step 3: Run them to see them fail**

Run: `.venv/Scripts/python.exe -m pytest contract -q`
Expected: FAIL. The new tests fail with "Extra inputs are not permitted" on `iupay`, and `test_every_shared_sample_is_accepted[finish-iupay.json]` fails too.

- [ ] **Step 4: Add the part to `schema.py`**

In `contract/sla_contract/schema.py`, after the `Tuition` class, add:

```python
BillStatus = Literal["unpaid", "paid", "paying", "partly_paid"]


class TuitionBill(_Strict):
    """One IUPay bill. Amounts are VND; due_date and paid_on are Vietnam dates."""

    bill_no: Annotated[str, Field(min_length=1, max_length=40)]
    term_code: Code
    term_name: Name | None = None
    description: Annotated[str, Field(min_length=1, max_length=1000)]
    fee_type: Name | None = None
    amount: Annotated[int, Field(ge=0)]
    discount: Annotated[int, Field(ge=0)] = 0
    fee: Annotated[int, Field(ge=0)] = 0  # IUPay's transaction fee
    status: BillStatus
    due_date: date | None = None  # bills not paid yet
    paid_on: date | None = None  # paid bills
    channel: Annotated[str, Field(max_length=100)] | None = None  # paid bills: how they were paid


class Iupay(_Strict):
    """Every bill IUPay lists for the student, paid or not."""

    bills: Annotated[list[TuitionBill], Field(max_length=500)] = []

    @model_validator(mode="after")
    def _each_bill_once(self):
        numbers = [bill.bill_no for bill in self.bills]
        if len(set(numbers)) != len(numbers):
            raise ValueError("bill_no must differ")
        return self
```

Next to the other results, add:

```python
IupayResult = Annotated[SectionOk[Iupay] | SectionFailed, Field(discriminator="status")]
```

Replace

```python
EDUSOFT_SECTIONS = ("timetable", "exams", "tuition")
SECTION_NAMES = EDUSOFT_SECTIONS + ("blackboard", "outlook")
```

with

```python
EDUSOFT_SECTIONS = ("timetable", "exams", "tuition")
SECTION_NAMES = EDUSOFT_SECTIONS + ("iupay", "blackboard", "outlook")
```

and in `FinishRun`, after `tuition: TuitionResult | None = None`, add:

```python
    iupay: IupayResult | None = None
```

- [ ] **Step 5: Run the Python tests**

Run: `.venv/Scripts/python.exe -m pytest contract -q`
Expected: PASS.

- [ ] **Step 6: Write the failing Java tests**

In `web/src/test/java/vn/edu/hcmiu/sla/school/sync/Payloads.java`, after `outlookPayload()`, add:

```java
    /** A valid IUPay section's data: the student's 7 paid bills of 2026-09-30. */
    static Map<String, Object> iupayPayload() {
        return at(read("finish-iupay.json"), "iupay", "data");
    }

    /** A valid IUPay section's data with one unpaid, one paying and one partly paid bill (made up). */
    static Map<String, Object> iupayUnpaidPayload() {
        return at(read("finish-iupay-unpaid.json"), "iupay", "data");
    }
```

In `SyncContractTest.java`, add `import static vn.edu.hcmiu.sla.school.sync.Payloads.iupayPayload;` and `import java.time.LocalDate;` to the imports, and before `// ---- contract/samples/` add:

```java
    // ---- IUPay ------------------------------------------------------------------

    @Test
    void iupayBillsAreAcceptedNextToTheOthers() {
        Map<String, Object> payload = fullPayload();
        payload.put("iupay", ok(iupayPayload()));

        FinishRun finish = read(payload);

        assertThat(finish.sections().keySet()).containsExactly("timetable", "exams", "tuition", "iupay");
        var first = finish.iupay().data().bills().get(0);
        assertThat(List.of(first.billNo(), first.status(), first.paidOn(), first.amount(), first.discount()))
                .containsExactly("E0000014104", "paid", LocalDate.of(2026, 9, 30), 65_250_000L, 0L);
        assertThat(first.dueDate()).isNull();
    }

    @Test
    void missingDiscountAndFeeMeanZero() {
        var bills = read(new HashMap<>(Map.of("iupay", ok(Payloads.iupayUnpaidPayload())))).iupay().data().bills();

        assertThat(bills).extracting(b -> b.status() + " " + b.discount() + " " + b.fee() + " " + b.dueDate())
                .containsExactly("unpaid 2000000 0 2027-02-15", "paying 0 0 2027-01-31", "partly_paid 0 0 2026-12-31");
    }

    @Test
    void noBillsIsAValidAnswer() {
        FinishRun finish = read(new HashMap<>(Map.of("iupay", ok(Map.of("bills", List.of())))));

        assertThat(finish.overallStatus()).isEqualTo("success");
    }

    static Stream<Arguments> badIupayData() {
        return Stream.<Arguments>of(
                Arguments.of("negative-amount", (Consumer<Map<String, Object>>) p -> at(p, "bills", 0).put("amount", -1)),
                Arguments.of("unknown-status", (Consumer<Map<String, Object>>) p -> at(p, "bills", 0)
                        .put("status", "cancelled")),
                Arguments.of("same-bill-twice", (Consumer<Map<String, Object>>) p -> list(p, "bills")
                        .add(new HashMap<>(at(p, "bills", 0)))),
                Arguments.of("extra-field", (Consumer<Map<String, Object>>) p -> at(p, "bills", 0)
                        .put("student_name", "Nguyen Van An")),
                Arguments.of("blank-description", (Consumer<Map<String, Object>>) p -> at(p, "bills", 0)
                        .put("description", "   ")),
                Arguments.of("bill-number-too-long", (Consumer<Map<String, Object>>) p -> at(p, "bills", 0)
                        .put("bill_no", "E".repeat(41))),
                Arguments.of("bad-date", (Consumer<Map<String, Object>>) p -> at(p, "bills", 0)
                        .put("paid_on", "30/09/2026")),
                Arguments.of("fraction", (Consumer<Map<String, Object>>) p -> at(p, "bills", 0).put("amount", 12.5)),
                Arguments.of("too-many-bills", (Consumer<Map<String, Object>>) p -> {
                    List<Object> bills = new ArrayList<>();
                    for (int i = 0; i < 72; i++) {
                        bills.addAll(list(p, "bills"));
                    }
                    p.put("bills", bills);
                }));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badIupayData")
    void badIupayDataIsRejected(String name, Consumer<Map<String, Object>> change) {
        Map<String, Object> data = iupayPayload();
        change.accept(data);

        assertRefused(new HashMap<>(Map.of("iupay", ok(data))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"network", "extra_verification", "bad_credentials", "source_changed"})
    void aFailedIupayPartSaysWhy(String code) {
        FinishRun finish = read(Map.of(
                "timetable", fullPayload().get("timetable"),
                "iupay", failed(code, "IUPay problem")));

        assertThat(finish.overallStatus()).isEqualTo("partial");
    }
```

- [ ] **Step 7: Run them to see them fail**

Run: `(cd web && ./mvnw -B -q test -Dtest=SyncContractTest)`
Expected: compilation FAILS (`iupay()` is not defined).

- [ ] **Step 8: Add the part to `SyncContract.java`**

After the `Tuition` record (before `// ---- Blackboard`), add:

```java
    // ---- IUPay ------------------------------------------------------------------

    static final String BILL_STATUSES = "unpaid|paid|paying|partly_paid";

    /** One IUPay bill. Amounts are VND; dueDate and paidOn are Vietnam dates. */
    public record TuitionBill(
            @NotNull @Chars(min = 1, max = 40) String billNo,
            @NotNull @Chars(min = 1, max = 20) String termCode,
            @Chars(min = 1, max = 255) String termName,
            @NotNull @Chars(min = 1, max = 1000) String description,
            @Chars(min = 1, max = 255) String feeType,
            @NotNull @PositiveOrZero Long amount,
            @PositiveOrZero Long discount,
            @PositiveOrZero Long fee,
            @NotNull @Pattern(regexp = BILL_STATUSES) String status,
            LocalDate dueDate,
            LocalDate paidOn,
            @Chars(max = 100) String channel) {

        public TuitionBill {
            discount = discount == null ? 0L : discount;
            fee = fee == null ? 0L : fee;
        }
    }

    /** Every bill IUPay lists for the student, paid or not. */
    public record Iupay(@Size(max = 500) List<@Valid TuitionBill> bills) {

        public Iupay {
            bills = bills == null ? List.of() : bills;
        }

        @AssertTrue(message = "bill_no must differ")
        boolean isEachBillOnce() {
            return bills.stream().map(TuitionBill::billNo).distinct().count() == bills.size();
        }
    }
```

In `FinishRun`, add the component `@Valid Section<Iupay> iupay,` right after `@Valid Section<Tuition> tuition,`. In `sections()`, after the `tuition` block, add:

```java
            if (iupay != null) {
                sent.put("iupay", iupay);
            }
```

and change its comment to `/** The parts that were sent, by name, in the order timetable, exams, tuition, iupay, blackboard, outlook. */`.

- [ ] **Step 9: Run the Java tests**

Run: `(cd web && ./mvnw -B -q test -Dtest='SyncContractTest,SyncApiTest,IngestTest')`
Expected: PASS (the two new samples are also checked by `everySharedSampleIsAccepted`).

- [ ] **Step 10: Commit**

```bash
git add contract web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncContract.java web/src/test/java/vn/edu/hcmiu/sla/school/sync/Payloads.java web/src/test/java/vn/edu/hcmiu/sla/school/sync/SyncContractTest.java
git commit -m "feat(contract): an iupay part with every tuition bill, paid or not

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Reading IUPay's bill list, on the laptop

A pure reader from IUPay's JSON to the `Iupay` part, tested against an anonymised copy of the student's real reply.

**Files:**
- Create: `agent/sla_agent/parsers/iupay.py`
- Create: `agent/tests/fixtures/iupay-bills.json`
- Create: `agent/tests/test_iupay.py`
- Modify: `agent/sla_agent/errors.py` (docstring only)

**Interfaces:**
- Consumes: `sla_contract.schema.Iupay`, `TuitionBill` (Task 1); `sla_agent.errors.SourceChanged` (code `source_changed`).
- Produces: `sla_agent.parsers.iupay.parse_iupay(reply: dict) -> Iupay`; raises `SourceChanged` for anything unexpected. `STATUSES = {0: "unpaid", 1: "paid", 2: "paying", 3: "partly_paid"}`.

- [ ] **Step 1: Save the anonymised real reply**

Create `agent/tests/fixtures/iupay-bills.json` with exactly this content. It is the student's reply of 2026-09-30 with the student ID replaced by `ITITIU00000`, the `student` block removed, internal IDs replaced, and most unused fields left out:

```json
{"code": 200, "success": true, "timestamp": "2026-09-30T04:14:50.495Z",
 "data": {"data": {"pagination": {"totalRows": 7, "totalPages": 1}, "records": [
   {"_id": "000000000000000000000001", "hoc_ky_chu": "Academic year 2026-2027 - Semester 1", "hoc_ky": "20261", "ten_truong": "Trường Đại học Quốc tế - Hệ Đào tạo Đại học ", "so_phieu_bao": "E0000014104", "ma_sv": "ITITIU00000", "noi_dung": "Thu Học Phí HK 1 (2026-2027)", "kenh_thu": "Đóng qua kênh EduBill", "ngay_thu": 1790739949000, "trang_thai": 1, "ma_loai_thu": {"typeFeeCode": "1", "typeFeeName": "Thu Học Phí"}, "phai_thu": 65250000, "tong_thu": 65250000, "so_tien_phu_thu_tien_ich": 0, "mien_giam": 0, "ngay_tao": 1790739949000, "date_line": 1790739949000},
   {"_id": "000000000000000000000002", "hoc_ky_chu": "Academic year 2025-2026 - Semester 1", "hoc_ky": "20251", "ten_truong": "Trường Đại học Quốc tế - Hệ Đào tạo Đại học ", "so_phieu_bao": "4073743", "ma_sv": "ITITIU00000", "noi_dung": "Thu học phí học kỳ 1/2025-2026", "kenh_thu": "Đóng offline", "ngay_thu": 1759251600000, "trang_thai": 1, "ma_loai_thu": {"typeFeeCode": "1", "typeFeeName": "Thu Học Phí"}, "phai_thu": 36900000, "tong_thu": 36900000, "so_tien_phu_thu_tien_ich": 0, "mien_giam": 0, "ngay_tao": 1759251600000, "date_line": 1759251600000},
   {"_id": "000000000000000000000003", "hoc_ky_chu": "Academic year 2025-2026 - Semester 2", "hoc_ky": "20252", "ten_truong": "Trường Đại học Quốc tế - Hệ Đào tạo Đại học ", "so_phieu_bao": "E0000000208", "ma_sv": "ITITIU00000", "noi_dung": "Thu Học Phí HK 2 (2025-2026)", "kenh_thu": "Đóng qua kênh EduBill", "ngay_thu": 1768535484000, "trang_thai": 1, "ma_loai_thu": {"typeFeeCode": "1", "typeFeeName": "Thu Học Phí"}, "phai_thu": 40273756, "tong_thu": 40273756, "so_tien_phu_thu_tien_ich": 0, "mien_giam": 0, "ngay_tao": 1768535484000, "date_line": 1768535484000},
   {"_id": "000000000000000000000004", "hoc_ky_chu": "Academic year 2024-2025 - Semester 2", "hoc_ky": "20242", "ten_truong": "Trường Đại học Quốc tế - Hệ Đào tạo Đại học ", "so_phieu_bao": "4073742", "ma_sv": "ITITIU00000", "noi_dung": "Chuyen tien du tu LPTTDN qua HP", "kenh_thu": "Đóng offline", "ngay_thu": 1746810000000, "trang_thai": 1, "ma_loai_thu": {"typeFeeCode": "1", "typeFeeName": "Thu Học Phí"}, "phai_thu": 216350, "tong_thu": 216350, "so_tien_phu_thu_tien_ich": 0, "mien_giam": 0, "ngay_tao": 1746810000000, "date_line": 1746810000000},
   {"_id": "000000000000000000000005", "hoc_ky_chu": "Academic year 2024-2025 - Semester 1", "hoc_ky": "20241", "ten_truong": "Trường Đại học Quốc tế - Hệ Đào tạo Đại học ", "so_phieu_bao": "4073739", "ma_sv": "ITITIU00000", "noi_dung": "Tạm thu học phi dau nam Khoa 2024", "kenh_thu": "Đóng offline", "ngay_thu": 1727110800000, "trang_thai": 1, "ma_loai_thu": {"typeFeeCode": "1", "typeFeeName": "Thu Học Phí"}, "phai_thu": 32000000, "tong_thu": 32000000, "so_tien_phu_thu_tien_ich": 0, "mien_giam": 0, "ngay_tao": 1727110800000, "date_line": 1727110800000},
   {"_id": "000000000000000000000006", "hoc_ky_chu": "Academic year 2024-2025 - Semester 2", "hoc_ky": "20242", "ten_truong": "Trường Đại học Quốc tế - Hệ Đào tạo Đại học ", "so_phieu_bao": "4073741", "ma_sv": "ITITIU00000", "noi_dung": "Thu Học Phí HK 2 (2024-2025)", "kenh_thu": "Đóng offline", "ngay_thu": 1741770482000, "trang_thai": 1, "ma_loai_thu": {"typeFeeCode": "1", "typeFeeName": "Thu Học Phí"}, "phai_thu": 10000, "tong_thu": 10000, "so_tien_phu_thu_tien_ich": 0, "mien_giam": 0, "ngay_tao": 1741770482000, "date_line": 1741770482000},
   {"_id": "000000000000000000000007", "hoc_ky_chu": "Academic year 2024-2025 - Semester 2", "hoc_ky": "20242", "ten_truong": "Trường Đại học Quốc tế - Hệ Đào tạo Đại học ", "so_phieu_bao": "4073740", "ma_sv": "ITITIU00000", "noi_dung": "Thu Học Phí HK 2 (2024-2025)", "kenh_thu": "Liên ngân hàng", "ngay_thu": 1741057701000, "trang_thai": 1, "ma_loai_thu": {"typeFeeCode": "1", "typeFeeName": "Thu Học Phí"}, "phai_thu": 10275000, "tong_thu": 10275000, "so_tien_phu_thu_tien_ich": 0, "mien_giam": 0, "ngay_tao": 1741057701000, "date_line": 1741057701000}
 ]}}}
```

- [ ] **Step 2: Write the failing tests**

Create `agent/tests/test_iupay.py`:

```python
"""IUPay: the bill-list reader (and, from Task 3, the client), against an anonymised copy of the student's
real reply of 2026-09-30 (agent/tests/fixtures/iupay-bills.json) and made-up bills."""

import json
from datetime import date
from pathlib import Path

import pytest

from sla_agent.errors import SourceChanged
from sla_agent.parsers.iupay import parse_iupay

FIXTURES = Path(__file__).parent / "fixtures"


def real_reply():
    return json.loads((FIXTURES / "iupay-bills.json").read_text(encoding="utf-8"))


def record(**changes):
    """One made-up bill as IUPay sends it: not paid yet, due Mon 15/02/2027 00:00 in Vietnam."""
    bill = {
        "_id": "000000000000000000000099", "hoc_ky_chu": "Academic year 2026-2027 - Semester 2", "hoc_ky": "20262",
        "so_phieu_bao": "E0000020001", "ma_sv": "ITITIU00000", "noi_dung": "Thu Học Phí HK 2 (2026-2027)",
        "kenh_thu": "", "ngay_thu": 0, "trang_thai": 0,
        "ma_loai_thu": {"typeFeeCode": "1", "typeFeeName": "Thu Học Phí"},
        "phai_thu": 40_000_000, "tong_thu": 40_000_000, "so_tien_phu_thu_tien_ich": 0, "mien_giam": 2_000_000,
        "ngay_tao": 1790739949000, "date_line": 1802624400000,
    }
    bill.update(changes)
    return bill


def reply(*records):
    """IUPay's answer, with the student block it always sends (made up here)."""
    return {"code": 200, "success": True, "data": {
        "data": {"records": list(records), "pagination": {"totalRows": len(records), "totalPages": 1}},
        "student": {"MaSV": "ITITIU00000", "HoTen": "Nguyễn Văn An", "Email": "ITITIU00000@student.hcmiu.edu.vn",
                    "MaLop": "ITIT24IU01"}}}


# ---- the reader ------------------------------------------------------------------


def test_the_real_reply_gives_seven_paid_bills_with_vietnam_dates():
    result = parse_iupay(real_reply())

    # 4073743 was paid at 00:00 on 01/10/2025 in Vietnam, still 30/09 in UTC.
    assert [(b.bill_no, b.term_code, b.amount, b.paid_on, b.channel) for b in result.bills] == [
        ("E0000014104", "20261", 65_250_000, date(2026, 9, 30), "Đóng qua kênh EduBill"),
        ("4073743", "20251", 36_900_000, date(2025, 10, 1), "Đóng offline"),
        ("E0000000208", "20252", 40_273_756, date(2026, 1, 16), "Đóng qua kênh EduBill"),
        ("4073742", "20242", 216_350, date(2025, 5, 10), "Đóng offline"),
        ("4073739", "20241", 32_000_000, date(2024, 9, 24), "Đóng offline"),
        ("4073741", "20242", 10_000, date(2025, 3, 12), "Đóng offline"),
        ("4073740", "20242", 10_275_000, date(2025, 3, 4), "Liên ngân hàng"),
    ]
    assert {(b.status, b.due_date, b.discount, b.fee) for b in result.bills} == {("paid", None, 0, 0)}
    first = result.bills[0]
    assert (first.term_name, first.description, first.fee_type) == (
        "Academic year 2026-2027 - Semester 1", "Thu Học Phí HK 1 (2026-2027)", "Thu Học Phí")


def test_a_bill_not_paid_yet_has_its_vietnam_due_date_and_no_payment():
    [bill] = parse_iupay(reply(record())).bills

    # date_line is 15/02/2027 00:00 in Vietnam, 14/02 in UTC.
    assert (bill.status, bill.due_date, bill.paid_on, bill.channel) == ("unpaid", date(2027, 2, 15), None, None)
    assert (bill.amount, bill.discount, bill.fee) == (40_000_000, 2_000_000, 0)


@pytest.mark.parametrize("number, status", [(0, "unpaid"), (1, "paid"), (2, "paying"), (3, "partly_paid")])
def test_each_iupay_status_number_has_its_name(number, status):
    [bill] = parse_iupay(reply(record(trang_thai=number, ngay_thu=1796092200000, kenh_thu="Liên ngân hàng"))).bills

    assert bill.status == status
    if status == "paid":
        assert (bill.paid_on, bill.channel, bill.due_date) == (date(2026, 12, 1), "Liên ngân hàng", None)
    else:
        assert (bill.paid_on, bill.channel, bill.due_date) == (None, None, date(2027, 2, 15))


def test_tags_in_a_description_become_plain_lines():
    [bill] = parse_iupay(reply(record(noi_dung="Học phí HK2<br>Bảo hiểm y tế<BR/> <b>2027</b> "))).bills

    assert bill.description == "Học phí HK2\nBảo hiểm y tế\n2027"


def test_a_bill_without_a_description_is_named_by_its_fee_type():
    [bill] = parse_iupay(reply(record(noi_dung=""))).bills

    assert bill.description == "Thu Học Phí"


def test_nothing_about_the_student_leaves_the_reader():
    uploaded = parse_iupay(reply(record())).model_dump_json()

    for personal in ("ITITIU00000", "Nguyễn Văn An", "student.hcmiu.edu.vn", "ITIT24IU01"):
        assert personal not in uploaded


def test_no_bills_is_an_empty_list():
    assert parse_iupay(reply()).bills == []


@pytest.mark.parametrize(
    "answer",
    [
        reply(record(trang_thai=5)),
        reply(record(phai_thu=-1)),
        reply({k: v for k, v in record().items() if k != "so_phieu_bao"}),
        reply(record(), record()),
        reply(record(date_line="15/02/2027")),
        reply("not a bill"),
        {"code": 200, "data": {"student": {}}},
        {"code": 200, "data": {"data": {"records": "none"}}},
        [],
    ],
    ids=["unknown-status", "negative-amount", "no-bill-number", "same-bill-twice", "date-as-text", "bill-not-an-object",
         "no-records", "records-not-a-list", "not-an-object"],
)
def test_an_answer_that_does_not_look_right_is_source_changed(answer):
    with pytest.raises(SourceChanged):
        parse_iupay(answer)
```

- [ ] **Step 3: Run them to see them fail**

Run: `.venv/Scripts/python.exe -m pytest agent/tests/test_iupay.py -q`
Expected: FAIL with `ModuleNotFoundError: No module named 'sla_agent.parsers.iupay'`.

- [ ] **Step 4: Write the reader**

Create `agent/sla_agent/parsers/iupay.py`:

```python
"""IUPay reader: the student's bills, as IUPay's search page (https://iupay.hcmiu.edu.vn/search/dhqt) lists them.

IUPay answers {"data": {"data": {"records": [...]}, "student": {...}}}. Only the bill fields of the upload format
are kept; the student block (name, email, class) and every other field are dropped here, in memory.
Times are epoch milliseconds; the dates kept are Vietnam dates (UTC+7).
"""

import re
from datetime import datetime, timedelta, timezone

from pydantic import ValidationError
from sla_contract.schema import Iupay, TuitionBill

from sla_agent.errors import SourceChanged

VN = timezone(timedelta(hours=7))
# trang_thai, as IUPay's own page labels it: Chưa đóng, Đã đóng, Đang thanh toán, Đóng một phần.
STATUSES = {0: "unpaid", 1: "paid", 2: "paying", 3: "partly_paid"}


def _records(reply):
    records = None
    if isinstance(reply, dict) and isinstance(reply.get("data"), dict) and isinstance(reply["data"].get("data"), dict):
        records = reply["data"]["data"].get("records")
    if not isinstance(records, list):
        raise SourceChanged("IUPay's bill list isn't in the expected format.")
    return records


def _day(millis):
    """The Vietnam date of an IUPay time; None for 0 or a missing time."""
    if millis in (None, 0):
        return None
    if isinstance(millis, bool) or not isinstance(millis, int):
        raise SourceChanged("An IUPay date isn't in the expected format.")
    return datetime.fromtimestamp(millis / 1000, VN).date()


def _text(html):
    """IUPay's descriptions are HTML: <br> becomes a line break, other tags are removed, blank lines dropped."""
    text = re.sub(r"<br\s*/?>", "\n", html or "", flags=re.IGNORECASE)
    text = re.sub(r"<[^>]+>", "", text)
    return "\n".join(line.strip() for line in text.splitlines() if line.strip())


def _bill(record):
    number = record["trang_thai"]
    status = STATUSES.get(number) if isinstance(number, int) and not isinstance(number, bool) else None
    if status is None:
        raise SourceChanged(f"IUPay shows a bill status it didn't use before ({number!r}).")
    paid = status == "paid"
    fee_type = _text((record.get("ma_loai_thu") or {}).get("typeFeeName")) or None
    bill_no = str(record["so_phieu_bao"])
    return TuitionBill(
        bill_no=bill_no,
        term_code=str(record["hoc_ky"]),
        term_name=_text(record.get("hoc_ky_chu")) or None,
        description=_text(record.get("noi_dung")) or _text(record.get("chi_tiet")) or fee_type or bill_no,
        fee_type=fee_type,
        amount=record["phai_thu"],
        discount=record.get("mien_giam") or 0,
        fee=record.get("so_tien_phu_thu_tien_ich") or 0,
        status=status,
        due_date=None if paid else _day(record.get("date_line")),
        paid_on=_day(record.get("ngay_thu")) if paid else None,
        channel=(_text(record.get("kenh_thu")) or None) if paid else None,
    )


def parse_iupay(reply):
    """reply: the decoded JSON of IUPay's .../secretCode/{code}/bill request."""
    bills = []
    for record in _records(reply):
        try:
            bills.append(_bill(record))
        except (KeyError, TypeError, AttributeError):
            raise SourceChanged("An IUPay bill is missing a field it used to have.") from None
        except ValidationError as error:
            raise SourceChanged(f"Unexpected IUPay bill: {error.errors()[0]['msg']}") from None
    try:
        return Iupay(bills=bills)
    except ValidationError as error:
        raise SourceChanged(f"Unexpected IUPay bill list: {error.errors()[0]['msg']}") from None
```

(`error.errors()[0]['msg']` is pydantic's message, e.g. "Input should be greater than or equal to 0"; it never contains the value.)

In `agent/sla_agent/errors.py`, change the `SourceChanged` docstring to:

```python
    """Blackboard's or IUPay's answers don't look the way the reader expects: their format probably changed."""
```

- [ ] **Step 5: Run the tests**

Run: `.venv/Scripts/python.exe -m pytest agent/tests/test_iupay.py -q`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add agent/sla_agent/parsers/iupay.py agent/sla_agent/errors.py agent/tests/test_iupay.py agent/tests/fixtures/iupay-bills.json
git commit -m "feat(agent): read IUPay's bill list, keeping only the bill fields, with Vietnam dates

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: The agent reads IUPay in every sync, and no longer EduSoft's tuition report

**Files:**
- Create: `agent/sla_agent/iupay_client.py`
- Modify: `agent/sla_agent/sync.py`, `agent/sla_agent/cli.py`, `agent/sla_agent/scheduler.py`, `agent/sla_agent/edusoft_client.py`, `agent/sla_agent/parsers/__init__.py`, `contract/sla_contract/schema.py`
- Delete: `agent/sla_agent/parsers/tuition.py`, `agent/tests/fixtures/tuition-report.json`
- Modify tests: `agent/tests/test_iupay.py`, `fakes.py`, `test_sync.py`, `test_cli.py`, `test_parsers.py`, `test_edusoft_client.py`

**Interfaces:**
- Consumes: `parse_iupay` (Task 2); `guarded_request(session, method, url, host, data=None, site=...)` and `protect(secret)` (existing).
- Produces: `IupayClient(session=None).read_bills(student_id) -> dict` (IUPay's decoded reply; raises `ExtraVerification`, `BadCredentials`, `NetworkError`, `SourceChanged`); `iupay_client.SCHOOL_URL`; `sync.collect_iupay(student_id, iupay, parse=parse_iupay) -> dict` (a section result); `run_sync(..., iupay=None)`; `cli.make_iupay()`; `fakes.FakeIupay(reply=None)` with `.calls`. `EDUSOFT_SECTIONS == ("timetable", "exams")`, `SECTION_NAMES == ("timetable", "exams", "tuition", "iupay", "blackboard", "outlook")`.

- [ ] **Step 1: Write the failing client tests**

Append to `agent/tests/test_iupay.py`:

```python
# ---- the client --------------------------------------------------------------------

import requests  # noqa: E402
import responses  # noqa: E402

from sla_agent.edusoft_client import USER_AGENT  # noqa: E402
from sla_agent.errors import BadCredentials, ExtraVerification, NetworkError  # noqa: E402
from sla_agent.iupay_client import SCHOOL_URL, IupayClient  # noqa: E402
from sla_agent.log import redact  # noqa: E402

CAPTCHA = f"{SCHOOL_URL}/captcha-public"
SEARCH = f"{SCHOOL_URL}/secret/ITITIU00000/0"
CODE = "$2b$05$Made.Up.Lookup.Code.For.Tests.Only@k218l5eyWabcdefgh"  # made up, shaped like IUPay's
BILLS = f"{SCHOOL_URL}/secretCode/{CODE}/bill?limit=99999&offset=0"


def answer_search(captcha=False, found=None):
    responses.get(CAPTCHA, json={"code": 200, "data": {"enabled": captcha}, "success": True})
    responses.get(SEARCH, json=found or {"code": 200, "data": {"secretCode": CODE}, "success": True})


@responses.activate
def test_the_client_makes_the_three_requests_the_iupay_page_makes():
    answer_search()
    responses.get(BILLS, json=real_reply())

    answer = IupayClient().read_bills("ITITIU00000")

    assert answer == real_reply()
    assert [c.request.method for c in responses.calls] == ["GET", "GET", "GET"]
    assert all(c.request.headers["User-Agent"] == USER_AGENT for c in responses.calls)


@responses.activate
def test_the_lookup_code_is_hidden_in_the_log():
    answer_search()
    responses.get(BILLS, json=real_reply())

    IupayClient().read_bills("ITITIU00000")

    assert redact(f"GET {BILLS}") == f"GET {SCHOOL_URL}/secretCode/***/bill?limit=99999&offset=0"


@responses.activate
def test_a_captcha_stops_before_the_student_id_is_sent():
    answer_search(captcha=True)

    with pytest.raises(ExtraVerification):
        IupayClient().read_bills("ITITIU00000")

    assert len(responses.calls) == 1


@responses.activate
def test_an_unknown_student_id_says_what_iupay_said():
    answer_search(found={"code": 200, "success": True, "data": {
        "success": False, "statusCode": 400, "message": "Thông tin sinh viên không chính xác"}})

    with pytest.raises(BadCredentials, match="Thông tin sinh viên không chính xác"):
        IupayClient().read_bills("ITITIU00000")


@responses.activate
def test_iupay_not_answering_is_a_network_problem():
    responses.get(CAPTCHA, body=requests.ConnectTimeout())

    with pytest.raises(NetworkError):
        IupayClient().read_bills("ITITIU00000")


@pytest.mark.parametrize(
    "search",
    [{"code": 200, "data": {}}, {"code": 200}, "<html>Bảo trì hệ thống</html>"],
    ids=["no-lookup-code", "no-data", "not-json"],
)
@responses.activate
def test_a_search_answer_that_does_not_look_right_is_source_changed(search):
    responses.get(CAPTCHA, json={"code": 200, "data": {"enabled": False}})
    if isinstance(search, str):
        responses.get(SEARCH, body=search)
    else:
        responses.get(SEARCH, json=search)

    with pytest.raises(SourceChanged):
        IupayClient().read_bills("ITITIU00000")
```

- [ ] **Step 2: Run them to see them fail**

Run: `.venv/Scripts/python.exe -m pytest agent/tests/test_iupay.py -q`
Expected: FAIL with `ModuleNotFoundError: No module named 'sla_agent.iupay_client'`.

- [ ] **Step 3: Write the client**

Create `agent/sla_agent/iupay_client.py`:

```python
"""Reads the student's bills from IUPay (https://iupay.hcmiu.edu.vn/search/dhqt). Reads only; never pays.

IUPay's page gets its data from AQTech's EduBill API at api.mybill.aqtech.vn; its search needs only the
student ID. The agent makes the same three requests the page makes (checked 2026-09-30):
1. .../captcha-public: whether IUPay asks for a captcha. It is never solved: the IUPay part fails instead.
2. .../secret/{student ID}/0: a lookup code for this student (the 0 is an ID-card number IU doesn't use).
3. .../secretCode/{code}/bill: every bill, paid or not.
The lookup code is treated like a password: hidden in the log, never saved.
"""

import logging
from urllib.parse import quote

import requests

from sla_agent.edusoft_client import USER_AGENT
from sla_agent.errors import BadCredentials, ExtraVerification, SourceChanged
from sla_agent.guarded_http import guarded_request
from sla_agent.log import protect

log = logging.getLogger(__name__)

HOST = "api.mybill.aqtech.vn"
SCHOOL_URL = f"https://{HOST}/api/organization/dhqt/school/dh"  # IU, undergraduate
SITE = "IUPay"


def _data(answer):
    return answer.get("data") if isinstance(answer, dict) else None


class IupayClient:
    def __init__(self, session=None):
        self.session = session or requests.Session()
        self.session.headers["User-Agent"] = USER_AGENT
        self.session.headers["Accept"] = "application/json"

    def _json(self, url):
        response = guarded_request(self.session, "GET", url, HOST, site=SITE)
        try:
            return response.json()
        except ValueError:
            raise SourceChanged("IUPay's answer isn't JSON.") from None

    def read_bills(self, student_id):
        """IUPay's decoded answer to the bill request for this student."""
        captcha = _data(self._json(f"{SCHOOL_URL}/captcha-public"))
        if isinstance(captcha, dict) and captcha.get("enabled"):
            raise ExtraVerification("IUPay now asks for a captcha; tuition can't sync.")

        found = _data(self._json(f"{SCHOOL_URL}/secret/{quote(student_id, safe='')}/0"))
        if not isinstance(found, dict):
            raise SourceChanged("IUPay's answer to the student search isn't in the expected format.")
        if found.get("success") is False:
            reason = str(found.get("message") or "no reason given")[:200]
            raise BadCredentials(f"IUPay didn't recognise the student ID: {reason}")
        code = found.get("secretCode")
        if not isinstance(code, str) or not code:
            raise SourceChanged("IUPay's answer to the student search has no lookup code.")
        protect(code)

        answer = self._json(f"{SCHOOL_URL}/secretCode/{code}/bill?limit=99999&offset=0")
        log.info("IUPay answered the bill request")
        return answer
```

- [ ] **Step 4: Run the client tests**

Run: `.venv/Scripts/python.exe -m pytest agent/tests/test_iupay.py -q`
Expected: PASS.

- [ ] **Step 5: Write the failing sync tests**

In `agent/tests/fakes.py`, after `FakeServer`, add:

```python
class FakeIupay:
    """Stands in for IupayClient. `reply` is what read_bills returns (an exception instance is raised instead);
    the default is IUPay's answer for a student with no bills."""

    def __init__(self, reply=None):
        self.reply = reply if reply is not None else {"data": {"data": {"records": []}}}
        self.calls = []

    def read_bills(self, student_id):
        self.calls.append(student_id)
        if isinstance(self.reply, Exception):
            raise self.reply
        return self.reply
```

In `agent/tests/test_sync.py`:
- change the fakes import to `from agent.tests.fakes import FakeBlackboard, FakeEduSoft, FakeIupay, FakeServer`;
- add `SourceChanged` to the `sla_agent.errors` import;
- give `sync(...)` an `iupay=None` parameter and pass `iupay=iupay` to `run_sync`:

```python
def sync(state, edusoft, server=None, parsers=PARSERS, trigger="scheduled", blackboard=None, read=None, iupay=None):
    server = server or FakeServer()
    outcome = run_sync(trigger, state=state, edusoft=edusoft, server=server, parsers=parsers,
                       password=PASSWORD, now=NOW, blackboard=blackboard,
                       blackboard_password=BB_PASSWORD if blackboard else None,
                       read_blackboard=read or empty_blackboard, iupay=iupay)
    return outcome, server
```

Then append:

```python
# ---- IUPay ---------------------------------------------------------------------


def test_iupay_is_read_with_the_student_id_after_edusoft(state):
    iupay = FakeIupay()

    outcome, server = sync(state, FakeEduSoft(), iupay=iupay)

    assert iupay.calls == ["ITITIU20001"]
    result = only_finish(server)
    assert list(result.sections()) == ["timetable", "exams", "iupay"]
    assert (result.iupay.status, result.iupay.data.bills) == ("ok", [])
    assert outcome.status == "success"


def test_iupay_still_syncs_while_edusoft_is_paused(state):
    state.paused = "bad_credentials"
    edusoft, iupay = FakeEduSoft(), FakeIupay()

    outcome, server = sync(state, edusoft, iupay=iupay)

    assert edusoft.logins == []
    assert iupay.calls == ["ITITIU20001"]
    result = only_finish(server)
    assert (result.iupay.status, result.timetable.error_code) == ("ok", "bad_credentials")
    assert "sla-agent setup" in outcome.message


def test_iupay_still_syncs_when_edusoft_cannot_be_reached(state):
    outcome, server = sync(state, FakeEduSoft(login_error=NetworkError("timed out")), iupay=FakeIupay())

    result = only_finish(server)
    assert (result.timetable.error_code, result.iupay.status) == ("network", "ok")


@pytest.mark.parametrize(
    "error, code",
    [(NetworkError("IUPay couldn't be reached."), "network"),
     (ExtraVerification("IUPay now asks for a captcha."), "extra_verification"),
     (BadCredentials("IUPay didn't recognise the student ID."), "bad_credentials"),
     (SourceChanged("IUPay's format changed."), "source_changed")],
    ids=["network", "captcha", "unknown-id", "format"],
)
def test_an_iupay_problem_fails_only_iupay_and_pauses_nothing(state, error, code):
    outcome, server = sync(state, FakeEduSoft(), iupay=FakeIupay(error))

    result = only_finish(server)
    assert (result.iupay.status, result.iupay.error_code) == ("failed", code)
    assert result.timetable.status == "ok"
    assert (state.paused, state.blackboard_paused) == (None, None)
    assert outcome.status == "partial"


def test_an_unexpected_iupay_crash_still_finishes_the_run(state):
    outcome, server = sync(state, FakeEduSoft(), iupay=FakeIupay(RuntimeError("boom")))

    assert only_finish(server).iupay.error_code == "unknown"


def test_bills_from_iupay_reach_the_upload(state):
    record = {"so_phieu_bao": "E0000020001", "hoc_ky": "20262", "noi_dung": "Thu Học Phí HK 2 (2026-2027)",
              "trang_thai": 0, "phai_thu": 40_000_000, "date_line": 1802624400000}
    outcome, server = sync(state, FakeEduSoft(), iupay=FakeIupay({"data": {"data": {"records": [record]}}}))

    [bill] = only_finish(server).iupay.data.bills
    assert (bill.bill_no, bill.status, bill.due_date) == ("E0000020001", "unpaid", date(2027, 2, 15))


def test_nothing_is_paused_for_good_while_iupay_can_run(state):
    from sla_agent.sync import everything_paused

    state.paused = "bad_credentials"

    assert not everything_paused(state)
    assert everything_paused(State(paused="bad_credentials"))
```

- [ ] **Step 6: Run them to see them fail**

Run: `.venv/Scripts/python.exe -m pytest agent/tests/test_sync.py -q`
Expected: FAIL: `run_sync() got an unexpected keyword argument 'iupay'`.

- [ ] **Step 7: Read IUPay in `sync.py`**

In `agent/sla_agent/sync.py`:

Change the module docstring's first paragraph to:

```python
"""One sync: EduSoft (timetable, exams), then IUPay (tuition bills), then Blackboard, then Outlook, each on its own.

Stop-don't-retry rules: a rejected password or an extra-verification request pauses
that system only (no second login attempt). An expired session gets one re-login and
one retry. One system failing never stops the others from uploading. Outlook and IUPay
have no password to lock out, so their problems never pause anything: every sync tries again.
"""
```

Add `from sla_agent.parsers.iupay import parse_iupay` to the imports.

Replace `everything_paused` with:

```python
def everything_paused(state):
    """True only when nothing at all can sync. IUPay needs only the student ID, so it can always run."""
    return (bool(state.paused) and not blackboard_ready(state) and not state.outlook_account
            and not state.student_id)
```

After `_collect_blackboard`, add:

```python
def collect_iupay(student_id, iupay, parse=parse_iupay):
    """The IUPay part: every bill, paid or not. IUPay has no password, so a problem never pauses anything."""
    try:
        return {"status": "ok", "data": parse(iupay.read_bills(student_id))}
    except AgentError as error:
        log.warning("IUPay sync failed: %s", error)
        return _failed(error)
    except Exception as error:  # a reader bug must not leave the run unfinished
        return _unexpected("IUPay", error)
```

Replace `run_sync` with:

```python
def run_sync(trigger, *, state, edusoft, server, parsers, password, now,
             blackboard=None, blackboard_password=None, read_blackboard=default_read_blackboard, read_outlook=None,
             iupay=None):
    """Run one sync and update `state` (the caller saves it). Server errors are raised."""
    protect(password)
    protect(blackboard_password)
    use_edusoft = not state.paused
    use_iupay = iupay is not None and bool(state.student_id)
    use_blackboard = blackboard is not None and bool(blackboard_password) and blackboard_ready(state)
    use_outlook = read_outlook is not None and bool(state.outlook_account)
    if not use_edusoft and not use_iupay and not use_blackboard and not use_outlook:
        return Outcome("paused", _paused_message(state))

    run_id = server.start(trigger)
    state.last_attempt_at = now.isoformat()
    sections = _paused_sections(state)
    if use_edusoft:
        sections.update(_collect_edusoft(state, edusoft, parsers, password))
    if use_iupay:
        sections["iupay"] = collect_iupay(state.student_id, iupay)
    if use_blackboard:
        sections["blackboard"] = _collect_blackboard(state, blackboard, blackboard_password, read_blackboard)
    _remember_context(state, sections)
    if use_outlook:
        sections["outlook"] = _collect_outlook(state, read_outlook, now)
    result = FinishRun.model_validate(sections)
    status = server.finish(run_id, result)

    message = _message(state, result)
    state.last_result = {"at": now.isoformat(), "status": status, "message": message}
    log.info("Sync %s (%s): %s", status, trigger, message)
    return Outcome(status, message)
```

- [ ] **Step 8: Stop reading EduSoft's tuition report**

In `contract/sla_contract/schema.py`, replace

```python
EDUSOFT_SECTIONS = ("timetable", "exams", "tuition")
SECTION_NAMES = EDUSOFT_SECTIONS + ("iupay", "blackboard", "outlook")
```

with

```python
EDUSOFT_SECTIONS = ("timetable", "exams")
SECTION_NAMES = ("timetable", "exams", "tuition", "iupay", "blackboard", "outlook")
```

Delete `agent/sla_agent/parsers/tuition.py` and `agent/tests/fixtures/tuition-report.json`:

```bash
git rm agent/sla_agent/parsers/tuition.py agent/tests/fixtures/tuition-report.json
```

`agent/sla_agent/parsers/__init__.py` becomes:

```python
"""Page readers: EduSoft pages -> the shared data format.

Each reader takes the dict of pages that EduSoftClient.read(section) returns. IUPay's reader,
parsers/iupay.py, takes IUPay's JSON answer instead.
"""

from sla_agent.parsers.exams import parse_exams
from sla_agent.parsers.timetable import parse_timetable


PARSERS = {"timetable": parse_timetable, "exams": parse_exams}
```

In `agent/sla_agent/edusoft_client.py`:
- remove `import base64` and `import json`, and change `from urllib.parse import urlencode, urljoin` to `from urllib.parse import urljoin`;
- remove `"tuition": "xemhocphi",` from `PAGES`;
- delete the block from `# Tuition: EduSoft's report viewer, report "Tổng Hợp Học Phí Một Sinh Viên".` through the closing `}` of `REPORT_VIEWER_REQUEST`;
- in `read`, delete the two lines `if section == "tuition":` / `return {"term": self._learn_current_term(), "report": self._tuition_report()}`;
- delete the whole `_tuition_report` method.

Then check that nothing still refers to them: `grep -n "tuition\|REPORT\|base64\|json\.\|urlencode" agent/sla_agent/edusoft_client.py` prints nothing.

In `agent/sla_agent/scheduler.py`, change the description line to:

```python
    _add(info, "Description", "School-Life-Assistant: sync EduSoft timetable and exams, IUPay tuition, "
                              "Blackboard and Outlook.")
```

- [ ] **Step 9: Read IUPay from the commands**

In `agent/sla_agent/cli.py`:
- add `from sla_agent.iupay_client import IupayClient` next to the other client imports, and change `from sla_agent.sync import PAUSE_MESSAGES, everything_paused, run_sync` to `from sla_agent.sync import PAUSE_MESSAGES, collect_iupay, everything_paused, run_sync`;
- after `make_blackboard`, add:

```python
def make_iupay():
    return IupayClient()
```

- in `_sync`, pass `iupay=make_iupay()` to `run_sync`:

```python
        outcome = run_sync(trigger, state=state, edusoft=make_edusoft(), server=server, parsers=PARSERS,
                           password=password, now=_now(), blackboard=blackboard,
                           blackboard_password=blackboard_password, read_blackboard=read_blackboard,
                           read_outlook=read_outlook, iupay=make_iupay())
```

- `_file_name` becomes (the tuition report was its only JSON file):

```python
def _file_name(section, part):
    """timetable + semester -> timetable-semester.html; exams + exams -> exams.html."""
    return f"{section}.html" if part == section else f"{section}-{part}.html"
```

- in `_read_folder`, delete the line `"tuition": {"report": load("tuition", "report")},`, change `if name in ("exams", "tuition"):` to `if name == "exams":`, and change the error to `raise ValueError("exams need the semester: add --term, e.g. --term 20261")`;
- in `cmd_import`, right after the `if not results:` block (so a folder without EduSoft pages still stops there), add:

```python
    if state.student_id:
        results["iupay"] = collect_iupay(state.student_id, make_iupay())  # IUPay needs no saved pages
```

- [ ] **Step 10: Update the existing agent tests**

`agent/tests/fakes.py`, in `FakeEduSoft`:

```python
    SECTION_PARTS = {"timetable": ("weekly", "semester"), "exams": ("final", "midterm"),
                     "registration": ("registration",)}
```

and in `read`, `if name in ("exams", "tuition"):` becomes `if name == "exams":`.

`agent/tests/test_sync.py`:
- the schema import becomes `from sla_contract.schema import Blackboard, Exams, Timetable`;
- delete `parse_tuition`; `PARSERS = {"timetable": parse_timetable, "exams": parse_exams}`;
- `broken_parser` raises `ParseError("Exam table not found")`;
- `test_a_good_sync_uploads_all_three_parts` → rename to `test_a_good_sync_uploads_both_edusoft_parts`, and assert `set(result.sections()) == {"timetable", "exams"}`;
- in `test_a_wrong_password_pauses_after_exactly_one_login_attempt`, the expected dict is `{"timetable": "bad_credentials", "exams": "bad_credentials"}`;
- `test_a_page_the_parser_cannot_read_fails_only_that_part` becomes:

```python
def test_a_page_the_parser_cannot_read_fails_only_that_part(state):
    outcome, server = sync(state, FakeEduSoft(), parsers={**PARSERS, "exams": broken_parser})

    result = only_finish(server)
    assert result.overall_status() == "partial"
    assert (result.exams.status, result.exams.error_code) == ("failed", "edusoft_changed")
    assert result.timetable.status == "ok"
```

- the section lists: `["timetable", "exams", "tuition", "blackboard"]` → `["timetable", "exams", "blackboard"]` and `["timetable", "exams", "tuition", "outlook"]` → `["timetable", "exams", "outlook"]`; in `test_a_paused_edusoft_is_reported_as_paused_in_every_run` the assertion becomes:

```python
    assert [(sections[n].status, sections[n].error_code) for n in ("timetable", "exams")] == [
        ("failed", "bad_credentials")] * 2
```

`agent/tests/test_parsers.py`: delete `from sla_agent.parsers.tuition import parse_tuition` and the whole `# ---- Tuition (EduSoft's report ...` section (from its heading to the end of `test_a_tuition_report_that_does_not_look_right_raises_parse_error`).

`agent/tests/test_edusoft_client.py`: delete `REPORT_URL = ...`, `test_reading_tuition_asks_the_report_viewer_for_my_own_report` and `test_the_report_viewer_showing_the_login_form_means_the_session_expired`. If `parse_qs` or `posted_form` is then unused, `grep -n "parse_qs\|posted_form" agent/tests/test_edusoft_client.py` shows it; remove only an import that has no other use.

`agent/tests/test_cli.py`:
- `from datetime import datetime, timedelta, timezone` (no `date`); `from sla_contract.schema import Exams, Timetable`; `from agent.tests.fakes import FakeBlackboard, FakeEduSoft, FakeIupay, FakeServer`;
- `PARSERS` loses its `"tuition"` entry;
- in `World.__init__`, after the Blackboard lines, add:

```python
        self.iupay = FakeIupay()
        monkeypatch.setattr(cli, "make_iupay", lambda: self.iupay)
```

- in `test_fetch_saves_the_pages_locally_with_a_privacy_warning`, remove `"tuition-report.json"` from the expected list;
- `test_run_while_paused_still_checks_in_but_never_logs_in` becomes:

```python
def test_run_while_edusoft_is_paused_never_logs_in_but_still_reads_iupay(world):
    configure(paused="bad_credentials")

    cli.main(["run"])

    assert world.server.checks == 1
    assert (world.server.starts, world.edusoft.logins, world.iupay.calls) == (["scheduled"], [], [STUDENT])
```

- `test_sync_now_while_paused_explains_how_to_fix_it` becomes:

```python
def test_sync_now_while_paused_explains_how_to_fix_it(world, capsys):
    configure(paused="bad_credentials")

    assert cli.main(["sync-now"]) == 0

    assert world.edusoft.logins == []
    assert world.iupay.calls == [STUDENT]
    assert "sla-agent setup" in capsys.readouterr().out
```

- in `test_import_uploads_the_parts_found_in_a_saved_folder_even_while_paused`, assert `list(result.sections()) == ["exams", "iupay"]` and add `assert world.iupay.calls == [STUDENT]`;
- replace `test_import_reads_a_saved_tuition_report` with:

```python
def test_import_no_longer_reads_a_saved_tuition_report(world, tmp_path, capsys):
    configure()
    (tmp_path / "tuition-report.json").write_text('{"pagesArray": []}', encoding="utf-8")

    assert cli.main(["import", str(tmp_path), "--term", "20261"]) == 1

    assert "No saved EduSoft pages" in capsys.readouterr().out
    assert (world.server.starts, world.iupay.calls) == ([], [])
```

- [ ] **Step 11: Run every Python test**

Run: `.venv/Scripts/python.exe -m pytest -q`
Expected: PASS. Then `grep -rn "tuition-report\|parse_tuition\|TUITION_REPORT\|xemhocphi" agent contract` prints nothing.

- [ ] **Step 12: Run the website's contract tests** (the samples still carry `tuition`, which stays accepted)

Run: `(cd web && ./mvnw -B -q test -Dtest=SyncContractTest)`
Expected: PASS.

- [ ] **Step 13: Commit**

```bash
git add -A agent contract/sla_contract/schema.py
git commit -m "feat(agent): read tuition bills from IUPay in every sync, even while EduSoft is paused; drop EduSoft's tuition report

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: An old agent's `tuition` part is accepted but ignored

Updated agents no longer send `tuition`. The website must still accept it from agents that aren't updated yet, but it no longer counts toward the run's status, saves anything, or adds to "What changed".

**Files:**
- Modify: `contract/sla_contract/schema.py`, `contract/tests/test_contract.py`
- Modify samples: `contract/samples/finish-edusoft.json`, `finish-parts-failed.json`, `invalid/ok-part-without-data.json`, `invalid/unknown-field.json`, `invalid/whole-run-error-with-data.json`
- Create samples: `contract/samples/finish-old-agent-tuition.json`, `contract/samples/invalid/whole-run-error-with-old-tuition.json`
- Modify: `web/src/main/java/vn/edu/hcmiu/sla/school/sync/SyncContract.java`, `Ingest.java`, `Changes.java`
- Modify tests: `SyncContractTest.java`, `IngestTest.java`, `ChangesTest.java`, `SyncApiTest.java`

**Interfaces:**
- Produces: `SECTION_NAMES == ("timetable", "exams", "iupay", "blackboard", "outlook")` (Python); `FinishRun.sections()` without `tuition` (both). `Ingest` no longer takes `SchoolTuitionRepository`. `Changes.tuition` and `Changes.TuitionInfo` are gone.

- [ ] **Step 1: Move the samples off `tuition`**

`contract/samples/finish-edusoft.json`: delete the whole `"tuition": {...}` member (and the comma after `"exams": {...}`), leaving `schema_version`, `timetable` and `exams`.

`contract/samples/finish-parts-failed.json`: replace the `"tuition"` member with:

```json
  "iupay": {
    "status": "failed",
    "error_code": "network",
    "error_message": "IUPay couldn't be reached."
  },
```

`contract/samples/invalid/ok-part-without-data.json`:

```json
{
  "schema_version": 1,
  "iupay": {
    "status": "ok"
  }
}
```

`contract/samples/invalid/unknown-field.json`:

```json
{
  "schema_version": 1,
  "iupay": {
    "status": "ok",
    "data": {
      "bills": [
        {"bill_no": "E0000014104", "term_code": "20261", "description": "Thu Học Phí HK 1 (2026-2027)",
         "amount": 65250000, "status": "paid", "paid_on": "2026-09-30", "student_email": "an@student.hcmiu.edu.vn"}
      ]
    }
  }
}
```

`contract/samples/invalid/whole-run-error-with-data.json`:

```json
{
  "schema_version": 1,
  "error_code": "bad_credentials",
  "error_message": "EduSoft rejected the password",
  "iupay": {
    "status": "ok",
    "data": {
      "bills": []
    }
  }
}
```

Create `contract/samples/finish-old-agent-tuition.json` (what an agent from before this change sends; its failed tuition part must not make the run partial):

```json
{
  "schema_version": 1,
  "timetable": {
    "status": "ok",
    "data": {
      "term_code": "20261",
      "courses": []
    }
  },
  "tuition": {
    "status": "failed",
    "error_code": "edusoft_changed",
    "error_message": "The tuition report's column headers have changed."
  }
}
```

Create `contract/samples/invalid/whole-run-error-with-old-tuition.json`:

```json
{
  "schema_version": 1,
  "error_code": "bad_credentials",
  "error_message": "EduSoft rejected the password",
  "tuition": {
    "status": "ok",
    "data": {
      "term_code": "20261",
      "amount_due": 12500000,
      "amount_paid": 0,
      "balance": 12500000
    }
  }
}
```

- [ ] **Step 2: Update and add the Python contract tests**

In `contract/tests/test_contract.py`:
- `test_a_complete_upload_is_accepted`: delete the line `assert finish.tuition.data.balance == 12500000`;
- in the `unknown fields` parametrize list, replace `("tuition", "data", "bank_account"),` with `("exams", "data", "bank_account"),`;
- in `test_each_part_is_either_ok_with_data_or_failed_with_a_reason`, `payload["tuition"] = section` → `payload["exams"] = section`, and change the "failed-without-code" message to `"Exam table not found"`;
- `test_overall_status`'s cases become:

```python
    [
        ({}, "success"),
        ({"exams": _failed()}, "partial"),
        ({"timetable": _failed(), "exams": _failed()}, "failed"),
        ({"exams": None}, "success"),
        (
            {"timetable": None, "exams": None,
             "error_code": "bad_credentials", "error_message": "EduSoft rejected the password"},
            "failed",
        ),
    ],
```

- the three `sections()` lists: `["timetable", "exams", "tuition", "blackboard"]` → `["timetable", "exams", "blackboard"]`; `["timetable", "exams", "tuition", "outlook"]` → `["timetable", "exams", "outlook"]`; `["timetable", "exams", "tuition", "iupay"]` → `["timetable", "exams", "iupay"]`.

Append to the IUPay section:

```python
def test_an_old_agents_tuition_part_is_accepted_but_not_counted():
    finish = FinishRun.model_validate(_sample("finish-old-agent-tuition.json"))

    assert list(finish.sections()) == ["timetable"]
    assert finish.tuition.status == "failed"
    assert finish.overall_status() == "success"


def test_an_old_tuition_part_alone_is_not_an_upload():
    with pytest.raises(ValidationError):
        FinishRun.model_validate({"tuition": _sample("finish-old-agent-tuition.json")["tuition"]})


def test_a_whole_run_error_cannot_carry_an_old_tuition_part_either():
    with pytest.raises(ValidationError):
        FinishRun.model_validate(json.loads(
            (SAMPLES / "invalid" / "whole-run-error-with-old-tuition.json").read_text(encoding="utf-8")))
```

- [ ] **Step 3: Run them to see them fail**

Run: `.venv/Scripts/python.exe -m pytest contract -q`
Expected: FAIL. `sections()` still lists `tuition`, and the old-tuition sample makes the run `partial`.

- [ ] **Step 4: Ignore `tuition` in `schema.py`**

```python
EDUSOFT_SECTIONS = ("timetable", "exams")
SECTION_NAMES = ("timetable", "exams", "iupay", "blackboard", "outlook")
```

In `FinishRun`, the `tuition` line becomes:

```python
    tuition: TuitionResult | None = None  # sent by agents from before IUPay: accepted, never counted or saved
```

and in `_error_or_sections`, `if self.sections():` becomes `if self.sections() or self.tuition is not None:`.

- [ ] **Step 5: Run the Python tests**

Run: `.venv/Scripts/python.exe -m pytest -q`
Expected: PASS.

- [ ] **Step 6: Update the Java tests**

`SyncContractTest.java`:
- `aCompleteUploadIsAccepted`: delete the two `finish.tuition()` assertions;
- `personalFields`: `List.of("tuition", "data", "bank_account")` → `List.of("exams", "data", "bank_account")`;
- `badParts`: the "failed-without-code" message → `"Exam table not found"`, and "ok-with-an-error" uses `at(fullPayload(), "exams", "data")`;
- `eachPartIsEitherOkWithDataOrFailedWithAReason`: `payload.put("exams", part);`;
- `overallStatuses` becomes:

```java
    static Stream<Arguments> overallStatuses() {
        Map<String, Object> wholeRunError = new HashMap<>();
        wholeRunError.put("timetable", null);
        wholeRunError.put("exams", null);
        wholeRunError.put("error_code", "bad_credentials");
        wholeRunError.put("error_message", "EduSoft rejected the password");
        Map<String, Object> onlyTimetable = new HashMap<>();
        onlyTimetable.put("exams", null);
        return Stream.of(
                Arguments.of("all-ok", Map.of(), "success"),
                Arguments.of("one-failed", Map.of("exams", failedPart()), "partial"),
                Arguments.of("all-failed", Map.of("timetable", failedPart(), "exams", failedPart()), "failed"),
                Arguments.of("only-timetable-sent", onlyTimetable, "success"),
                Arguments.of("whole-run-error", wholeRunError, "failed"));
    }
```

- the `sections()` lists lose `"tuition"` (Blackboard, Outlook and IUPay tests);
- `numbersAreNotTextAndFractionsAreNotWholeNumbers`: `at(fraction, "tuition", "data").put("amount_due", 12.5);` → `at(fraction, "exams", "data", "exams", 0).put("duration_min", 90.5);`;
- add, in the IUPay section:

```java
    @Test
    void anOldAgentsTuitionPartIsAcceptedButNotCounted() throws IOException {
        FinishRun finish = json.read(Files.readAllBytes(Payloads.SAMPLES.resolve("finish-old-agent-tuition.json")),
                FinishRun.class);

        assertThat(finish.sections().keySet()).containsExactly("timetable");
        assertThat(finish.tuition().ok()).isFalse();
        assertThat(finish.overallStatus()).isEqualTo("success");
    }

    @Test
    void anOldTuitionPartAloneIsNotAnUpload() {
        assertRefused(new HashMap<>(Map.of("tuition", failed("edusoft_changed", "Headers changed"))));
    }
```

(The whole-run-error case is covered by the shared invalid sample.)

`SyncApiTest.java`, `anOversizedUploadGets413`: `at(payload, "tuition", "data").put("status_text", "x".repeat(5_100_000));` → `at(payload, "timetable", "data").put("term_name", "x".repeat(5_100_000));`.

`IngestTest.java`:
- remove the imports of `SchoolTuition` and `SchoolTuitionRepository`, and the `SchoolTuitionRepository tuition;` field with its `@Autowired`;
- `aSuccessfulSyncSavesEveryPartWithTimesInUtc`: delete the five lines from `SchoolTuition bill = ...` to `assertThat(bill.getStatusText())...`;
- `aFailedPartKeepsItsOldDataAndRecordsWhy` becomes:

```java
    @Test
    void aFailedPartKeepsItsOldDataAndRecordsWhy() {
        sync(userId, fullPayload());
        Map<String, Object> payload = payloadWithCourse("IT002IU", "New course");
        payload.put("exams", failed("edusoft_changed", "Exam table not found"));

        assertThat(sync(userId, payload)).isEqualTo("partial");

        assertThat(exams.findAll(BY_ID)).singleElement().extracting(SchoolExam::getRoom).isEqualTo("A1.101");
        assertThat(courses.findAll(BY_ID)).extracting(SchoolCourse::getCourseCode).containsExactly("IT002IU");
        assertThat(lastRun().getSections().get("exams")).isEqualTo(Map.of(
                "status", "failed", "error_code", "edusoft_changed", "error_message", "Exam table not found"));
        assertThat(lastRun().getSections().get("timetable")).isEqualTo(Map.of("status", "ok"));
    }
```

- `aWholeRunErrorChangesNoData`: `assertThat(tuition.count()).isEqualTo(1);` → `assertThat(exams.count()).isEqualTo(1);`;
- `eachSyncRecordsWhatChanged`: `containsExactly(List.of("timetable", "added"), List.of("exams", "added"))`;
- `aFailedPartRecordsNoChanges`: `payload.put("exams", failed("edusoft_changed", "Table not found"));`;
- add:

```java
    @Test
    void anOldAgentsTuitionPartIsIgnored() {
        Map<String, Object> payload = fullPayload();
        payload.put("tuition", ok(Map.of("term_code", "20261", "amount_due", 12_500_000, "amount_paid", 0,
                "balance", 12_500_000)));

        assertThat(sync(userId, payload)).isEqualTo("success");

        assertThat(lastRun().getSections()).containsOnlyKeys("timetable", "exams");
        assertThat(changes.findAll(BY_ID)).noneMatch(c -> c.getSection().equals("tuition"));
    }
```

`ChangesTest.java`: delete the `// ---- Tuition` section (the `UNPAID` constant and its four tests), the `TuitionInfo` import, and `import java.time.LocalDate;` (nothing else uses it).

- [ ] **Step 7: Run them to see them fail**

Run: `(cd web && ./mvnw -B -q test -Dtest='SyncContractTest,IngestTest,ChangesTest,SyncApiTest')`
Expected: FAIL. `sections()` still lists `tuition`, and `anOldAgentsTuitionPartIsIgnored` finds a `tuition` section.

- [ ] **Step 8: Ignore `tuition` on the website**

`SyncContract.java`, in `FinishRun`:
- the `tuition` component gets a comment: `@Valid Section<Tuition> tuition, // agents from before IUPay: accepted, never counted or saved`;
- delete the `if (tuition != null) { sent.put("tuition", tuition); }` block from `sections()`, and its comment becomes `/** The parts that were sent, by name, in the order timetable, exams, iupay, blackboard, outlook. */`;
- `isErrorWithoutSections` returns `errorCode == null || (sections().isEmpty() && tuition == null);`.

`Ingest.java`:
- delete the line `part(run, summary, "tuition", payload.tuition(), data -> saveTuition(userId, data), now);`;
- delete `saveTuition` entirely;
- remove the `SchoolTuitionRepository tuition` field, its constructor parameter and assignment, and the imports of `SchoolTuition`, `SchoolTuitionRepository`, `Changes.TuitionInfo` and `SyncContract.Tuition`;
- the class comment's first sentence becomes: `Saves the result of a sync run, in one transaction. Each part that arrived correctly replaces that user's rows for that term (Blackboard and Outlook: all of them), after comparing old and new rows for the "What changed" feed (mail never adds to it). A part that failed keeps its old rows. An old agent's tuition part is ignored.`

`Changes.java`: delete the `TuitionInfo` record, the whole `// ---- Tuition` section with `tuition(...)`, and the now unused `day(...)` and `money(...)` helpers and `import java.time.LocalDate;`.

- [ ] **Step 9: Run every Java test**

Run: `(cd web && ./mvnw -B test)`
Expected: `Failures: 0, Errors: 0`.

- [ ] **Step 10: Commit**

```bash
git add -A contract web
git commit -m "feat(contract): an old agent's tuition part is still accepted but no longer counted, saved or announced

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: The website stores the bills

**Files:**
- Create: `web/src/main/resources/db/migration/V20260930_1_1__tuition_bills.sql`
- Create: `web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolTuitionBill.java`, `SchoolTuitionBillRepository.java`, `SchoolTuitionStatus.java`, `SchoolTuitionStatusRepository.java`
- Modify: `web/src/main/java/vn/edu/hcmiu/sla/school/sync/Ingest.java`
- Modify tests: `IngestTest.java`, `SchoolTablesTest.java`, `core/MigrationTest.java`

**Interfaces:**
- Consumes: `SyncContract.Iupay`, `TuitionBill` (Task 1); `Payloads.iupayPayload()`, `iupayUnpaidPayload()`.
- Produces: `SchoolTuitionBill(Integer userId, String billNo, String termCode, String termName, String description, String feeType, long amount, long discount, long fee, String status, LocalDate dueDate, LocalDate paidOn, String channel)` with getters, `getPayable()` (= amount − discount) and `isPaid()`; `SchoolTuitionBill.PAID = "paid"`; `SchoolTuitionBillRepository.findByUserIdOrderById(Integer)`, `deleteAllOfUser(Integer)`; `SchoolTuitionStatus(Integer userId, LocalDateTime checkedAt)` with `getCheckedAt()`; `SchoolTuitionStatusRepository extends JpaRepository<SchoolTuitionStatus, Integer>`.

- [ ] **Step 1: Write the failing tests**

`core/MigrationTest.java`, in `anEmptyDatabaseGetsEveryTable`, add `"school_tuition_bills", "school_tuition_status",` after `"school_mail_joined",`.

`model/SchoolTablesTest.java`, after `tuitionItemsAreKeptAsJson`, add:

```java
    @Test
    void aTuitionBillIsKept() {
        SchoolTuitionBill bill = new SchoolTuitionBill(userId, "E0000020001", "20262",
                "Academic year 2026-2027 - Semester 2", "Thu Học Phí HK 2\nIT093IU", "Thu Học Phí", 40_000_000,
                2_000_000, 0, "unpaid", LocalDate.of(2027, 2, 15), null, null);
        db.persist(bill);

        SchoolTuitionBill again = reloaded(bill, bill.getId());

        assertThat(List.of(again.getBillNo(), again.getDescription(), again.getStatus()))
                .containsExactly("E0000020001", "Thu Học Phí HK 2\nIT093IU", "unpaid");
        assertThat(List.of(again.getPayable(), again.getFee())).containsExactly(38_000_000L, 0L);
        assertThat(again.getDueDate()).isEqualTo(LocalDate.of(2027, 2, 15));
        assertThat(again.isPaid()).isFalse();
    }
```

`sync/IngestTest.java`: add the imports `vn.edu.hcmiu.sla.school.model.SchoolTuitionBill`, `SchoolTuitionBillRepository`, `SchoolTuitionStatus`, `SchoolTuitionStatusRepository`, and `static vn.edu.hcmiu.sla.school.sync.Payloads.iupayPayload` / `iupayUnpaidPayload`; add the fields

```java
    @Autowired
    SchoolTuitionBillRepository tuitionBills;

    @Autowired
    SchoolTuitionStatusRepository tuitionStatus;
```

and add a section:

```java
    // ---- IUPay ------------------------------------------------------------------

    String syncIupay(Integer who, Object part) {
        Map<String, Object> payload = fullPayload();
        payload.put("iupay", part);
        return sync(who, payload);
    }

    @Test
    void iupayBillsAreSavedAndTheCheckIsRemembered() {
        assertThat(syncIupay(userId, ok(iupayPayload()))).isEqualTo("success");

        List<SchoolTuitionBill> bills = tuitionBills.findByUserIdOrderById(userId);
        assertThat(bills).extracting(SchoolTuitionBill::getBillNo).containsExactly(
                "E0000014104", "4073743", "E0000000208", "4073742", "4073739", "4073741", "4073740");
        SchoolTuitionBill first = bills.get(0);
        assertThat(List.of(first.getStatus(), first.getChannel(), first.getTermName()))
                .containsExactly("paid", "Đóng qua kênh EduBill", "Academic year 2026-2027 - Semester 1");
        assertThat(first.getPaidOn()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(first.getPayable()).isEqualTo(65_250_000L);
        assertThat(tuitionStatus.findById(userId)).get().extracting(SchoolTuitionStatus::getCheckedAt).isNotNull();
        assertThat(lastRun().getSections().get("iupay")).isEqualTo(Map.of("status", "ok"));
        assertThat(changes.findAll(BY_ID)).noneMatch(c -> c.getSection().equals("iupay"));
    }

    @Test
    void aNewIupayReadReplacesTheBills() {
        syncIupay(userId, ok(iupayPayload()));

        syncIupay(userId, ok(iupayUnpaidPayload()));

        assertThat(tuitionBills.findByUserIdOrderById(userId))
                .extracting(b -> b.getBillNo() + " " + b.getStatus() + " " + b.getPayable() + " " + b.getDueDate())
                .containsExactly("E0000020001 unpaid 38000000 2027-02-15", "E0000020002 paying 1105650 2027-01-31",
                        "E0000020003 partly_paid 3000000 2026-12-31");
    }

    @Test
    void anEmptyListMeansNoBills() {
        syncIupay(userId, ok(iupayPayload()));

        syncIupay(userId, ok(Map.of("bills", List.of())));

        assertThat(tuitionBills.findByUserIdOrderById(userId)).isEmpty();
        assertThat(tuitionStatus.findById(userId)).isPresent();
    }

    @Test
    void aFailedIupayPartKeepsTheBillsAndTheLastCheck() {
        syncIupay(userId, ok(iupayPayload()));
        LocalDateTime checked = tuitionStatus.findById(userId).orElseThrow().getCheckedAt();

        assertThat(syncIupay(userId, failed("network", "IUPay couldn't be reached."))).isEqualTo("partial");

        assertThat(tuitionBills.findByUserIdOrderById(userId)).hasSize(7);
        assertThat(tuitionStatus.findById(userId).orElseThrow().getCheckedAt()).isEqualTo(checked);
    }

    @Test
    void anotherStudentsBillsAreUntouched() {
        Integer binh = makeUser("binh@example.com");
        syncIupay(binh, ok(iupayPayload()));

        syncIupay(userId, ok(Map.of("bills", List.of())));

        assertThat(tuitionBills.findByUserIdOrderById(binh)).hasSize(7);
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `(cd web && ./mvnw -B -q test -Dtest='IngestTest,SchoolTablesTest,MigrationTest')`
Expected: compilation FAILS (`SchoolTuitionBill` does not exist).

- [ ] **Step 3: Add the tables**

Create `web/src/main/resources/db/migration/V20260930_1_1__tuition_bills.sql`:

```sql
-- Tuition bills from IUPay (docs/superpowers/specs/2026-09-30-iupay-tuition-design.md, section 4.1).
-- Each good IUPay read replaces the student's bills. Amounts are VND; due_date and paid_on are Vietnam dates.
CREATE TABLE school_tuition_bills (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    bill_no VARCHAR(40) NOT NULL,
    term_code VARCHAR(20) NOT NULL,
    term_name VARCHAR(255) NULL,
    description VARCHAR(1000) NOT NULL,
    fee_type VARCHAR(255) NULL,
    amount BIGINT NOT NULL,
    discount BIGINT NOT NULL,
    fee BIGINT NOT NULL,
    status VARCHAR(12) NOT NULL,
    due_date DATE NULL,
    paid_on DATE NULL,
    channel VARCHAR(100) NULL,
    PRIMARY KEY (id),
    UNIQUE (user_id, bill_no),
    CONSTRAINT fk_school_tuition_bills_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- When IUPay was last read successfully (UTC); no row means never.
CREATE TABLE school_tuition_status (
    user_id INT NOT NULL,
    checked_at DATETIME NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_school_tuition_status_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
```

- [ ] **Step 4: Add the classes**

`web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolTuitionBill.java`:

```java
package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One tuition bill from IUPay. Amounts are VND; dueDate and paidOn are Vietnam dates. Each good IUPay read
 * replaces the student's bills. status: unpaid / paid / paying / partly_paid.
 */
@Entity
@Table(name = "school_tuition_bills")
public class SchoolTuitionBill {

    public static final String PAID = "paid";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "bill_no", nullable = false, length = 40)
    private String billNo;

    @Column(name = "term_code", nullable = false, length = 20)
    private String termCode;

    @Column(name = "term_name", length = 255)
    private String termName;

    @Column(nullable = false, length = 1000)
    private String description;

    @Column(name = "fee_type", length = 255)
    private String feeType;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false)
    private long discount;

    @Column(nullable = false)
    private long fee;

    @Column(nullable = false, length = 12)
    private String status;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "paid_on")
    private LocalDate paidOn;

    @Column(length = 100)
    private String channel;

    protected SchoolTuitionBill() {
    }

    public SchoolTuitionBill(Integer userId, String billNo, String termCode, String termName, String description,
            String feeType, long amount, long discount, long fee, String status, LocalDate dueDate, LocalDate paidOn,
            String channel) {
        this.userId = userId;
        this.billNo = billNo;
        this.termCode = termCode;
        this.termName = termName;
        this.description = description;
        this.feeType = feeType;
        this.amount = amount;
        this.discount = discount;
        this.fee = fee;
        this.status = status;
        this.dueDate = dueDate;
        this.paidOn = paidOn;
        this.channel = channel;
    }

    public Integer getId() {
        return id;
    }

    public Integer getUserId() {
        return userId;
    }

    public String getBillNo() {
        return billNo;
    }

    public String getTermCode() {
        return termCode;
    }

    public String getTermName() {
        return termName;
    }

    public String getDescription() {
        return description;
    }

    public String getFeeType() {
        return feeType;
    }

    public long getAmount() {
        return amount;
    }

    public long getDiscount() {
        return discount;
    }

    public long getFee() {
        return fee;
    }

    public String getStatus() {
        return status;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public LocalDate getPaidOn() {
        return paidOn;
    }

    public String getChannel() {
        return channel;
    }

    /** What the bill asks for after its discount (IUPay leaves the fee to the payment). */
    public long getPayable() {
        return amount - discount;
    }

    public boolean isPaid() {
        return PAID.equals(status);
    }
}
```

`SchoolTuitionBillRepository.java`:

```java
package vn.edu.hcmiu.sla.school.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolTuitionBillRepository extends JpaRepository<SchoolTuitionBill, Integer> {

    /** In the order IUPay listed them. */
    List<SchoolTuitionBill> findByUserIdOrderById(Integer userId);

    @Modifying
    @Query("delete from SchoolTuitionBill b where b.userId = :userId")
    void deleteAllOfUser(Integer userId);
}
```

`SchoolTuitionStatus.java`:

```java
package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** When the laptop last read IUPay successfully. One row per user; no row means IUPay was never read. */
@Entity
@Table(name = "school_tuition_status")
public class SchoolTuitionStatus {

    @Id
    @Column(name = "user_id")
    private Integer userId;

    @Column(name = "checked_at", nullable = false)
    private LocalDateTime checkedAt; // UTC

    protected SchoolTuitionStatus() {
    }

    public SchoolTuitionStatus(Integer userId, LocalDateTime checkedAt) {
        this.userId = userId;
        this.checkedAt = checkedAt;
    }

    public Integer getUserId() {
        return userId;
    }

    public LocalDateTime getCheckedAt() {
        return checkedAt;
    }
}
```

`SchoolTuitionStatusRepository.java`:

```java
package vn.edu.hcmiu.sla.school.model;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SchoolTuitionStatusRepository extends JpaRepository<SchoolTuitionStatus, Integer> {
}
```

- [ ] **Step 5: Save the `iupay` part in `Ingest`**

In `Ingest.java`:
- import `SchoolTuitionBill`, `SchoolTuitionBillRepository`, `SchoolTuitionStatus`, `SchoolTuitionStatusRepository`, `SyncContract.Iupay`, `SyncContract.TuitionBill`;
- add the fields `private final SchoolTuitionBillRepository tuitionBills;` and `private final SchoolTuitionStatusRepository tuitionStatus;`, add them as the last two constructor parameters (`SchoolTuitionBillRepository tuitionBills, SchoolTuitionStatusRepository tuitionStatus`) and assign them;
- in `finishRun`, after the `exams` line, add:

```java
        part(run, summary, "iupay", payload.iupay(), data -> saveIupay(userId, data, now), now);
```

- after `saveExams`, add:

```java
    /** Replaces the student's bills with this read and remembers when IUPay was read. Bills never enter "What changed". */
    private List<Change> saveIupay(Integer userId, Iupay data, LocalDateTime now) {
        tuitionBills.deleteAllOfUser(userId);
        for (TuitionBill b : data.bills()) {
            tuitionBills.save(new SchoolTuitionBill(userId, b.billNo(), b.termCode(), b.termName(), b.description(),
                    b.feeType(), b.amount(), b.discount(), b.fee(), b.status(), b.dueDate(), b.paidOn(), b.channel()));
        }
        tuitionStatus.save(new SchoolTuitionStatus(userId, now));
        return List.of();
    }
```

(The JPQL delete runs at once, before the inserts, so the `(user_id, bill_no)` unique key never sees a bill twice. That's the same pattern as `saveOutlook`.)

- [ ] **Step 6: Run the tests**

Run: `(cd web && ./mvnw -B -q test -Dtest='IngestTest,SchoolTablesTest,MigrationTest,SyncApiTest')`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A web
git commit -m "feat(web): store IUPay's bills, replaced at each good read, and when IUPay was last read

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: The Tuition page shows IUPay's bills; the old tuition table goes

**Files:**
- Create: `web/src/main/resources/db/migration/V20260930_1_2__drop_school_tuition.sql`
- Delete: `web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolTuition.java`, `SchoolTuitionRepository.java`
- Create: `web/src/main/java/vn/edu/hcmiu/sla/school/pages/TuitionBills.java`
- Modify: `pages/SchoolFormat.java`, `pages/SchoolController.java`, `templates/school/tuition.html`, `templates/school/fragments.html`, `static/css/style.css`
- Create test: `web/src/test/java/vn/edu/hcmiu/sla/school/pages/TuitionBillsTest.java`
- Modify tests: `SchoolTestData.java`, `pages/SchoolPagesTest.java`, `model/SchoolTablesTest.java`, `core/MigrationTest.java`

**Interfaces:**
- Consumes: `SchoolTuitionBill`, `SchoolTuitionBillRepository`, `SchoolTuitionStatus`, `SchoolTuitionStatusRepository` (Task 5).
- Produces: `TuitionBills.toPay(List<SchoolTuitionBill>)`, `paid(...)`, `notice(List<SchoolTuitionBill>, LocalDate today) -> Notice` (null when nothing to pay), `recent(List<SchoolTuitionBill>, LocalDate today)`, record `TuitionBills.Notice(long total, LocalDate due, boolean overdue)`, `TuitionBills.RECENT_DAYS = 30`; `SchoolFormat.billStatus(String)`; fragment `school/fragments :: pay-button`; `SchoolTestData.bill(AppUser, String billNo, String status, long amount, long discount, LocalDate dueDate, LocalDate paidOn)` and `SchoolTestData.tuitionChecked(AppUser, LocalDateTime utc)`. `SchoolController` has fields `tuitionBills` and `tuitionStatus` (used again in Task 7).

- [ ] **Step 1: Write the failing unit tests**

Create `web/src/test/java/vn/edu/hcmiu/sla/school/pages/TuitionBillsTest.java`:

```java
package vn.edu.hcmiu.sla.school.pages;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import vn.edu.hcmiu.sla.school.model.SchoolTuitionBill;
import vn.edu.hcmiu.sla.school.pages.TuitionBills.Notice;

class TuitionBillsTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

    static SchoolTuitionBill bill(String number, String status, long amount, long discount, LocalDate due,
            LocalDate paidOn) {
        return new SchoolTuitionBill(1, number, "20261", null, "Bill " + number, null, amount, discount, 0, status, due,
                paidOn, null);
    }

    static final SchoolTuitionBill LATE = bill("A", "unpaid", 10_000_000, 1_000_000, LocalDate.of(2026, 10, 15), null);
    static final SchoolTuitionBill SOON = bill("B", "paying", 1_105_650, 0, LocalDate.of(2026, 10, 10), null);
    static final SchoolTuitionBill UNDATED = bill("C", "partly_paid", 3_000_000, 0, null, null);
    static final SchoolTuitionBill PAID_TODAY = bill("D", "paid", 65_250_000, 0, null, TODAY);
    static final SchoolTuitionBill PAID_30_DAYS_AGO = bill("E", "paid", 100, 0, null, TODAY.minusDays(30));
    static final SchoolTuitionBill PAID_31_DAYS_AGO = bill("F", "paid", 200, 0, null, TODAY.minusDays(31));

    static final List<SchoolTuitionBill> ALL = List.of(PAID_31_DAYS_AGO, LATE, PAID_TODAY, UNDATED, SOON,
            PAID_30_DAYS_AGO);

    @Test
    void billsToPayComeSoonestDueFirstWithUndatedOnesLast() {
        assertThat(TuitionBills.toPay(ALL)).containsExactly(SOON, LATE, UNDATED);
    }

    @Test
    void paidBillsComeNewestFirst() {
        assertThat(TuitionBills.paid(ALL)).containsExactly(PAID_TODAY, PAID_30_DAYS_AGO, PAID_31_DAYS_AGO);
    }

    @Test
    void theNoticeAddsUpWhatIsLeftAfterDiscountsAndGivesTheEarliestDueDate() {
        assertThat(TuitionBills.notice(ALL, TODAY))
                .isEqualTo(new Notice(9_000_000 + 1_105_650 + 3_000_000, LocalDate.of(2026, 10, 10), false));
    }

    @Test
    void theNoticeIsOverdueOnceTheEarliestDueDateHasPassed() {
        assertThat(TuitionBills.notice(ALL, LocalDate.of(2026, 10, 11)).overdue()).isTrue();
        assertThat(TuitionBills.notice(ALL, LocalDate.of(2026, 10, 10)).overdue()).isFalse();
    }

    @Test
    void aBillWithoutADueDateIsNeverOverdue() {
        assertThat(TuitionBills.notice(List.of(UNDATED), TODAY)).isEqualTo(new Notice(3_000_000, null, false));
    }

    @Test
    void noNoticeWhenEverythingIsPaidOrThereAreNoBills() {
        assertThat(TuitionBills.notice(List.of(PAID_TODAY), TODAY)).isNull();
        assertThat(TuitionBills.notice(List.of(), TODAY)).isNull();
    }

    @Test
    void recentBillsAreTheOnesToPayThenPaymentsFromTheLast30Days() {
        assertThat(TuitionBills.recent(ALL, TODAY)).containsExactly(SOON, LATE, UNDATED, PAID_TODAY, PAID_30_DAYS_AGO);
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `(cd web && ./mvnw -B -q test -Dtest=TuitionBillsTest)`
Expected: compilation FAILS (`TuitionBills` does not exist).

- [ ] **Step 3: Write `TuitionBills`**

Create `web/src/main/java/vn/edu/hcmiu/sla/school/pages/TuitionBills.java`:

```java
package vn.edu.hcmiu.sla.school.pages;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import vn.edu.hcmiu.sla.school.model.SchoolTuitionBill;

/** Sorts and sums IUPay bills for the Tuition page and the Overview. Pure functions; dates are Vietnam dates. */
public final class TuitionBills {

    private TuitionBills() {
    }

    static final int RECENT_DAYS = 30; // a payment stays in the Overview's Bills list this long

    /** The Overview's notice: the total still to pay, the earliest due date (or null), and whether it has passed. */
    public record Notice(long total, LocalDate due, boolean overdue) {
    }

    private static final Comparator<SchoolTuitionBill> SOONEST_DUE = Comparator
            .comparing(SchoolTuitionBill::getDueDate, Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()))
            .thenComparing(SchoolTuitionBill::getBillNo);

    private static final Comparator<SchoolTuitionBill> NEWEST_PAID = Comparator
            .comparing(SchoolTuitionBill::getPaidOn, Comparator.nullsLast(Comparator.<LocalDate>reverseOrder()))
            .thenComparing(SchoolTuitionBill::getBillNo);

    /** Bills not paid yet (unpaid, paying, partly paid), soonest due first; bills without a due date last. */
    public static List<SchoolTuitionBill> toPay(List<SchoolTuitionBill> bills) {
        return bills.stream().filter(bill -> !bill.isPaid()).sorted(SOONEST_DUE).toList();
    }

    /** Paid bills, newest payment first. */
    public static List<SchoolTuitionBill> paid(List<SchoolTuitionBill> bills) {
        return bills.stream().filter(SchoolTuitionBill::isPaid).sorted(NEWEST_PAID).toList();
    }

    /** What the Overview's notice says, or null when nothing is left to pay. */
    public static Notice notice(List<SchoolTuitionBill> bills, LocalDate today) {
        List<SchoolTuitionBill> toPay = toPay(bills);
        if (toPay.isEmpty()) {
            return null;
        }
        long total = toPay.stream().mapToLong(SchoolTuitionBill::getPayable).sum();
        LocalDate due = toPay.get(0).getDueDate();
        return new Notice(total, due, due != null && due.isBefore(today));
    }

    /** The Overview's Bills list: the bills to pay, then the payments of the last 30 days, newest first. */
    public static List<SchoolTuitionBill> recent(List<SchoolTuitionBill> bills, LocalDate today) {
        LocalDate since = today.minusDays(RECENT_DAYS);
        List<SchoolTuitionBill> recent = new ArrayList<>(toPay(bills));
        paid(bills).stream().filter(bill -> bill.getPaidOn() != null && !bill.getPaidOn().isBefore(since))
                .forEach(recent::add);
        return recent;
    }
}
```

- [ ] **Step 4: Run the unit tests**

Run: `(cd web && ./mvnw -B -q test -Dtest=TuitionBillsTest)`
Expected: PASS.

- [ ] **Step 5: Write the failing page tests**

`SchoolTestData.java`: replace the `tuition(...)` method (and its `SchoolTuition` import) with:

```java
    /** An IUPay bill: description "Thu Học Phí " + its number; a paid bill was paid through EduBill. */
    public void bill(AppUser user, String billNo, String status, long amount, long discount, LocalDate dueDate,
            LocalDate paidOn) {
        boolean paid = SchoolTuitionBill.PAID.equals(status);
        db.persist(new SchoolTuitionBill(user.id(), billNo, "20261", "Academic year 2026-2027 - Semester 1",
                "Thu Học Phí " + billNo, "Thu Học Phí", amount, discount, 0, status, dueDate, paidOn,
                paid ? "Đóng qua kênh EduBill" : null));
        db.flush();
    }

    /** IUPay was read at this UTC time. */
    public void tuitionChecked(AppUser user, LocalDateTime utc) {
        db.persist(new SchoolTuitionStatus(user.id(), utc));
        db.flush();
    }
```

with the imports `vn.edu.hcmiu.sla.school.model.SchoolTuitionBill` and `SchoolTuitionStatus`. If `List` or `LocalDate` imports become unused, remove only those the compiler reports as unused. `LocalDate` is still used by `bill`.

`SchoolPagesTest.java`: replace `theTuitionPageShowsBalanceAndDueDate` with these tests, and keep `theTuitionPageLinksToTheIuPaymentSiteInANewTab` and `theTuitionPageSaysWhenNothingIsSynced` as they are:

```java
    static final LocalDateTime CHECKED = LocalDateTime.of(2026, 9, 30, 4, 14); // Wed 30/09 11:14 in Vietnam

    @Test
    void theTuitionPageShowsWhatIsLeftToPaySoonestDueFirst() throws Exception {
        data.tuitionChecked(an, CHECKED);
        data.bill(an, "E0000020001", "unpaid", 40_000_000, 2_000_000, LocalDate.of(2026, 10, 15), null);
        data.bill(an, "E0000020002", "paying", 1_105_650, 0, LocalDate.of(2026, 10, 10), null);
        clock.set(LocalDateTime.of(2026, 10, 1, 1, 0));

        String html = page("/school/tuition");

        assertThat(html).contains("38,000,000 VND", "due 15/10/2026", "Unpaid", "bill E0000020001",
                "1,105,650 VND", "due 10/10/2026", "Payment in progress").doesNotContain("No tuition to pay");
        assertThat(html.indexOf("Thu Học Phí E0000020002")).isLessThan(html.indexOf("Thu Học Phí E0000020001"));
        assertThat(html.split("Pay on IUPay ↗", -1)).hasSize(3);
    }

    @Test
    void aBillPastItsDueDateSaysSinceWhen() throws Exception {
        data.tuitionChecked(an, CHECKED);
        data.bill(an, "E0000020003", "partly_paid", 3_000_000, 0, LocalDate.of(2026, 10, 15), null);
        clock.set(LocalDateTime.of(2026, 10, 20, 1, 0));

        assertThat(page("/school/tuition")).contains("overdue since 15/10/2026", "is-overdue",
                "Partly paid, check IUPay for the rest");
    }

    @Test
    void withEverythingPaidTheTuitionPageSaysSoAndListsThePaymentsNewestFirst() throws Exception {
        data.tuitionChecked(an, CHECKED);
        data.bill(an, "4073743", "paid", 36_900_000, 0, null, LocalDate.of(2025, 10, 1));
        data.bill(an, "E0000014104", "paid", 65_250_000, 0, null, LocalDate.of(2026, 9, 30));

        String html = page("/school/tuition");

        assertThat(html).contains("No tuition to pay", "Last checked on IUPay: Wed 30/09 11:14");
        String paid = section(html, "Paid");
        assertThat(paid).contains("65,250,000", "30/09/2026", "Đóng qua kênh EduBill", "36,900,000", "01/10/2025");
        assertThat(paid.indexOf("Thu Học Phí E0000014104")).isLessThan(paid.indexOf("Thu Học Phí 4073743"));
    }

    @Test
    void aDescriptionIsShownAsTextNotHtml() throws Exception {
        data.tuitionChecked(an, CHECKED);
        db.persist(new SchoolTuitionBill(an.id(), "E0000020009", "20261", null, "<b>Học phí</b>\nIT093IU", null,
                1_000, 0, 0, "unpaid", null, null, null));
        db.flush();

        assertThat(page("/school/tuition")).contains("&lt;b&gt;Học phí&lt;/b&gt;").doesNotContain("<b>Học phí</b>");
    }

    @Test
    void theTuitionPageShowsOnlyMyBills() throws Exception {
        AppUser binh = data.user("binh@example.com");
        data.tuitionChecked(binh, CHECKED);
        data.bill(binh, "E0000099999", "unpaid", 5_000_000, 0, LocalDate.of(2026, 10, 15), null);
        data.tuitionChecked(an, CHECKED);

        assertThat(page("/school/tuition")).doesNotContain("E0000099999").contains("No tuition to pay");
    }
```

(Add `import vn.edu.hcmiu.sla.school.model.SchoolTuitionBill;` to `SchoolPagesTest` if it isn't there.)

`SchoolTablesTest.java`: delete `tuitionItemsAreKeptAsJson` and the `SchoolTuition` import.

`MigrationTest.java`: remove `"school_tuition",` from the list in `anEmptyDatabaseGetsEveryTable`, and add at the end of that test:

```java
        assertThat(tables(dataSource)).doesNotContain("school_tuition");
```

- [ ] **Step 6: Drop the old table and class**

Create `web/src/main/resources/db/migration/V20260930_1_2__drop_school_tuition.sql`:

```sql
-- EduSoft's per-semester tuition summary, replaced by IUPay's bills (school_tuition_bills). Nothing is lost:
-- IUPay holds every bill, and the next sync fills the new table.
DROP TABLE school_tuition;
```

```bash
git rm web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolTuition.java web/src/main/java/vn/edu/hcmiu/sla/school/model/SchoolTuitionRepository.java
```

- [ ] **Step 7: The Tuition page**

`SchoolFormat.java`: add next to `CHANGE_LABELS`:

```java
    static final Map<String, String> BILL_STATUSES = Map.of("unpaid", "Unpaid", "paid", "Paid",
            "paying", "Payment in progress", "partly_paid", "Partly paid, check IUPay for the rest");
```

and after `changeLabel`:

```java
    /** A bill's status as the pages say it: "Unpaid", "Payment in progress", … */
    public String billStatus(String status) {
        return BILL_STATUSES.getOrDefault(status, status);
    }
```

`SchoolController.java`:
- replace the import and field `SchoolTuitionRepository tuition` with `SchoolTuitionBillRepository tuitionBills` and `SchoolTuitionStatusRepository tuitionStatus`, both as constructor parameters in the same place (`SchoolTuitionBillRepository tuitionBills, SchoolTuitionStatusRepository tuitionStatus,`) with their assignments; import `SchoolTuitionBill`, `SchoolTuitionBillRepository`, `SchoolTuitionStatus`, `SchoolTuitionStatusRepository`;
- `tuition(...)` becomes:

```java
    @GetMapping("/tuition")
    String tuition(@AuthenticationPrincipal AppUser user, Model model) {
        List<SchoolTuitionBill> bills = tuitionBills.findByUserIdOrderById(user.id());
        model.addAttribute("checkedAt",
                tuitionStatus.findById(user.id()).map(SchoolTuitionStatus::getCheckedAt).orElse(null));
        model.addAttribute("toPay", TuitionBills.toPay(bills));
        model.addAttribute("paid", TuitionBills.paid(bills));
        model.addAttribute("today", VietnamTime.date(now()));
        return "school/tuition";
    }
```

`templates/school/fragments.html`: before the closing `</body>`, add:

```html
<!--/* IU's official payment site, in a new tab. */-->
<a th:fragment="pay-button" class="button" href="https://iupay.hcmiu.edu.vn/search/dhqt" target="_blank"
   rel="noopener noreferrer">Pay on IUPay ↗</a>
```

`templates/school/tuition.html` becomes:

```html
<!doctype html>
<html xmlns:th="http://www.thymeleaf.org" th:replace="~{layout :: page(~{::title}, ~{::main})}">
<head>
  <title>Tuition · School-Life-Assistant</title>
</head>
<body>
<main>
  <h1>Tuition</h1>
  <nav th:replace="~{school/fragments :: subnav('tuition')}"></nav>

  <!--/* Bills come from IUPay (docs/superpowers/specs/2026-09-30-iupay-tuition-design.md, 4.3). */-->
  <section class="card pay-card" th:if="${checkedAt == null}">
    <div>
      <h2>No tuition information yet</h2>
      <p class="muted">It appears after the next sync, which reads your bills on IUPay.</p>
    </div>
    <a th:replace="~{school/fragments :: pay-button}">Pay on IUPay ↗</a>
  </section>

  <th:block th:if="${checkedAt != null}">
    <section th:each="bill : ${toPay}" class="card pay-card bill-card"
             th:classappend="${bill.dueDate != null and bill.dueDate.isBefore(today)} ? 'is-overdue'">
      <div>
        <h2 class="bill-text" th:text="${bill.description}">Thu Học Phí HK 1 (2026-2027)</h2>
        <p class="muted"><th:block th:text="${bill.termName != null ? bill.termName : bill.termCode}">Academic year
          2026-2027 - Semester 1</th:block> · <th:block th:text="|bill ${bill.billNo}|">bill E0000014104</th:block></p>
        <p><strong th:text="|${@schoolFormat.money(bill.payable)} VND|">65,250,000 VND</strong><th:block
            th:if="${bill.dueDate != null}"> · <span class="bill-due"
            th:text="|${bill.dueDate.isBefore(today) ? 'overdue since' : 'due'} ${@schoolFormat.day(bill.dueDate)}|">due
            15/10/2026</span></th:block> · <span th:text="${@schoolFormat.billStatus(bill.status)}">Unpaid</span></p>
      </div>
      <a th:replace="~{school/fragments :: pay-button}">Pay on IUPay ↗</a>
    </section>

    <section class="card pay-card" th:if="${toPay.isEmpty()}">
      <div>
        <h2>No tuition to pay</h2>
        <p class="muted" th:text="|Last checked on IUPay: ${@schoolFormat.when(checkedAt)}|">Last checked on IUPay:
          Wed 30/09 11:14</p>
      </div>
      <a th:replace="~{school/fragments :: pay-button}">Pay on IUPay ↗</a>
    </section>

    <section class="card" th:unless="${paid.isEmpty()}">
      <h2>Paid</h2>
      <div class="table-scroll">
        <table class="bills">
          <thead>
            <tr><th>Paid on</th><th>Semester</th><th>What for</th><th class="num">Amount (VND)</th><th>How</th></tr>
          </thead>
          <tbody>
            <tr th:each="bill : ${paid}">
              <td th:text="${bill.paidOn != null ? @schoolFormat.day(bill.paidOn) : '-'}">30/09/2026</td>
              <td th:text="${bill.termName != null ? bill.termName : bill.termCode}">Academic year 2026-2027 - Semester 1</td>
              <td class="bill-text" th:text="${bill.description}">Thu Học Phí HK 1 (2026-2027)</td>
              <td class="num" th:text="${@schoolFormat.money(bill.payable)}">65,250,000</td>
              <td th:text="${bill.channel != null ? bill.channel : '-'}">Đóng qua kênh EduBill</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </th:block>
</main>
</body>
</html>
```

`static/css/style.css`: replace the `/* School: tuition payment */` block with:

```css
/* School: tuition bills from IUPay */
.pay-card { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; }
.pay-card h2, .pay-card p { margin: 0 0 4px; }
.button { text-decoration: none; }
.bill-text { white-space: pre-line; }
.bill-card.is-overdue { border-left: 4px solid var(--error); }
.is-overdue .bill-due { color: var(--error); font-weight: 600; }
.table-scroll { overflow-x: auto; }
.bills { width: 100%; border-collapse: collapse; font-size: 0.9rem; }
.bills th, .bills td { padding: 6px 8px; text-align: left; vertical-align: top; border-top: 1px solid var(--border); }
.bills .num { text-align: right; white-space: nowrap; font-variant-numeric: tabular-nums; }
```

- [ ] **Step 8: Run every Java test**

Run: `(cd web && ./mvnw -B test)`
Expected: `Failures: 0, Errors: 0`. Then `grep -rn "SchoolTuition\b\|SchoolTuitionRepository\|school_tuition\b" web/src` finds only the two migrations (`V1__baseline.sql` and `V20260930_1_2__drop_school_tuition.sql`) and `MigrationTest`'s `doesNotContain`.

- [ ] **Step 9: Commit**

```bash
git add -A web
git commit -m "feat(web): the Tuition page shows IUPay's bills to pay and the paid history; drop EduSoft's tuition table

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: The Overview's tuition notice and Bills list

**Files:**
- Modify: `web/src/main/java/vn/edu/hcmiu/sla/school/pages/SchoolController.java`, `templates/school/index.html`, `static/css/style.css`
- Modify test: `pages/SchoolPagesTest.java`

**Interfaces:**
- Consumes: `TuitionBills.notice`, `TuitionBills.recent`, `SchoolFormat.billStatus`, `SchoolTestData.bill` (Task 6).
- Produces: model attributes `tuitionNotice` (`TuitionBills.Notice` or null) and `recentBills` on `/school`.

- [ ] **Step 1: Write the failing page tests**

In `SchoolPagesTest.java`, in the `// ---- Overview` section, add:

```java
    @Test
    void theOverviewShowsATuitionNoticeWhileSomethingIsUnpaid() throws Exception {
        data.bill(an, "E0000020001", "unpaid", 40_000_000, 2_000_000, LocalDate.of(2026, 10, 15), null);
        data.bill(an, "E0000020002", "paying", 1_105_650, 0, LocalDate.of(2026, 10, 10), null);
        clock.set(LocalDateTime.of(2026, 10, 1, 1, 0));

        String html = page("/school");

        assertThat(html).contains("Tuition to pay: 39,105,650 VND", "due 10/10/2026", "See tuition →")
                .doesNotContain("tuition-notice is-overdue");
    }

    @Test
    void theTuitionNoticeTurnsRedOnceItIsOverdue() throws Exception {
        data.bill(an, "E0000020002", "unpaid", 1_105_650, 0, LocalDate.of(2026, 10, 10), null);
        clock.set(LocalDateTime.of(2026, 10, 12, 1, 0));

        assertThat(page("/school")).contains("tuition-notice is-overdue", "overdue since 10/10/2026");
    }

    @Test
    void noTuitionNoticeWhenEverythingIsPaid() throws Exception {
        data.bill(an, "E0000014104", "paid", 65_250_000, 0, null, LocalDate.of(2026, 9, 30));
        data.bill(data.user("binh@example.com"), "E0000099999", "unpaid", 5_000_000, 0, LocalDate.of(2026, 10, 15),
                null);

        assertThat(page("/school")).doesNotContain("Tuition to pay", "E0000099999");
    }

    @Test
    void theBillsListHasBillsToPayAndPaymentsFromTheLast30Days() throws Exception {
        data.bill(an, "E0000020001", "unpaid", 40_000_000, 0, LocalDate.of(2026, 10, 15), null);
        data.bill(an, "E0000014104", "paid", 65_250_000, 0, null, LocalDate.of(2026, 9, 30));
        data.bill(an, "E0000000208", "paid", 40_273_756, 0, null, LocalDate.of(2026, 8, 31)); // 31 days before 01/10
        clock.set(LocalDateTime.of(2026, 10, 1, 1, 0));

        String bills = section(page("/school"), "Bills");

        assertThat(bills).contains("New:", "Thu Học Phí E0000020001: 40,000,000 VND", ", due 15/10/2026", "(Unpaid)",
                "Paid Wed 30/09:", "Thu Học Phí E0000014104: 65,250,000 VND", "Đóng qua kênh EduBill", "See all →")
                .doesNotContain("E0000000208");
        assertThat(bills.indexOf("E0000020001")).isLessThan(bills.indexOf("E0000014104"));
    }

    @Test
    void theBillsListSaysWhenThereIsNothingNew() throws Exception {
        assertThat(section(page("/school"), "Bills")).contains("No new bills or payments in the last 30 days.");
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `(cd web && ./mvnw -B -q test -Dtest=SchoolPagesTest)`
Expected: FAIL. The page has no notice, and `section(..., "Bills")` fails because there is no Bills heading.

- [ ] **Step 3: Add them to the Overview**

`SchoolController.overview(...)`: before `model.addAttribute("changes", ...)`, add:

```java
        List<SchoolTuitionBill> bills = tuitionBills.findByUserIdOrderById(user.id());
        model.addAttribute("tuitionNotice", TuitionBills.notice(bills, today));
        model.addAttribute("recentBills", TuitionBills.recent(bills, today));
```

`templates/school/index.html`: right after `<section th:replace="~{school/fragments :: status-card}"></section>`, add:

```html
  <!--/* Only while some bill isn't paid (docs/superpowers/specs/2026-09-30-iupay-tuition-design.md, 4.4). */-->
  <section class="card tuition-notice" th:if="${tuitionNotice != null}"
           th:classappend="${tuitionNotice.overdue} ? 'is-overdue'">
    <span>💰 <strong th:text="|Tuition to pay: ${@schoolFormat.money(tuitionNotice.total)} VND|">Tuition to pay:
      65,250,000 VND</strong><th:block th:if="${tuitionNotice.due != null}">, <span class="bill-due"
      th:text="|${tuitionNotice.overdue ? 'overdue since' : 'due'} ${@schoolFormat.day(tuitionNotice.due)}|">due
      15/10/2026</span></th:block></span>
    <a th:href="@{/school/tuition}">See tuition →</a>
  </section>
```

and, in the second card, just before `<h2>What changed</h2>`, add:

```html
      <h2>Bills</h2>
      <ul class="changes" th:unless="${recentBills.isEmpty()}">
        <li th:each="bill : ${recentBills}">
          <th:block th:unless="${bill.paid}"><strong>New:</strong> <th:block
              th:text="|${bill.description}: ${@schoolFormat.money(bill.payable)} VND|">Thu Học Phí HK 1 (2026-2027):
              65,250,000 VND</th:block><th:block th:if="${bill.dueDate != null}"
              th:text="|, due ${@schoolFormat.day(bill.dueDate)}|">, due 15/10/2026</th:block>
            <span class="muted" th:text="|(${@schoolFormat.billStatus(bill.status)})|">(Unpaid)</span></th:block>
          <th:block th:if="${bill.paid}"><strong th:text="|Paid ${@schoolFormat.dayLabel(bill.paidOn)}:|">Paid Wed
              30/09:</strong> <th:block th:text="|${bill.description}: ${@schoolFormat.money(bill.payable)} VND|">Thu
              Học Phí HK 1 (2026-2027): 65,250,000 VND</th:block><th:block th:if="${bill.channel != null}"
              th:text="| · ${bill.channel}|"> · Đóng qua kênh EduBill</th:block></th:block>
        </li>
      </ul>
      <p class="muted" th:if="${recentBills.isEmpty()}">No new bills or payments in the last 30 days.</p>
      <p><a th:href="@{/school/tuition}">See all →</a></p>
```

`static/css/style.css`: after the tuition block from Task 6, add:

```css
.tuition-notice { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 4px 12px;
  border-left: 4px solid var(--accent); }
.tuition-notice.is-overdue { border-left-color: var(--error); }
```

(`--accent` is the site's existing accent colour; check it with `grep -n "\-\-accent:" web/src/main/resources/static/css/style.css`.)

- [ ] **Step 4: Run every Java test**

Run: `(cd web && ./mvnw -B test)`
Expected: `Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add -A web
git commit -m "feat(web): the Overview tells the student about tuition to pay and lists new bills and recent payments

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: IUPay gets its own line in the sync status

**Files:**
- Modify: `web/src/main/java/vn/edu/hcmiu/sla/school/pages/SyncStatus.java`
- Modify tests: `pages/SyncStatusTest.java`, `pages/SchoolPagesTest.java`

**Interfaces:**
- Produces: `SyncStatus.SYSTEMS` includes `IUPay` (part `iupay`) between EduSoft and Blackboard; `IUPAY_PROBLEMS`, `IUPAY_HINTS`; `PART_NAMES.get("iupay") == "tuition (IUPay)"`.

- [ ] **Step 1: Write the failing tests**

In `SyncStatusTest.java`:
- `edu()` becomes `parts(Map.entry("timetable", OK), Map.entry("exams", OK))` (EduSoft's parts from now on; tests that add `tuition` themselves stand for old runs);
- add the imports `org.junit.jupiter.params.ParameterizedTest` and `org.junit.jupiter.params.provider.CsvSource` if missing, and append:

```java
    // ---- IUPay --------------------------------------------------------------------

    @Test
    void iupayHasItsOwnLineAfterEduSoft() {
        Map<String, Map<String, String>> sections = edu();
        sections.put("iupay", OK);

        assertThat(SyncStatus.systemLines(List.of(run("success", "07:00", "07:05", null, sections)), NOW))
                .extracting(SystemLine::name, SystemLine::state)
                .containsExactly(tuple("EduSoft", "ok"), tuple("IUPay", "ok"));
    }

    @ParameterizedTest
    @CsvSource({  // no apostrophes: CsvSource quotes with '
        "network, be reached",
        "extra_verification, asks for a captcha",
        "bad_credentials, recognise your student ID",
        "source_changed, data format changed",
    })
    void anIupayProblemIsNamedOnItsOwnLineAndNeverPauses(String code, String words) {
        Map<String, Map<String, String>> sections = edu();
        sections.put("iupay", bad(code));

        List<SystemLine> lines = SyncStatus.systemLines(List.of(run("partial", "07:00", "07:05", null, sections)), NOW);

        assertThat(lines.get(0)).isEqualTo(new SystemLine("EduSoft", "ok", "synced at 14:05"));
        assertThat(lines.get(1).name()).isEqualTo("IUPay");
        assertThat(lines.get(1).state()).isEqualTo("failed");
        assertThat(lines.get(1).text()).contains(words);
    }

    @Test
    void aRunWhereOnlyIupayFailedSaysIupayInTheHeadline() {
        Status result = status(run("failed", "07:00", "07:05", null, parts(Map.entry("iupay", bad("extra_verification")))));

        assertThat(List.of(result.state(), result.headline()))
                .containsExactly("failed", "Sync failed: IUPay now asks for a captcha");
    }

    @Test
    void aPartlySyncedRunNamesIupayAsTuition() {
        Map<String, Map<String, String>> sections = edu();
        sections.put("iupay", bad("network"));

        assertThat(status(run("partial", "07:00", "07:05", null, sections)).detail())
                .startsWith("Couldn't read: tuition (IUPay).");
    }
```

In `SchoolPagesTest.schoolHomeShowsALinePerSystem`, add `"iupay", Map.of("status", "ok"),` to the run's sections, and `"IUPay:"` to the expected `contains(...)`.

- [ ] **Step 2: Run them to see them fail**

Run: `(cd web && ./mvnw -B -q test -Dtest='SyncStatusTest,SchoolPagesTest')`
Expected: FAIL. There is no IUPay line, and the headline says EduSoft.

- [ ] **Step 3: Add IUPay to `SyncStatus`**

- `PART_ORDER = List.of("timetable", "exams", "tuition", "iupay", "blackboard", "outlook");` and the class comment's order sentence says the same;
- after `OUTLOOK_PROBLEMS`, add:

```java
    static final Map<String, Problem> IUPAY_PROBLEMS = Map.of(
            "network", new Problem("failed", "Sync failed: IUPay couldn't be reached", RETRY),
            "extra_verification", new Problem("failed", "Sync failed: IUPay now asks for a captcha",
                    "Tuition shows the last bills known. Check IUPay in your browser; the agent never passes a captcha."),
            "bad_credentials", new Problem("failed", "Sync failed: IUPay didn't recognise your student ID",
                    "Check the student ID with `sla-agent status`; run `sla-agent setup` if it is wrong."),
            "source_changed", new Problem("failed", "Sync failed: IUPay's data format has changed",
                    "sla-agent needs an update to read it."));

    /** IUPay's own words on its line; other codes use FAILURE_HINTS. IUPay never pauses. */
    static final Map<String, String> IUPAY_HINTS = Map.of(
            "extra_verification", "now asks for a captcha; tuition shows the last bills known.",
            "bad_credentials", "didn't recognise your student ID; check it with `sla-agent status`.");
```

- `PART_NAMES` gets `"iupay", "tuition (IUPay)"` (6 pairs, still within `Map.of`);
- `SYSTEMS` becomes:

```java
    static final List<SystemParts> SYSTEMS = List.of(
            new SystemParts("EduSoft", List.of("timetable", "exams", "tuition")), // tuition: runs from before IUPay
            new SystemParts("IUPay", List.of("iupay")),
            new SystemParts("Blackboard", List.of("blackboard")),
            new SystemParts("Outlook", List.of("outlook")));
```

- in `systemLines`, the last `else` becomes:

```java
            } else {
                String hint = system.name().equals("IUPay") ? IUPAY_HINTS.get(code) : null;
                lines.add(new SystemLine(system.name(), "failed", hint != null ? hint
                        : FAILURE_HINTS.getOrDefault(code, "sync failed; it will be tried again automatically.")));
            }
```

- in `describe`, the choice of problem map becomes:

```java
                if (!failed.isEmpty() && failed.get(0).equals("blackboard")) {
                    problems = BLACKBOARD_PROBLEMS;
                } else if (!failed.isEmpty() && failed.get(0).equals("outlook")) {
                    problems = OUTLOOK_PROBLEMS;
                } else if (!failed.isEmpty() && failed.get(0).equals("iupay")) {
                    problems = IUPAY_PROBLEMS;
                }
```

- [ ] **Step 4: Run every Java test**

Run: `(cd web && ./mvnw -B test)`
Expected: `Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add -A web
git commit -m "feat(web): IUPay has its own line in the sync status, and its problems never read as EduSoft's

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: README, and a real sync the student checks (main session only)

**Files:**
- Modify: `README.md`, `docs/superpowers/specs/2026-09-30-iupay-tuition-design.md`

- [ ] **Step 1: Every test, one last time**

Run: `.venv/Scripts/python.exe -m pytest -q` → PASS. Run: `(cd web && ./mvnw -B test)` → `Failures: 0, Errors: 0`.

- [ ] **Step 2: README**

In `README.md`, line 5 becomes:

```markdown
- **School** (Vy): EduSoft timetable and exams, IUPay tuition bills, and Blackboard courses, synced automatically from a laptop, on one calendar.
```

and in the agent paragraph (line 65), "uploads only your timetable, exams, tuition and Blackboard courses" becomes "uploads only your timetable, exams, IUPay tuition bills and Blackboard courses". Add after that sentence: "IUPay needs only your student ID: the agent makes the same requests as IUPay's search page and keeps only the bills."

- [ ] **Step 3: Restart the site and sync for real**

Ask the student to stop and start the site (their `mvn spring-boot:run` terminal), so the two migrations run on their database; its log shows `Migrating schema ... to version "20260930.1.2 - drop school tuition"`. Then run the agent: `.venv/Scripts/sla-agent.exe sync-now`. Expected output: `Sync finished.` (or the known EduSoft/Blackboard notes); `agent.log` has `IUPay answered the bill request` and no lookup code.

- [ ] **Step 4: The student checks the pages**

Ask the student to open `http://localhost:5000/school/tuition` and `http://localhost:5000/school` (Ctrl+F5) and check:
- Tuition: "No tuition to pay", "Last checked on IUPay: …", and a Paid table with the 7 bills, newest (30/09/2026, 65,250,000, Đóng qua kênh EduBill) first;
- Overview: no tuition notice; under **Bills**, "Paid Wed 30/09: Thu Học Phí HK 1 (2026-2027): 65,250,000 VND · Đóng qua kênh EduBill"; an **IUPay** line in the status box.
Wait for their answer, and fix anything they report before going on.

- [ ] **Step 5: Mark the spec built and commit**

In the spec, change `**Status:**` to `Built (see docs/superpowers/plans/2026-09-30-iupay-tuition.md)`.

```bash
git add README.md docs/superpowers/specs/2026-09-30-iupay-tuition-design.md
git commit -m "docs: IUPay tuition bills in the README; the spec is built

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
