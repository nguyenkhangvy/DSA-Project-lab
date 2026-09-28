# School-Life-Assistant: Outlook mail in a Mailbox tab

**Date:** 2026-09-28
**Scope:** read the student's IU Inbox through classic Outlook on the laptop, sort every email into the agreed categories, show them in a new **Mailbox** tab in priority order, and let class-change emails change the timetable like Blackboard announcements do, without counting a change twice
**Owner:** Nguyen Khang Vy
**Status:** Built (see docs/superpowers/plans/2026-09-28-outlook-mailbox.md). The Mailbox page, "lose points" and event times were then changed by [a compact Mailbox, auto-Done, and events you can join](2026-09-28-mailbox-events-design.md); each section that changed says so.
**Builds on:** [EduSoft-first Phase 1](2026-09-25-edusoft-first-phase1-design.md), [Blackboard](2026-09-26-blackboard-design.md), [Class changes and "To submit"](2026-09-26-class-changes-and-to-submit-design.md) and [the Java website](2026-09-26-java-website-design.md). Everything there stays the same unless this document says otherwise.

---

## 1. Goal

IU students get many emails: lecturers' notices, school tasks with deadlines, invoices and scholarships, and a long stream of event invitations, some of which give training points (điểm rèn luyện). The important ones get lost. The School module should read the Inbox, sort each email, and show the important ones first. A lecturer's "class is online / cancelled / make-up" email should change the timetable, exactly like a Blackboard announcement.

### Decided with the student (2026-09-27 and 28)

