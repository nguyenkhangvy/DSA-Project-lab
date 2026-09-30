# School-Life-Assistant: tuition bills from IUPay

**Date:** 2026-09-30
**Scope:** read the student's tuition bills from IUPay instead of EduSoft, show them on the Tuition page, and tell the student about bills on the Overview page (a notice while something is unpaid, and a Bills list of new bills and payments)
**Owner:** Nguyen Khang Vy
**Status:** Built (see docs/superpowers/plans/2026-09-30-iupay-tuition.md); checked by the student on 2026-10-01
**Builds on:** [Java website](2026-09-26-java-website-design.md) and [EduSoft first phase](2026-09-25-edusoft-first-phase1-design.md). Everything there stays the same unless this document says otherwise.

---

## 1. Goal

IU publishes tuition bills on IUPay (`https://iupay.hcmiu.edu.vn/search/dhqt`), not in EduSoft. The student wants the app to find out by itself when a bill appears, show it on the Tuition page, and put a small notice on the Overview page so they don't miss it. IUPay's search needs only the student ID, no password.

### Decided with the student (2026-09-30)

- **IUPay replaces EduSoft** for tuition (option A). The agent stops reading EduSoft's tuition report.
- **The agent reads IUPay** as its own part of each sync (approach 1), next to EduSoft, Blackboard and Outlook. It needs only the student ID the agent already keeps, and runs even when EduSoft is paused.
- **Every bill is uploaded**, paid and unpaid, all semesters.
- **Tuition page:** bills still to pay on top with the amount, due date and "Pay on IUPay ↗"; otherwise "No tuition to pay"; then a table of paid bills.
- **Overview:** a small notice under the sync status while anything is unpaid, and its own **Bills** list of bills not yet paid and bills paid in the last 30 days. Bills do **not** go into "What changed".
- **The student's real IUPay reply** (7 paid bills, 2026-09-30) is the test sample, anonymised.

### Not in scope

