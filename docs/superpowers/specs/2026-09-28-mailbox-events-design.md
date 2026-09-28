# School-Life-Assistant: a compact Mailbox, auto-Done, and events you can join

**Date:** 2026-09-28
**Scope:** show Mailbox as compact rows with the category first, mark opened emails Done by themselves (a setting turns this off), drop "lose points", let the agent find events' times, mark each time Conflict / No conflict against the timetable, and put the events the student joins into the Timetable
**Owner:** Nguyen Khang Vy
**Status:** Design, not built
**Builds on:** [Outlook mail in a Mailbox tab](2026-09-28-outlook-mailbox-design.md) (built on the branch `outlook-mailbox`, Tasks 1–9 of its plan). Everything there stays the same unless this document says otherwise. Section numbers like "Outlook §6.3" point into that spec.

---

## 1. Goal

The first Mailbox showed each email as a tall card (about 160 px), so a screen held three emails. The student wants to see all mail at a glance with the category first, open an email and have it leave the list, and decide which events to go to: an event's times should say whether they clash with classes or exams, and an event the student joins should appear in the Timetable.

### Decided with the student (2026-09-28)

- **Rows, grouped:** one row per email, the priority boxes kept (Outlook §6.3), every email shown (no "Show all"), counts in the box titles. Mockup `mailbox-rows.html` accepted as the base.
- **A bigger ✓ Done** button, easy to hit.
- **Auto-Done on by default:** opening an email from Mailbox marks it Done. A setting turns it off (manual Done).
- **Upcoming events and school tasks stay** when opened: they only turn grey, and fold into Past when their date passes.
- **"Lose points" is removed** everywhere (page, upload format, agent, database).
- **Event times come from the agent** (approach A): it finds each event's sessions on the laptop and uploads only day, start and end; the website checks conflicts when it shows the page.
- **Sessions are picked:** an event with several sessions shows a mark per session, and Join lets the student tick the sessions they will attend.
- **Join is offered for every event or school-task email**, not only training-point events.
- **An email the student moves to Event or School task** gets its found times suggested on the Join page, so the agent finds sessions in every email (2026-09-29).
- **No timetable means No conflict:** a session is checked against whatever the timetable holds; with nothing there the time is free (2026-09-29).
- **Place is typed by the student** on the Join page; the agent never uploads a venue (it is email text).

### Not in scope

- A phone calendar subscription (.ics); joined events appear in the app's Timetable only
- Reminders or notifications before an event
- Registering for an event with its organiser (Join only changes this app)
- Reading the venue, the organiser or any other text from the email
- Conflict checks for anything but Events and School-tasks emails

---

## 2. How it works

```
Laptop (sla-agent, each sync)
  Inbox email ── sort (Outlook §5) ── find sessions in every email: day, start, end
        │  the text stays in memory and is thrown away
        ▼
  upload: … categories, dates, sessions, class changes   (no text, no loses_points)
        ▼
Website
  Mailbox rows ── each upcoming session marked against Schedule (classes, exams, joined events)
     │ open (subject / Web ↗) ── opened, and Done when auto-Done is on
     │ Join… ── the student's joined sessions (kept across syncs)
     ▼
  Timetable, Overview Today/Tomorrow, /school/api/calendar ── joined sessions as "event" items
```

---

## 3. The agent: sessions

### 3.1 Which emails

**Every email** is searched for sessions, whatever its categories, because the student can move any email to Event or School task (Move to…, Outlook §6.3) and its times should then be ready on the Join page. The website only shows and uses sessions of event-like cards (4.2).

### 3.2 Finding sessions

The subject and the text are read together, line by line. A **session** is a day and a start time, with an end time when the email gives one. Days and times are Vietnam time.

**Times** (a start alone, or a start and an end joined by `-`, `–`, `—`, `đến`, `to`, `until`):

- `13:00`, `13.00`, `13h`, `13h00`, `13h30`, `13g`, `13g00`, `8g30`
- `1:00 PM`, `1 PM`, `1:30 pm`, `9 AM` (AM/PM written after the time, or once after the end: `1:00 – 2:30 PM`)
- a start alone after `lúc`, `vào lúc`, `at`, `from`, `từ`

A number without `:`, `.`, `h`, `g` or AM/PM is not a time. Hours run 0–23, minutes 0–59.

**Days:** the date formats the class-change reader already knows (Outlook §5.4: `29/09/2026`, `27/9`, `18-9-2026`, `ngày 18 tháng 9`, `September 24`, `24th September` …), with the same year guess. Dates inside links are ignored.

**Pairing a time with its day:**