- **8 categories**, at most **2 per email**: Class, Event, Training points, School task, Money, Your requests & account, System notice, Promotion.
- **Training points** is a category. Only emails that actually say "điểm rèn luyện" get it. "Được công nhận hoạt động theo Quy chế sinh viên" does not count.
- Mail appears only in a new **Mailbox** tab, not on Overview or elsewhere. Class-change emails also change the Timetable (and Overview's Today/Tomorrow, which shows the timetable).
- **Layout:** boxes in priority order: From lecturers → School tasks → Money → Events (training-point events first) → Everything else.
- **Lecturers** are: Blackboard announcements, this semester's timetable lecturers, and any IU person writing from their own address.
- **Past** events and school tasks fold into a closed "Past" list. An email with no readable date never goes to Past.
- **Done** and **Move to…** (change categories, mark as from a lecturer), both only inside the app.
- **Email text is never stored or uploaded.** The laptop reads each email, sorts it, and uploads only the results. Clicking a card opens the email in Outlook.
- **Syncing between devices stays** (the website may go online; the Devices tab stays). Mail syncs on the same schedule as EduSoft and Blackboard; the student will rework sync timing later.
- **Errors are shown on the page** in plain words, with what to do.
- **Access:** classic Outlook on the Windows laptop (section 2 explains why). Reading on the phone waits for IU IT's approval (section 12).
- **"Open in Outlook"** opens the exact email on the laptop through a small `sla-mail:` link type. Every card also links to Outlook on the web.

### Not in scope

- Replying, deleting, moving, flagging or marking mail as read; anything that changes the mailbox
- Folders other than the Inbox, attachments, AI summaries
- Reading mail on the phone or on a Mac (needs IU IT's approval, section 12)
- New entries in the Overview's "What changed" feed for mail
- Changing how often sync runs

---

## 2. What we found (checked 2026-09-28)

**Microsoft Graph is blocked for students.** The student registered the app "School-Life-Assistant" in IU's Microsoft tenant (client `e6890c56-d95e-474f-8037-4c25987d4882`, tenant `a7380202-eb54-415a-9b66-4d9806cfab42`) and a one-time sign-in asked only for `Mail.Read`. Microsoft answered **"Need admin approval"**: IU does not let students approve apps, even read-only ones. Only an IU admin can.

We will **not** get around this screen by borrowing a Microsoft app's client ID. That would sidestep IU's rule on purpose.

**Classic Outlook works.** A second one-time test on the student's laptop (Outlook for Microsoft 365, classic, cached Exchange mode, Windows Defender and McAfee active):

| Check | Result |
|---|---|
| Read the Inbox through Outlook's Windows programming interface (COM) | Works, **no security warning**. 401 emails in the Inbox, 55 since 1 August, all 55 read in 2.4 s (44 ms each). Outlook was started hidden and needed 1.2 s. |
| Sender addresses | All 55 found. 51 are plain addresses; 4 are Exchange-internal and convert to the real address with the property `PR_SENDER_SMTP_ADDRESS`. |
| Links in the plain text | Kept: 45 emails with links, most written as `text <https://…>`. |
| Thread ID (`ConversationID`) and message ID (`PR_INTERNET_MESSAGE_ID`) | Present on all 55 |
| Blackboard emails | 9 from `bb@hcmiu.edu.vn`. Lecturer announcements carry the lecturer's address in the sender name ("… - dvlong@hcmiu.edu.vn"); "Submission received" receipts have only "bb@hcmiu.edu.vn" as the name. |
| Teams "added you to a group" emails | Sent from the lecturer's own address (`dhtai@hcmiu.edu.vn`, `buithanhnga@mp.hcmiu.edu.vn`) |
| Lecturer address shapes | Initials + given name (`vmkhoa` for Vo Minh Khoa, `dhtai` for Do Huu Tai) or the full name joined (`buithanhnga` for Bui Thanh Nga); also on IU sub-domains (`@mp.hcmiu.edu.vn`) |
| The dorm invoice | Subject "[ M-Invoice ] TB: Xuất hóa đơn điện tử số …" (contains "hóa đơn") |
| Opening one exact email | `Namespace.GetItemFromID(entryId).Display()` opens it. Outlook's command line (`/select outlook:<id>`) only opens the main window. The `outlook:` link type is not registered on this laptop. |

EduSoft stores lecturer names shortened, e.g. `P.Q.Hùng`, `Đ.V.Long`, `N.T.Hà`. With dots and accents removed and in lower case they equal the address before the `@` (`pqhung@`, `dvlong@`).

---

## 3. How it works

```
Laptop (sla-agent, each sync, after EduSoft and Blackboard)
  classic Outlook ──COM, read only──► Inbox emails since the semester start
        │  sort, find dates, lose-points warnings and class changes (on the laptop)
        │  the email text stays in memory and is thrown away
        ▼
  upload: sender, subject, time, IDs, categories, dates, class changes   (no text)
        │  existing sync API, device key
        ▼
Website
  Mailbox tab (boxes, Done, Move to…)      Timetable / Overview (class changes)
```

---

## 4. The laptop agent

### 4.1 Setup

`sla-agent setup --outlook`:

1. Checks that classic Outlook is installed and has an account. If not: *"Classic Outlook isn't set up on this laptop. Open Outlook (classic), sign in with your IU account, wait until it says 'All folders are up to date', then run this again."*
2. Shows the account's address and asks the student to confirm it. The address and `outlook_enabled = true` go in `state.json` (not secret).
3. Registers the `sla-mail:` link type for this Windows user only, under `HKCU\Software\Classes\sla-mail` (no admin rights). Its command is `"<pythonw.exe>" -m sla_agent open-mail "%1"`.
4. Nothing is stored in Credential Manager: Outlook does its own sign-in and the agent never gets a Microsoft key.

`sla-agent forget` also removes the `sla-mail:` link type and turns Outlook off. `sla-agent status` shows whether Outlook is on and the last Outlook result.

### 4.2 Which emails, and reading them

- **Only the default Inbox** of the confirmed account, and only emails (Outlook item class 43), not meeting requests or delivery reports.
- **From the semester start**, taken from the EduSoft term code of the last timetable read (`YYYYS`): semester 1 → 1 August of `YYYY`; semester 2 → 1 January of `YYYY+1`; semester 3 (summer) → 1 June of `YYYY+1`. For `20261` this is **1 August 2026**. With no term code yet: the last 90 days.
- **Every sync reads all of them again.** About 50 ms per email, so 500 emails take under 30 seconds. This keeps it simple: deleted or moved emails disappear by themselves, and an improved rule re-sorts old mail at the next sync (no "re-read" button is needed).
- **Outlook is started hidden** if it isn't running, and the reader waits up to 30 seconds for it to connect (it needs about 5). The agent never closes Outlook: a hidden Outlook closes by itself when the agent lets go of it and no Outlook window is open (checked 2026-09-28), so a window the student opened meanwhile is never closed.
- **The time an email arrived** is read from `PR_MESSAGE_DELIVERY_TIME` (UTC). pywin32 labels Outlook's `ReceivedTime` as UTC although it is the laptop's local time, so that is only the fallback, read as local time.
- **Time limit:** the Outlook read runs in a background thread and is given up after **3 minutes** (for example when Outlook shows a security prompt nobody answers): the Outlook part fails with `outlook_blocked`, and the stuck call ends when the agent's process does.
- **Read only, in code:** the Outlook reader only reads item properties and, in `open-mail`, calls `Display()`. It never calls `Send`, `Delete`, `Move`, `Copy`, `Save`, or sets any property (including `UnRead`). A test scans the reader's source for these names.
- **Outlook's security warning is never answered by the agent.** If Outlook blocks the read, the Outlook part fails with a message (4.4).

### 4.3 Laptop context used for sorting

Kept in `state.json` (not secret) so sorting still works when EduSoft or Blackboard fails in a sync:

- `term_code` and `courses`: `[[course code, course name, lecturer]]` from the last timetable read
- `bb_courses`: `[[Blackboard course name, course code]]` from the last Blackboard read

### 4.4 What is uploaded

A new part `outlook` in the upload format (`contract/sla_contract/schema.py`, and its Java twin `SyncContract.java`). Unknown fields are refused, so no text field can be added by mistake.

```python
MailCategory = Literal["class", "event", "training_points", "school_task", "money",
                       "requests_account", "system_notice", "promotion"]

class MailClassChange(_Strict):
    course_code: Code
    kind: Literal["online", "cancelled", "makeup"]
    day: date                      # Vietnam date
    start: time | None = None      # Vietnam time, make-up classes only
    end: time | None = None
    room: Room | None = None

class MailItem(_Strict):
    key: str                       # SHA-256 (64 lower-case hex) of the internet message ID
    entry_id: str                  # Outlook's ID, upper-case hex, 2-512 characters: opens it on the laptop
    thread_id: str | None          # Outlook's ConversationID, up to 64 characters
    received_at: AwareDatetime
    sender_name: str = ""          # up to 255
    sender_address: str = ""       # up to 255, lower case
    subject: str = ""              # up to 500
    categories: list[MailCategory] = []   # 0-2, no repeats
    from_lecturer: bool = False
    dates: list[date] = []         # up to 30: every date found in the email, from the day it arrived on
    loses_points: bool = False
    sorted: bool = True            # False: the rules failed on this email
    blackboard_title: str | None = None   # set when the email is Blackboard's copy of an announcement
    class_changes: list[MailClassChange] = []   # up to 10

class Outlook(_Strict):
    since: date
    connected: bool                # False: Outlook was offline, newest mail may be missing
    emails: list[MailItem]         # up to 2,000
```

**Never uploaded:** the email text or HTML, attachments, To/CC lists, the Outlook account's other folders.

**Changed later:** `loses_points` was removed and each email gained `sessions`, the days and times its event takes place (mailbox-events design, 3.3).

### 4.5 Problems

New error codes in the upload format: `outlook_not_set_up`, `outlook_blocked`.

| What happened | Part result | On the page |
|---|---|---|
| Classic Outlook missing, no account, or the confirmed account is gone | failed, `outlook_not_set_up` | "Sync failed: classic Outlook isn't set up on your laptop." / "Open Outlook (classic), sign in with your IU account, then press Sync now." |
| Outlook refused the read, or didn't answer within 3 minutes | failed, `outlook_blocked` | "Sync failed: Outlook didn't let the agent read your mail." / "Outlook may be showing a security warning, or your antivirus may be off. The agent never clicks past it. Check Outlook, then press Sync now." |
| Outlook is offline or disconnected (`ExchangeConnectionMode`) | ok, `connected: false` | Yellow note: "Outlook was offline when your laptop last read it, so the newest mail may be missing. Open Outlook (classic) and check that you're signed in." |
| The rules fail on one email | that email has `sorted: false`, no categories | On its card: "Couldn't sort this email automatically. Use Move to…" |
| One email can't be read at all | skipped and counted in the log | nothing (the rest still upload) |
| Anything else | failed, `unknown` | "Sync failed" / "It will be tried again automatically." |

Outlook problems never pause EduSoft or Blackboard, and never pause Outlook itself: there is no password to lock out, so every sync simply tries again.

### 4.6 Opening an email

`sla-agent open-mail "sla-mail:<entry id>"`:

- Accepts only `sla-mail:` followed by 2 to 512 upper-case hex characters (one trailing `/`, which some browsers add, is ignored). Anything else shows a Windows message box: "This isn't a School-Life-Assistant email link." and does nothing.
- Opens the email in classic Outlook with `GetItemFromID(id).Display()`. If Outlook can't find it: "This email is no longer in your Outlook Inbox."
- The first time, Edge asks "Open sla-agent?"; the student can tick "Always allow".

---

## 5. Sorting rules (on the laptop)

All matching ignores letter case and Vietnamese accents ("KHẢO SÁT" matches "khao sat"). Words match as whole words. The word lists live in one Python file.

### 5.1 Who is a lecturer

An email is **from a lecturer** when any of these holds, unless it is an automatic Microsoft notice (below):

1. **Blackboard announcement:** the sender is `bb@hcmiu.edu.vn` and the sender name contains another `@hcmiu.edu.vn` address (e.g. "Đặng Văn Long - dvlong@hcmiu.edu.vn"). "Submission received" receipts, whose name is only "bb@hcmiu.edu.vn", don't count.
2. **Timetable lecturer:** the part before `@` equals a timetable lecturer's name with dots, spaces and accents removed, in lower case (`P.Q.Hùng` → `pqhung`).
3. **An IU person:** the address is at `hcmiu.edu.vn` or one of its sub-domains, except `student.hcmiu.edu.vn`, and the part before `@` is built from the sender's own name (accents removed, lower case). It is either the initials of every word but the last followed by the last word (`Vo Minh Khoa` → `vmkhoa`), or all words joined (`Bui Thanh Nga` → `buithanhnga`). Both word orders are tried, so `Hung Quoc Pham` also gives `pqhung`. Office accounts (`oss@`, `hoisinhvien@`, `iuyouth@`, `bb@`, `noreply.cis@` …) never match because their names aren't built this way.

Every rule needs an IU staff address (`hcmiu.edu.vn` or a sub-domain, not `student.hcmiu.edu.vn`). Rule 3 needs a name of at least two words, so a one-word office name never counts.

**Automatic Microsoft notices never count as lecturer mail:** Teams "added you to a group" emails (subject contains "Microsoft Teams" and one of "được thêm", "đã thêm", "added you") and anything from `sharepointonline.com` or `microsoft.com` addresses.

### 5.2 Categories

| Category | An email gets it when… |
|---|---|
| **Class** | it is from a lecturer; or from `bb@hcmiu.edu.vn`; or it is a Teams "added you to a group" notice |
| **School task** | the subject has: khảo sát, survey, tạm trú, cư trú, sinh hoạt công dân, bảo hiểm y tế, BHYT, bắt buộc |
| **Money** | the subject has: học bổng, scholarship, hóa đơn, invoice, học phí, tuition, thanh toán, payment, lệ phí |
| **Event** | the subject has: thư mời, workshop, talkshow, chuyên đề, hội thảo, seminar, webinar, cuộc thi, contest, casting, hội thao, khai mạc, bế mạc, ngày hội, tuần lễ, đăng ký tham gia, đăng ký tham dự |
| **Training points** | the subject **or the text** says "điểm rèn luyện" |
| **Your requests & account** | the subject starts with "[Ticket:"; or has password, mật khẩu |
| **System notice** | a Teams "added you to a group" notice; or the sender is at `sharepointonline.com` or `microsoft.com` |
| **Promotion** | the subject or the text has: ưu đãi, khuyến mãi, giảm giá, voucher, discount |

Most words are checked in the **subject only**, because texts mention them in passing. For example, the beFood promotion's text offers "học bổng 25.000.000 VNĐ" as a prize and must not become Money.

### 5.3 At most two

When more than two categories match, the two that come first in this order are kept: Class → School task → Money → Event → Training points → Your requests & account → System notice → Promotion. Example: the IELTS workshop matches Event, Training points and Promotion ("ưu đãi") and keeps **Event + Training points**. An email matching nothing has no category and goes to "Everything else".

### 5.4 Dates and "loses points"

- **Dates:** every date in the subject and text, found with the class-change reader's date formats (`29/09/2026`, `27/9`, `18-9-2026`, `ngày 18 tháng 9`, `September 24`, `24th September`, …). Dates inside links are ignored. A date without a year takes the year closest to the day the email arrived. Dates before the day it arrived are dropped. At most 30, sorted, no repeats.
- **`loses_points`** was removed by the mailbox-events design. The agent finds the times an event takes place instead (mailbox-events 3.2).

### 5.5 Class changes from email

Only emails **from a lecturer** (5.1) are read for class changes.

**Which course**, first rule that works:

1. **Blackboard copy:** the subject starts with a Blackboard course name from `bb_courses` followed by ": " (e.g. "Physics 4_S1_2026-27_G01: Link học online …") and that Blackboard course has a course code. The course is that code.
2. **The email names exactly one timetable course**, by code (`IT093IU`) or full name ("Web Application Development"), in the subject or text.
3. **The sender teaches exactly one timetable course** (rule 2 of 5.1; for a Blackboard announcement, the address in the sender name).
4. Otherwise the email changes nothing on the timetable. It still shows in Mailbox.

For every Blackboard announcement email (5.1 rule 1), `blackboard_title` is the subject after its first ": ", whichever rule found the course. The website uses it to skip copies (6.4).

**Reading the change** uses the same reader as Blackboard announcements (the class-changes design, section 2.3): the subject is the title, the email text is the text, and the time it arrived is the posting time. The results go into `class_changes`. The "online / cancelled only on a day the course has a class" rule is applied later by the website, as for Blackboard.

### 5.6 The Python date reader

The Python class-change reader removed with the Python website (`app/school/services/class_changes.py` at commit `471cac0^`, 178 lines) comes back in the agent as `agent/sla_agent/class_changes.py`. The Java `ClassChanges` stays for Blackboard announcements.

So the two can't drift apart, a shared file `contract/samples/class-changes/sentences.json` lists example announcements (title, text, posting time) with the changes they must give, each distinct change once. Both the Python and the Java tests check every example. The examples start with those in the class-changes design's tests. The file is in a sub-folder because every `*.json` directly in `contract/samples/` is read as an upload.

---

## 6. The website

### 6.1 Tables

One Flyway migration, `V<build date>_1_<n>__school_mail.sql` (School module = 1). The mailbox-events design (4.1) later dropped `loses_points` and added `school_mail_sessions`, `school_mail_joined`, `school_mail_settings` and `school_mail_choices.opened`.

- **`school_mail`**: `id`, `user_id`, `mail_key` VARCHAR(64), `entry_id` VARCHAR(512), `thread_id` VARCHAR(64) NULL, `received_at` DATETIME (UTC), `sender_name` VARCHAR(255), `sender_address` VARCHAR(255), `subject` VARCHAR(500), `categories` VARCHAR(100) (comma-separated), `from_lecturer`, `dates` VARCHAR(400) (comma-separated ISO dates), `loses_points`, `is_sorted`, `blackboard_title` VARCHAR(255) NULL. Unique (`user_id`, `mail_key`).
- **`school_mail_changes`**: `id`, `mail_id` (deleted with its email), `course_code`, `kind`, `change_day`, `start_time` NULL, `end_time` NULL, `room` NULL.
- **`school_mail_choices`**: `id`, `user_id`, `mail_key`, `done`, `categories` VARCHAR(100) NULL, `from_lecturer` NULL, `updated_at`. Unique (`user_id`, `mail_key`). The student's Done and Move to… choices.
- **`school_mail_status`**: `user_id` (key), `since`, `connected`, `synced_at` (UTC).

### 6.2 Saving

When a sync's `outlook` part arrives correctly, it **replaces** the user's `school_mail` and `school_mail_changes` rows (like Blackboard) and updates `school_mail_status`. Choices are kept. Choices whose `mail_key` is no longer in the upload are deleted. A failed `outlook` part changes nothing, so Mailbox keeps showing what it had. Mail never adds entries to "What changed".

### 6.3 The Mailbox tab

**Menu:** Overview · **Mailbox** · Timetable · Courses · Exams · Tuition · Devices. Page `/school/mailbox`.

This section describes the first Mailbox. The mailbox-events design (4.2–4.6) turned the cards into one row each, shows every card (no "Show all"), removed the "Open in Outlook" button and the lose-points tag, and added opening, auto-Done, event sessions and Join….

**Cards.** Emails are grouped into cards:

1. Emails with the same `thread_id` form one card ("3 messages").
2. Cards whose newest emails have the same sender address and the same subject after normalizing (lower case, only letters and digits kept, spaces collapsed), received within 30 days of each other, merge into one card ("sent 2×").

A card uses its **newest** email for sender, subject and link; its dates are all its emails' dates; `loses_points` if any email has it.

**Choices on a card:**

- **✓ Done** is saved for the card's newest email. A card is done when its newest email is done, so a new reply brings it back.
- **Move to…** is saved for every email in the card. The card uses the newest email that has a choice, so a new reply keeps the chosen categories.
- **Back to automatic** clears the categories and lecturer choice for every email in the card.

**Boxes**, in this order. A card goes in the first box it fits:

1. **From lecturers:** from a lecturer (the student's choice wins over the rules)
2. **School tasks:** has School task
3. **Money:** has Money
4. **Events:** has Event
5. **Everything else**

Done cards leave their box and go to a closed **"Done (n)"** list at the bottom of the page, newest first.

**Past, next date and order:**

- **Next date:** the earliest of the card's dates that is today or later (Vietnam time).
- **Past** (Events and School tasks only): the card has dates but none is today or later. Past cards go into a closed "Past events (n)" / "Past (n)" list inside their box, latest date first.
- **Events:** Training points first, then soonest next date, then cards without dates (newest first).
- **School tasks:** soonest next date, then cards without dates (newest first).
- **From lecturers, Money, Everything else:** newest first. "Everything else" shows the newest 10, then "Show all (n)".

**A card shows:** sender name; subject; for Events and School tasks the next date ("Next: 29/09"), otherwise the date received; category tags (Training points with a ★); "3 messages" or "sent 2×"; ⚠ "lose points if absent" when `loses_points`; "Couldn't sort this email automatically. Use Move to…" when not sorted. Buttons:

- **Open in Outlook**: `sla-mail:<entry_id>` (works on the laptop that has the agent)
- **Outlook on the web ↗**: `https://outlook.office.com/mail/` in a new tab (`rel="noopener noreferrer"`), for other devices
- **✓ Done** / **Undo**
- **Move to…**: a page with Category 1 (the 8 categories), Category 2 (optional, different from 1), "This is from a lecturer", **Save**, **Back to automatic**, **Cancel**

**Addresses** (all need login, POSTs need the CSRF token; a card key that isn't the user's gives 404):

- `GET /school/mailbox`
- `POST /school/mailbox/{key}/done`, `POST /school/mailbox/{key}/undone`
- `GET` and `POST /school/mailbox/{key}/edit`, `POST /school/mailbox/{key}/automatic`

`{key}` is the card's newest email's `mail_key`.

**Top of the page:**

- Last read: "Mail read from Outlook 28/09 07:02" (from `school_mail_status`)
- **Red box** when the newest sync's `outlook` part failed: the headline and what to do (4.5), with the time
- **Yellow note** when `connected` is false (4.5)
- **Not connected yet** (no `outlook` part ever received): "Outlook isn't connected yet. On your laptop, open Outlook (classic), sign in, then run `sla-agent setup --outlook`."
- An empty box shows "Nothing here."

### 6.4 Timetable and Overview

- Class changes from email join those from Blackboard announcements in `Schedule`. Both become one list of (course code, time posted, change, link) and go through the same newest-wins rule per course, day and slot (`class` for online/cancelled, `makeup` for make-ups).
- **Blackboard copies are skipped** when the Timetable is built: an email's changes are ignored when its `blackboard_title` equals (ignoring case and surrounding spaces) the title of a stored Blackboard announcement of a course with the same code. Doing this when the Timetable is built means the order the two arrive in doesn't matter.
- `ClassChange` and `Schedule.Item` carry a **link** instead of `bbCourseId`: the course page for Blackboard changes, `/school/mailbox#mail-<key>` for email changes. The calendar feed and Overview use it.
- Everything else about class changes (online only on a class day, make-up times and rooms, all-day notes, purple and grey) stays as in the class-changes design.

### 6.5 Sync status

`SyncStatus` gains an **Outlook** system with the part `outlook` and the problems in 4.5, so Overview's sync status shows an Outlook line like EduSoft and Blackboard.

---

## 7. Security and privacy

1. **Read only**, by design and in code (4.2). The only action on an email is opening it for the student to read.
2. **No Microsoft key.** Outlook does its own sign-in. The agent stores nothing new in Credential Manager.
3. **Email text never leaves the laptop.** It exists only in the agent's memory while sorting. It is never written to the upload, `state.json`, logs or the website. The upload format has no field for it and refuses unknown fields.
4. **What the website holds per email:** sender name and address, subject, time, IDs, categories, dates, flags and class changes. Subjects can be personal (e.g. "[Ticket: 12345] Trần Thị Mai – …"); they are shown only to their owner.
5. **The `sla-mail:` link type** only opens an email in Outlook. It accepts only hex IDs (4.6), so another website using the link can at most open one of the student's own emails on the student's own screen.
6. **Safe display:** everything shown with Thymeleaf's escaping. The `sla-mail:` link is built only from `entry_id`, which the upload format limits to hex. The web link is a fixed address.
7. **Every query is filtered by the logged-in user.** Done and Move to… only work on the user's own cards.

---

## 8. Testing

**Upload format (Python and Java):** a valid `outlook` part; refused: a text field, 3 categories, an unknown category, a non-hex `entry_id`, a bad `key`, too many emails. New shared samples in `contract/samples/` checked by both test suites.

**Agent: sorting (pure functions):**

- **The sample.** The student's Inbox since 1 August (55 emails, including the 41 pasted on 2026-09-27), exported with a new tool `agent/tools/anonymize_mail.py`. It keeps subject, sender and text, replaces the student's name, ID, address and ticket details, and replaces lecturer names and addresses with placeholders built the same way (`Tran Van An`, `tvan@hcmiu.edu.vn`). The student checks the file before it is committed. Expected results: the agreed table for the 41, and the rules' answers for the other 14, confirmed by the student.
- **Lecturers:** each rule of 5.1; `bb@` receipts; Teams notices from a lecturer's address; sub-domains; student addresses; office accounts.
- **Categories and order:** subject-only words (the beFood prize), the top-two order, no match.
- **Dates:** each format, dates in links, year guessing, dates before arrival, the 30 limit; `loses_points`.
- **Class changes:** the four course rules; not from a lecturer → none.
- **Shared sentences:** every example in `class-changes/sentences.json`.

**Agent: Outlook (with a fake Outlook, no real Outlook needed):**

- Reads only the Inbox, only mail items, only from the semester start; Exchange addresses converted.
- Outlook not installed, no account, account gone → `outlook_not_set_up`; access refused and the 3-minute limit → `outlook_blocked`; offline → `connected: false`.
- One unreadable email is skipped; one email the rules fail on gets `sorted: false`.
- Outlook is closed afterwards only when the agent started it and no window is open.
- **No text leaves:** a fake email with a unique sentence in its text; that sentence appears nowhere in the upload JSON, `state.json` or the log file.
- The reader's source never names `Send`, `Delete`, `Move`, `Copy`, `Save` or `UnRead`.
- `setup --outlook` registers the link type; `forget` removes it; `open-mail` refuses anything but a hex `sla-mail:` link and opens a known ID.

**Website:**

- Saving replaces mail and keeps choices; stale choices deleted; a failed part changes nothing; no "What changed" entries.
- Mailbox: boxes and their order; grouping by thread and "sent 2×"; Past and next date around midnight Vietnam time; Done, Undo, reply brings a done card back; Move to… and Back to automatic; the "Everything else" limit; error box, yellow note, not connected, empty boxes; another user's card gives 404; CSRF required.
- Timetable: an email's online / cancelled / make-up changes; the Blackboard copy skipped whichever arrives first; a newer email overriding a Blackboard change; links to the course page or the Mailbox card; another user's mail never changes my timetable.
- Sync status: the Outlook line for each problem.

**Browser check (Edge):** Mailbox at 1400×1000 and 390×844; `sla-mail:` opens the email on the laptop. **Real sync** with the student's Outlook.

---

## 9. Build order

1. Upload format: the `outlook` part and error codes (Python and Java), samples.
2. Agent: the Python date reader restored, with `class-changes/sentences.json` checked by both test suites.
3. Agent: `anonymize_mail.py` and the sample file (the student checks it).
4. Agent: sorting rules (5.1–5.5).
5. Agent: the Outlook reader, `setup --outlook`, the link type, `open-mail`, `forget`, sync and problems.
6. Website: tables, saving, sync status.
7. Website: the Mailbox tab.
8. Website: class changes from email in the Timetable and Overview.
9. Browser check and a real sync.

---

## 10. Dependencies

- Agent: `pywin32` (Windows only; the agent already needs Windows for Credential Manager and Task Scheduler). The scheduled task already runs in the student's own Windows session (`InteractiveToken`), which COM needs.
- Classic Outlook for Microsoft 365, included in IU students' Microsoft 365. "New Outlook" and Outlook on the web can't be read this way.

---

## 11. Risks

- **Rules miss or misplace an email.** Move to… fixes it for good, and a better rule applies to all mail at the next sync.
- **Outlook's security warning** appears when antivirus is off or out of date. The agent never answers it; the Outlook part fails after 3 minutes with a message.
- **IU turns off programmatic access to Outlook** (a group policy). The Outlook part then always fails with `outlook_blocked`, and the student sees why.
- **Microsoft retires classic Outlook.** It is supported for years, but when it goes, reading needs Graph (section 12).
- **An offline Outlook** gives old mail. The yellow note says so.
- **Term code guessing for semesters 2 and 3** (`YYYY2` → 1 January, `YYYY3` → 1 June) is not yet checked against a real term code; only `20261` has been seen.
- **`sla-mail:` works only on the laptop with the agent.** Other devices use "Outlook on the web" and the subject to find the email.

---

## 12. Later: the phone

If IU IT grants admin consent for the app's `Mail.Read`, the agent can read through Microsoft Graph instead (sign-in in the browser, key in Credential Manager), and Graph's `webLink` gives an exact link that works on the phone. Sorting, the upload format (with a web link added) and the Mailbox tab stay the same. That is a separate design.