- Paying from the app (the button opens IUPay; payment happens there)
- Postgraduate students (IUPay school `ch`); only `dh` is read
- Reminders or notifications outside the app
- E-invoices (IUPay's "Hóa đơn" PDFs)
- A way for the website to read IUPay by itself when the laptop is off

---

## 2. How it works

```
Laptop (sla-agent, each sync: every 15 minutes, at logon, Sync now, import)
  EduSoft  ── timetable, exams                       (tuition no longer read)
  IUPay    ── captcha-public? ── secret/{student ID}/0 ── secretCode/{code}/bill
        │  keeps only the bill fields (§3.3); name, email, class and the code are dropped
        ▼
  upload: FinishRun { timetable, exams, iupay: { bills: [...] }, blackboard, outlook }
        ▼
Website
  school_tuition_bills ── replaced on each good IUPay read
     ├─ Tuition page: To pay / No tuition to pay / Paid
     ├─ Overview: notice while unpaid, Bills list (unpaid + paid in 30 days)
     └─ Sync status: its own IUPay line
```

---

## 3. The agent

### 3.1 IUPay's API

IUPay is a single-page app ("Edubills"); its data comes from `https://api.mybill.aqtech.vn/`. The organisation is `dhqt`, the undergraduate school `dh`. All requests are plain `GET`s without cookies, the same ones the IUPay page makes:

| Step | Request | Reply used |
|---|---|---|
| 1 | `api/organization/dhqt/school/dh/captcha-public` | `data.enabled` (false on 2026-09-30) |
| 2 | `api/organization/dhqt/school/dh/secret/{student ID}/0` | `data.secretCode`; or `data.success: false` with `data.message` |
| 3 | `api/organization/dhqt/school/dh/secretCode/{secretCode}/bill?limit=99999&offset=0` | `data.data.records[]` |

The last `0` in step 2 is the ID-card number, only used by schools that search by it. The secret code is a bcrypt-like string (the one seen had no `/`); it goes into the path unchanged, as the page sends it. It is passed to `log.protect` so it is masked in the log, and never saved.

A new `agent/sla_agent/iupay_client.py` makes the three requests through `guarded_http.guarded_request` (the agent's existing wrapper: 20-second timeout, https only, every redirect checked against the host `api.mybill.aqtech.vn`, network problems become `NetworkError`). A new `agent/sla_agent/parsers/iupay.py` turns step 3's JSON into the upload format.

### 3.2 Bill status

`trang_thai` is a number. IUPay's own page shows it as:

| Number | IUPay label | Upload `status` |
|---|---|---|
| 0 | Chưa đóng | `unpaid` |
| 1 | Đã đóng | `paid` |
| 2 | Đang thanh toán | `paying` |
| 3 | Đóng một phần | `partly_paid` |

Any other number is a `source_changed` failure (§5), so an unknown state is never shown as paid.

### 3.3 What is kept from each bill

| Upload field | IUPay field | Notes |
|---|---|---|
| `bill_no` | `so_phieu_bao` | e.g. `E0000014104`, `4073743`; unique per student |
| `term_code` | `hoc_ky` | e.g. `20261` |
| `term_name` | `hoc_ky_chu` | e.g. "Academic year 2026-2027 - Semester 1"; may be missing |
| `description` | `noi_dung` | `<br>` becomes a line break; other tags removed |
| `fee_type` | `ma_loai_thu.typeFeeName` | e.g. "Thu Học Phí"; may be missing |
| `amount` | `phai_thu` | VND, ≥ 0 |
| `discount` | `mien_giam` | VND, ≥ 0 |
| `fee` | `so_tien_phu_thu_tien_ich` | VND transaction fee, ≥ 0 |
| `status` | `trang_thai` | §3.2 |
| `due_date` | `date_line` | only when the status isn't `paid`; epoch ms → date in Asia/Ho_Chi_Minh |
| `paid_on` | `ngay_thu` | only when `paid`; epoch ms → date in Asia/Ho_Chi_Minh |
| `channel` | `kenh_thu` | only when `paid`; e.g. "Đóng qua kênh EduBill", "Đóng offline" |

The reply's `student` block (name, email, class, faculty) and every other bill field are dropped in memory. The amount still owed on a bill is `amount − discount`, the way IUPay's own page works out "Còn nợ" (`phai_thu - mien_giam`, checked in its bundle on 2026-09-30); IUPay does not say how much of a `partly_paid` bill is left.

### 3.4 The sync

- `EDUSOFT_SECTIONS` becomes `("timetable", "exams")`. `edusoft_client.py` loses its `tuition` read; `parsers/tuition.py`, its sample `tuition-report.json` and their tests are deleted.
- `sync.py` gets `_collect_iupay(state, iupay)`, run after EduSoft and before Blackboard, whatever EduSoft's result, and also when EduSoft is paused. It returns one section result like `_collect_blackboard`.
- IUPay never pauses anything: there is no password to lock out, so every sync tries again.
- `everything_paused(state)` stays false while the agent has a student ID, since IUPay can always run.
- `sla-agent import` no longer asks for the tuition report; it reads IUPay live like a scheduled run.
- The scheduled task's description becomes "sync EduSoft timetable and exams, IUPay tuition, Blackboard and Outlook".

### 3.5 The upload format (contract)

In `contract/sla_contract/schema.py`:

```python
BillStatus = Literal["unpaid", "paid", "paying", "partly_paid"]

class TuitionBill(_Strict):
    bill_no: Annotated[str, Field(min_length=1, max_length=40)]
    term_code: Code
    term_name: Name | None = None
    description: Annotated[str, Field(min_length=1, max_length=1000)]
    fee_type: Name | None = None
    amount: Annotated[int, Field(ge=0)]      # VND
    discount: Annotated[int, Field(ge=0)] = 0
    fee: Annotated[int, Field(ge=0)] = 0
    status: BillStatus
    due_date: date | None = None
    paid_on: date | None = None
    channel: Annotated[str, Field(max_length=100)] | None = None

class Iupay(_Strict):
    bills: Annotated[list[TuitionBill], Field(max_length=500)] = []  # bill_no unique

IupayResult = Annotated[SectionOk[Iupay] | SectionFailed, Field(discriminator="status")]
```

- `FinishRun` gets `iupay: IupayResult | None = None`; `SECTION_NAMES` becomes timetable, exams, iupay, blackboard, outlook.
- `FinishRun.tuition` **stays accepted** with its old shape so an agent that hasn't been updated isn't rejected, but the website ignores it. It is removed in a later change once every agent sends `iupay`.
- A validator rejects two bills with the same `bill_no`.
- `SCHEMA_VERSION` stays 1: the change only adds an optional part.
- `SyncContract.java` mirrors this (`TuitionBill`, `Iupay`, `FinishRun.iupay`).
- Samples: `contract/samples/finish-iupay.json` (anonymised real reply, 7 paid bills) and `finish-iupay-unpaid.json` (one unpaid, one paying, one partly paid); `finish-edusoft.json` drops `tuition`.

---

## 4. The website

### 4.1 Tables

Migration `V20260930_1_1__tuition_bills.sql` adds two tables:

```sql
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
    status VARCHAR(12) NOT NULL,      -- unpaid / paid / paying / partly_paid
    due_date DATE NULL,
    paid_on DATE NULL,
    channel VARCHAR(100) NULL,
    PRIMARY KEY (id),
    UNIQUE (user_id, bill_no),
    CONSTRAINT fk_school_tuition_bills_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- When IUPay was last read successfully; no row means never (like school_mail_status for Outlook).
CREATE TABLE school_tuition_status (
    user_id INT NOT NULL,
    checked_at DATETIME NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_school_tuition_status_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
```

A second migration, `V20260930_1_2__drop_school_tuition.sql`, drops `school_tuition` when the new Tuition page replaces the old one (the site checks its tables against its classes at start-up, so the table and `SchoolTuition` go together). Dropping it loses nothing the student needs: IUPay holds every bill, and the next sync fills the new table. `SchoolTuition` and `SchoolTuitionRepository` are replaced by `SchoolTuitionBill`, `SchoolTuitionStatus` and their repositories.

### 4.2 Saving a sync

In `Ingest.finishRun`:

- `iupay` arrived `ok`: delete the student's bills and save the new list, and set `school_tuition_status.checked_at` to now, in the run's transaction. An empty list is valid and means "no bills".
- `iupay` failed or missing: the bills stay as they were.
- `tuition` (old agents): ignored; nothing is saved from it.
- Nothing is added to `school_changes`: bills never enter "What changed". `Changes.tuition` and `TuitionInfo` are deleted.

### 4.3 The Tuition page

`/school/tuition`, top to bottom:

1. **To pay** (only when some bill isn't `paid`): one card per bill, earliest due date first. It shows the description, term name, `amount − discount` in VND, the due date ("overdue since …" in red when past), the status label (Unpaid / Payment in progress / Partly paid, check IUPay for the rest) and the bill number, with "Pay on IUPay ↗".
2. **No tuition to pay** (when every bill is `paid`, including no bills): "No tuition to pay", "Last checked on IUPay: Wed 30/09 11:14" (`school_tuition_status.checked_at` in Vietnam time), with "Pay on IUPay ↗".
3. **Paid**: a table of paid bills, newest `paid_on` first: term, description, amount, paid date, channel.
4. **Never read**: when there is no `school_tuition_status` row yet, "No tuition information yet. It appears after the next sync." replaces 1–3.

### 4.4 The Overview page

- **Notice**, right under the sync status box, only while some bill isn't `paid`: "💰 **Tuition to pay: 65,250,000 VND**, due 15/10/2026 · See tuition →". With several bills: their total and the earliest due date. A due date in the past makes it red: "overdue since 01/10/2026". No due date: the date part is left out.
- **Bills**, a heading in the Overview card next to To submit, Next exam and What changed. It lists every bill not yet paid, then bills with `paid_on` in the last 30 days (Vietnam time), newest first:
  - "**New:** Thu Học Phí HK 1 (2026-2027): 65,250,000 VND, due 15/10/2026 (unpaid)"
  - "**Paid 30/09:** Thu Học Phí HK 1 (2026-2027): 65,250,000 VND · Đóng qua kênh EduBill"
  - Empty: "No new bills or payments in the last 30 days."
  - A "See all →" link to the Tuition page.
- A bill that vanishes from IUPay simply leaves both lists.

### 4.5 Sync status

`SyncStatus` gets a separate **IUPay** system with the part `iupay`, between EduSoft and Blackboard. EduSoft's parts become timetable and exams. `PART_ORDER` becomes timetable, exams, iupay, blackboard, outlook. IUPay's problems:

| Error code | Line |
|---|---|
| `network` | "Sync failed: IUPay couldn't be reached". Tries again next sync. |
| `extra_verification` | "Sync failed: IUPay now asks for a captcha". Tuition shows the last bills known. |
| `bad_credentials` | "Sync failed: IUPay didn't recognise your student ID". Not paused. |
| `source_changed` | "Sync failed: IUPay's data format has changed". |

Old runs that recorded a `tuition` part still show it under EduSoft.

---

## 5. Errors

Only the IUPay part fails; the other parts still upload and old bills stay.

| What happened | Error code | Message sent |
|---|---|---|
| Timeout, connection error, HTTP 5xx | `network` | "IUPay couldn't be reached." |
| Step 1 says `enabled: true` | `extra_verification` | "IUPay now asks for a captcha; tuition can't sync." |
| Step 2 has `success: false` | `bad_credentials` | "IUPay didn't recognise the student ID: " + IUPay's message |
| JSON not as expected, missing fields, unknown `trang_thai`, negative amount | `source_changed` | what was wrong, without student data |

These are existing error codes; the contract's `ErrorCode` list doesn't change. None of them sets a pause.

---

## 6. Security and privacy

- Only bill fields leave the laptop (§3.3). The name, email, class and the secret code stay in memory and are thrown away.
- The agent's log records the IUPay part's status and bill count, never amounts or bill numbers; the secret code goes through `log.protect`, as passwords do.
- The test samples replace the student ID with `ITITIU00000` and remove the `student` block; amounts, bill numbers and dates stay because they identify no one.
- The website shows a student only their own bills (queries by `user_id`, as for every School table).

---

## 7. Testing

**Agent**
- `parsers/iupay.py` against the anonymised real reply: 7 bills, all `paid`, `paid_on` in Vietnam dates, channels kept, `due_date` empty.
- A made-up reply with statuses 0, 2 and 3: `due_date` set, `paid_on` and `channel` empty; `<br>` in `noi_dung` becomes a line break.
- Unknown `trang_thai`, a negative amount, missing `records`: `source_changed`.
- `iupay_client.py` with a fake HTTP layer: captcha enabled → `extra_verification`; `success: false` → `bad_credentials`; timeout → `network`; the secret code is masked in the log.
- `sync.py`: IUPay runs when EduSoft is paused and when EduSoft fails; an IUPay failure leaves the other parts `ok` and pauses nothing.
- `cli.py import` no longer asks for the tuition report.

**Contract**
- Both new samples validate; an old-style `tuition` part still validates; duplicate `bill_no`, a negative amount and an unknown status are rejected.
- The Java `SyncContractTest` reads the same samples.

**Website**
- Migrations: `school_tuition_bills` and `school_tuition_status` exist, `school_tuition` is gone (`MigrationTest`).
- `Ingest`: an `ok` part replaces the bills; a failed part keeps them; an empty list clears them; a `tuition` part is ignored and adds no change.
- Tuition page: To pay cards (overdue, partly paid, paying), No tuition to pay with the last-checked time, Paid table order, Never read.
- Overview: notice shown / total and earliest date / overdue / hidden when all paid; Bills list (unpaid + paid in 30 days, 31-day-old payment left out, empty text, See all link).
- `SyncStatus`: the IUPay line and its four problems; an old run's `tuition` part still shown.

**By hand**
- A real sync on the student's laptop; the Tuition page shows the 7 paid bills and "No tuition to pay"; the Overview shows no notice and "Paid 30/09 … 65,250,000 VND" under Bills.

---

## 8. Build order

1. Contract: `TuitionBill`, `Iupay`, `FinishRun.iupay`, samples, tests (Python and Java).
2. Agent: `parsers/iupay.py` with samples and tests.
3. Agent: `iupay_client.py`, `_collect_iupay` in `sync.py`, EduSoft tuition removed, `import` and the scheduled task updated.
4. Website: migration, `SchoolTuitionBill`, `Ingest`, `Changes.tuition` removed.
5. Website: Tuition page.
6. Website: Overview notice and Bills list.
7. Website: `SyncStatus` IUPay line.
8. README; manual check with a real sync.

---

## 9. Risks

- **IUPay turns the captcha on.** Tuition stops updating and the IUPay line says why; the student can still check IUPay by hand. The agent never tries to solve a captcha.
- **IUPay's API changes** (it is a third-party service, AQTech's EduBill). Strict parsing turns changes into `source_changed` instead of wrong amounts.
- **IUPay starts asking for an ID-card number.** Step 2's `0` works for `dh` today; if IUPay starts requiring an ID-card number, step 2 fails with `bad_credentials` and a new setting would be needed.
- **Status 2 ("paying") sticking** while a bank payment is pending: shown as "Payment in progress" until IUPay changes it.