1. A time goes with the dates in the **same line**. If the line has none, it takes the dates of the **nearest line above** that has dates (e.g. "Ngày: 01/10/2026" then "Thời gian: 13h00 – 16h00").
2. **Several dates, one time** on a line: one session per date ("ngày 29/09 và 01/10, 13:00–14:00" → 2 sessions).
3. **Several times, one date** on a line: one session per time ("Ca 1: 8:00–10:00; Ca 2: 13:00–15:00 ngày 30/9" → 2 sessions on 30/9).
4. A line with several dates and several times pairs them in order when the counts are equal; otherwise every date gets every time.
5. **Deadlines are not sessions:** a time or date on a line with `hạn chót`, `hạn đăng ký`, `hạn nộp`, `thời hạn`, `trước ngày`, `đăng ký trước`, `deadline`, `due` is skipped for sessions (the date still counts in `dates`). Bare `hạn` and `trước` don't count: "Số lượng có hạn" and "có mặt trước 15 phút" sit next to real event times.
6. A time with no date anywhere above it is dropped.

**Then:** sessions on days before the day the email arrived are dropped; repeats (same day and start) are kept once, with the end from the first; the sessions are sorted by day and start; at most **10** are kept.

The finder lives next to the class-change reader (`agent/sla_agent/class_changes.py`, which already reads times for make-up classes) and is pure. `sort_email` calls it; when it fails on one email, that email is uploaded with `sessions: []` and the failure is logged without any email text (as Outlook §4.5 for sorting).

### 3.3 The upload format

In `contract/sla_contract/schema.py` and its Java twin `SyncContract.java`:

```python
class MailSession(_Strict):
    day: date                   # Vietnam date
    start: time                 # Vietnam time
    end: time | None = None     # after start when given

class MailItem(_Strict):
    ...                         # as in Outlook §4.4, except:
    sessions: list[MailSession] = []     # up to 10
    # loses_points is removed: an upload that still sends it is refused (unknown field)
```

`MailSession` refuses an `end` that is not after `start`. The shared samples (`contract/samples/finish-outlook.json` and the invalid ones) change with it; a new invalid sample sends `loses_points`, and one sends 11 sessions.

---

## 4. The website

### 4.1 Tables

One Flyway migration, `V20260928_1_2__mail_events.sql`:

- **`school_mail`**: drop `loses_points`.
- **`school_mail_sessions`** (new): `id`, `mail_id` (deleted with its email), `day` DATE, `start_time` TIME, `end_time` TIME NULL. Replaced with the mail at every sync (Outlook §6.2).
- **`school_mail_joined`** (new): `id`, `user_id`, `mail_key` VARCHAR(64), `day`, `start_time`, `end_time` NULL, `title` VARCHAR(500), `place` VARCHAR(100) NULL, `training_points` BOOLEAN, `by_hand` BOOLEAN, `created_at`. Unique (`user_id`, `mail_key`, `day`, `start_time`). **Not** touched by a sync: joined sessions stay even when their email is gone.
- **`school_mail_choices`**: add `opened` BOOLEAN NOT NULL DEFAULT FALSE.
- **`school_mail_settings`** (new): `user_id` (key), `auto_done` BOOLEAN NOT NULL DEFAULT TRUE. No row means auto-Done is on.

Saving an `outlook` part stores each email's sessions. Stale choices are still deleted (Outlook §6.2); a choice with only `opened` counts as a choice.

### 4.2 The Mailbox page

Layout as in the mockup `mailbox-rows.html` (kept in `.superpowers/brainstorm/`, not committed). An **event-like card** is one whose categories (the student's Move to… choice wins) include Event or School task; only event-like cards show sessions and marks and get Join… and the auto-Done exception. Sessions of other cards are stored but not shown.

- **Width:** the Mailbox page is up to 1200 px wide (the site's other pages stay 960 px).
- **Box titles** show counts: "From lecturers (8)". Every card of a box is shown; "Everything else" has no limit and no "Show all". Past and Done stay folded (`<details>`).
- **One row per card**, on wide screens left to right:
  1. **Category labels** in a fixed-width column, each category its own soft color and short name: Class, Task, Money, Event, ★ Points, Requests, System, Promo. A card with no category shows a grey "Other"; a card the rules couldn't sort shows an orange "Not sorted" label whose hover text is "Couldn't sort this email automatically. Use Move to…".
  2. **Sender** (bold, one line, cut with …).
  3. **Subject**: one line, cut with …, the full subject as hover text. It is the `sla-mail:<entry_id>` link that opens the email in Outlook on the laptop. An **opened** card's subject is grey.
  4. "3 messages" / "sent 2×" as a small grey tag.
  5. **When**: "Next: Thu 01/10" for cards in the Events and School tasks boxes with an upcoming date or session (as Outlook §6.3), otherwise the time received.
  6. **Actions**: **Web ↗** (Outlook on the web, new tab, `rel="noopener noreferrer"`), **Move…**, **Join…** (event-like cards only), and **✓ Done** (Undo in the Done list) as a real button at the end, at least 32 px tall with a visible border.
- **Sessions line:** a card with upcoming sessions gets one small extra line under its row listing them, e.g. `Tue 29/09 13:00–14:00 ⚠ Conflict: Web Application · Thu 01/10 13:00–14:00 ✓ No conflict`. A joined session shows **Joined** (green) instead of its mark. A session without an end shows "from 14:00".
- **Phone (under 700 px):** each row wraps to two lines: labels and extra tags, then when (first line); sender · subject, then actions (second line). The sessions line wraps below.
- **Removed:** the "Open in Outlook" button (the subject is the link), the "⚠ lose points if absent" tag, and the "Couldn't sort…" sentence on the card (now the label's hover text).

### 4.3 Opening, auto-Done and the setting

- **The setting:** a tick box at the top of Mailbox, "Mark emails as done when I open them", on by default. Changing it saves it at once (the script submits its form); without the script a **Save** button shows. `POST /school/mailbox/settings`.
- **Opening:** a click on a card's subject or **Web ↗** runs `static/js/mailbox.js`, which sends `POST /school/mailbox/{key}/opened` with the CSRF token. The link opens as usual without waiting; the answer (`{"done": true|false}`) only updates the row. Without the script, nothing is recorded.
- **The site records the card as opened** (`opened` on the choice of the card's newest email, like Done in Outlook §6.3, so a new reply makes the card unread again). Then, when auto-Done is on, it also marks the card **Done**, **except** an event-like card that has a date or session today or later (not Past). Those stay in their box, grey, and fold into Past by themselves.
- **On the page:** the script greys the row's subject at once; a card that became Done shows "Done ✓" and **Undo** in place. It moves to the Done list the next time the page loads.
- Opening never changes anything in Outlook.

### 4.4 Past and next date

For a card with sessions: the **next date** is the day of its first session that has not ended (Vietnam time); the card is **Past** when every session has ended. A card without sessions keeps the rules of Outlook §6.3 (its `dates`). A session without an end ends one hour after its start.

### 4.5 Conflicts

For each upcoming session of an event-like card:

- The **timetable of that Vietnam day** is `Schedule.itemsOn(userId, day)` as the Timetable page shows it, plus the student's **other** joined sessions (not this email's).
- A session **conflicts** with an item when their times overlap: `start < itemEnd && itemStart < end`. Back-to-back (one ends at 14:00, the other starts at 14:00) is not a conflict.
  - Classes count, including online classes and make-ups; **cancelled** classes don't.
  - Exams count; an exam without a length lasts 90 minutes.
  - Items without a time (a make-up class announced without a time) don't count.
  - A session without an end lasts one hour.
- The mark is **⚠ Conflict: <first item's name>** (the course name, the exam label and course, or the event's title), with "+ n" when more items clash, or **✓ No conflict**.
- A day with nothing on the timetable, or a student with **no timetable at all**, gives **✓ No conflict**: the time is free, and the student can join.
- Marks are worked out when the page is shown, so a later make-up class or cancellation changes them by itself.

### 4.6 Join

**Join…** on an event-like card opens `GET /school/mailbox/{key}/join`:

- The card's subject and sender.
- **Each upcoming session** the agent found, with its mark (4.5) and a tick box, ticked when already joined. For a card the student moved to Event or School task themselves (the rules gave it neither), the same sessions are listed under **"Found in this email"**, unticked, as suggestions; with none found, only "Add a session" is offered.
- The student's **other joined sessions** of this email (added by hand, or no longer in the email), ticked, labelled "added by you".
- **Add a session:** day, start, end (optional). One per save.
- **Place** (optional, up to 100 characters), shown for every joined session of this email.
- **Save** (`POST …/join`), **Leave event** (`POST …/leave`: removes all of this email's joined sessions), **Cancel**.

Saving replaces this email's joined sessions with the ticked ones plus the added one, copying the card's subject as `title` and whether its categories (the student's choice from Move to… wins) include Training points. Refused with a message: a day before today, an end not after its start, more than 10 joined sessions for one email. Joining does not mark the card Done.

**Addresses** (login, CSRF on POSTs, a key that isn't one of the user's cards gives 404): `GET`/`POST /school/mailbox/{key}/join`, `POST /school/mailbox/{key}/leave`, `POST /school/mailbox/{key}/opened`, `POST /school/mailbox/settings`.

### 4.7 Timetable, Overview and the calendar feed

- `Schedule` adds the student's joined sessions as `Item`s of kind **`event`**: `label` "Event", or "★ Training points" when `training_points`; `title` the saved title; `room` the place; `source` `Source.email(mailKey)` (link `/school/mailbox#mail-<key>`, "See email"). A session without an end lasts one hour.
- So they appear in the Timetable page (its feed `/school/api/calendar`, CSS class `event-event`, green), and in Overview's Today and Tomorrow.
- Overlapping items stay side by side as today; nothing is hidden or moved.
- Class changes never apply to `event` items.
- Joined sessions are only ever read for the logged-in user.

---

## 5. Security and privacy

1. **Still no email text leaves the laptop.** Sessions are dates and times only; the place is typed by the student on the website. `MailSession` refuses unknown fields like every other part.
2. **Opening records nothing in Outlook** and needs the CSRF token; another site can't mark the student's mail as opened.
3. **Every query is filtered by the logged-in user**: sessions, joined sessions, settings and choices.
4. **Safe display:** the title and place are shown with Thymeleaf's escaping; the place is limited to 100 characters.

---

## 6. Testing

**Upload format (Python and Java):** a valid email with sessions; refused: `loses_points`, 11 sessions, an end not after its start, a bad time.

**Agent: sessions (pure):** each time format; AM/PM once after the end; `lúc` / `từ` starts; not-a-time numbers; a time taking the date from the line above; several dates → several sessions; several times → several sessions; equal counts paired in order; deadline lines skipped (and bare `hạn` / `trước` not); a time with no date dropped; days before arrival dropped; repeats once; the 10 limit; sessions found in an email of any category; the shared examples; a failure gives `sessions: []` and logs no text; the text never in the upload.

**Website:**

- Saving: sessions stored and replaced; joined sessions untouched by a sync, also when their email is gone; stale choices; the migration on H2 and MySQL.
- Mailbox: rows and labels ("Other", "Not sorted" with its hover text); counts; no "Show all"; the Done button; no "lose points".
- Opening: auto-Done on → Done, except upcoming Events and School tasks; auto-Done off → only opened; a new reply is unread again; the setting saved and read; another user's key 404; CSRF required.
- Past and next date from sessions, around midnight Vietnam time.
- Conflicts: class overlap; online counts; cancelled doesn't; make-up counts; exam with and without a length; another joined event; back-to-back isn't; no end = one hour; an empty day and no timetable at all → No conflict; "+ n".
- Join: save ticked sessions; a card moved to Event lists its found sessions as unticked suggestions; a card of another category shows no sessions; add by hand; place; leave; refused inputs; training points copied (Move to… wins); another user's card 404; CSRF.
- Timetable, Overview and `/school/api/calendar`: joined sessions as green `event` items with their link; another user's joined sessions never shown.

**Browser check (Edge):** Mailbox and Join at 1400×1000 and 390×844; the auto-Done click; the Timetable with a joined event. **Real sync** with the student's Outlook, then the student's own check.

---

## 7. Build order

On the branch `outlook-mailbox`, after Tasks 1–9 of the Outlook plan:

1. Upload format: `sessions`, no `loses_points` (Python and Java, samples).
2. Agent: the session finder for every email and `sort_email` (no `loses_points`).
3. Website: the migration, entities, saving.
4. Website: the Mailbox rows, the Done button, opening, auto-Done and the setting.
5. Website: conflicts and Join.
6. Website: joined sessions in `Schedule`, the Timetable and Overview.
7. Every test (H2 and MySQL) and the browser check.
8. A real sync and the student's check (replaces the paused Outlook Task 10, Step 5).
9. The anonymized samples of the student's Inbox (Outlook Task 11), now also pinning each email's sessions.
10. README and both specs (Outlook Task 12, plus this one's status).

---

## 8. Changes to the Outlook spec

When this is built, the Outlook spec is updated so the two agree: §4.4 (`sessions`, no `loses_points`), §5.4 (no "loses points"), §6.1 (the new tables), §6.3 (rows, no "Show all", no "Open in Outlook" button, no lose-points tag, opening and auto-Done, Past from sessions), §8 (tests).

---

## 9. Risks

- **The agent misreads or misses a time.** The student sees the sessions on the card and can add or fix one on the Join page.
- **A deadline read as a session** when its line lacks the deadline words. The student simply doesn't tick it.
- **Auto-Done hides an email the student opened by mistake.** Undo is in the Done list.
- **The script is blocked or fails.** Opening then records nothing; ✓ Done still works.
- **A joined session's email changes its time.** The joined copy keeps the old time; the Join page shows the new session unticked next to the old one, so the student can switch.
