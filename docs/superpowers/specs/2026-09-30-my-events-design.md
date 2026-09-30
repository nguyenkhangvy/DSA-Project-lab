# School-Life-Assistant: your own events on the Timetable, with repeats and conflict checks

**Date:** 2026-09-30
**Scope:** the student creates, edits, skips days of and deletes their own events on the Timetable. An event happens once or repeats every N days, every N weeks on chosen weekdays, or every N months, between a first and a last day, at a fixed time. The app shows whether each occurrence clashes with the timetable.
**Owner:** Nguyen Khang Vy
**Status:** Approved 2026-09-30; plan: docs/superpowers/plans/2026-09-30-my-events.md
**Builds on:** [Java website](2026-09-26-java-website-design.md) and [Mailbox events](2026-09-28-mailbox-events-design.md) (its `Conflicts` and the joined events in `Schedule`). Everything there stays the same unless this document says otherwise.

---

## 1. Goal

The Timetable shows classes, exams and the events joined from Mailbox, but the student can't add their own plans, such as evening self-study. They want to add them by hand, often as a repeating block ("Tự học buổi tối", every Mon, Tue, Wed from date X to date Y, 17:00–19:00), change or delete them later, and see whether they clash with the timetable.

### Decided with the student (2026-09-30)

- **Repeat options** (question 1: C plus every N days): once, every N days, every N weeks on chosen weekdays, every N months. Every repeat has a first and a last day.
- **Changing a series** (question 2: A): Save changes the whole series. Single days can be skipped and un-skipped. There is no per-occurrence edit and no "this and following".
- **Conflicts** (question 3: A): a "Check for conflicts" button on the form lists each clashing occurrence. Saving is always allowed. Clashing occurrences carry ⚠ on the calendar.
- **Storage** (approach 1): the rule is stored; occurrences are worked out when needed.

### Not in scope

- All-day events and events that go past midnight
- Editing one occurrence (its own time or title), or "this and following"
- Reminders or notifications
- Sharing events, exporting them (.ics), or sending them anywhere outside this app
- Moving classes, exams or joined events; only the student's own events carry conflict marks

---

## 2. How it works

```
Timetable page ── "+ New event" ──▶ Event form (/school/events/new, /school/events/{id}/edit)
     ▲                                   │ Check for conflicts ─▶ same form + list of clashes (nothing saved)
     │                                   │ Save ─▶ school_my_events (+ skips kept) ─▶ Timetable + "3 of 33 clash" note
     │                                   │ Skip this day / Undo ─▶ school_my_event_skips
     │
Schedule.itemsBetween ── classes, exams, joined events, + "mine" items from Occurrences
     ├─ Timetable calendar (/school/api/calendar): ⚠ and red border on clashing "mine" items, link to the edit page
     ├─ Overview Today / Tomorrow
     └─ "My events" list under the calendar: ⚠ N clashes / ✓ No conflict
```

---

## 3. The data

### 3.1 Tables

Migration `V20260930_1_3__my_events.sql`:

```sql
CREATE TABLE school_my_events (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    title VARCHAR(100) NOT NULL,
    place VARCHAR(100) NULL,
    notes VARCHAR(500) NULL,
    first_day DATE NOT NULL,
    last_day DATE NOT NULL,          -- = first_day for "once"
    start_time TIME NOT NULL,        -- Vietnam wall-clock time
    end_time TIME NOT NULL,          -- after start_time, same day
    repeat_kind VARCHAR(6) NOT NULL, -- once / days / weeks / months
    every_n INT NOT NULL,            -- 1..99; 1 for "once"
    weekdays VARCHAR(20) NULL,       -- "weeks" only: ISO day numbers, e.g. "1,2,3" (Mon, Tue, Wed)
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_school_my_events_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE school_my_event_skips (
    id INT NOT NULL AUTO_INCREMENT,
    event_id INT NOT NULL,
    skip_day DATE NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (event_id, skip_day),
    CONSTRAINT fk_school_my_event_skips_event FOREIGN KEY (event_id) REFERENCES school_my_events (id) ON DELETE CASCADE
);
```

The old `school_events` table (made by the Python site, unused by the Java site) is left untouched.

### 3.2 Which days an event happens on

`Occurrences` is a pure class: `days(rule, from, to)` gives the event's days in [from, to], in order, without the skipped days.

| Repeat | Days |
|---|---|
| once | `first_day` |
| every N days | `first_day`, `first_day + N`, `first_day + 2N`, … up to `last_day` |
| every N weeks on weekdays | Weeks run Monday–Sunday and are counted from the week holding `first_day` (week 0). In weeks 0, N, 2N, …, each chosen weekday that falls in [`first_day`, `last_day`]. |
| every N months | The day of the month of `first_day`, in `first_day`'s month and every N months after, up to `last_day`. A month without that day (a 31st in April, 29–31 in February) is skipped. |

Then every skipped day is removed. Each day becomes an occurrence from `start_time` to `end_time` on that day, in Vietnam time (stored UTC times are made with `VietnamTime.utc`, as for joined events).

### 3.3 Limits

- `last_day` is on or after `first_day`, and at most 366 days after it. A series has at most 367 occurrences (every day for a year).
- `every_n` is 1–99. For "weeks", at least one weekday.
- `end_time` is after `start_time`.
- Settings that give no days at all can't be saved: "These settings give no days." Only a weekly rule can give none (the other rules always include the first day), e.g. weekly on Mon with first day Tue 06/10 and last day Sat 10/10.

---

## 4. The pages

### 4.1 The event form

`GET /school/events/new` and `GET /school/events/{id}/edit` show the same form, laid out like Join…. `EventForm` holds the typed text (as `JoinForm` does), and `EventForm.check()` returns the mistakes by field.

- **Title** (required, ≤100), **Place** (≤100), **Notes** (≤500)
- **Start time**, **End time** (`<input type="time">`, required)
- **Repeat**: Once / Every [N] days / Every [N] weeks on ☐Mon … ☐Sun / Every [N] months ("Months without this day are skipped.")
- **First day** (`<input type="date">`, required; on "new" it is today), **Last day** (hidden for Once)
- On "new", the weekday of the first day is ticked by default.

Buttons (each a POST with the CSRF token):

| Button | Does |
|---|---|
| **Check for conflicts** | Checks the form. With mistakes: shows them. Otherwise it shows the form again with the conflicts of every occurrence (§5.2): each clashing occurrence on a line, or "✓ No conflict in 33 sessions". Nothing is saved. |
| **Save** | Checks the form. With mistakes: shows them, nothing saved. Otherwise saves (new: insert; edit: update the row, keep the skipped days that are still days of the new rule, drop the others) and goes to `/school/timetable` with a flash: "Saved "Tự học buổi tối". 3 of 33 sessions clash: Mon 05/10, Tue 06/10, Wed 07/10." (at most 5 days listed, then "…"), or "Saved "…". No conflict." |
| **Delete** (edit only) | Asks "Delete this event and all its days?" in the browser, then deletes the event and its skips and goes to `/school/timetable` with a flash "Deleted "…"." |

Mistake messages show under their field, and the form keeps what was typed:
- "Enter a title." / "At most 100 characters." (title, place) / "At most 500 characters." (notes)
- "Enter a time." / "The end must be after the start."
- "Enter a day." / "The last day can't be before the first day." / "The last day can be at most a year after the first day."
- "Tick at least one day." / "Enter a number from 1 to 99."
- "These settings give no days."

### 4.2 Skipping a day

`GET /school/events/{id}/edit?day=2026-10-05`, when 05/10 is one of the event's days (skipped or not), shows above the form:
- "Skip Mon 05/10 only" (POST `/school/events/{id}/skip` with `day`), or, if it is already skipped, "Mon 05/10 is skipped · Undo".

The edit page always lists the skipped days, each with **Undo** (POST `/school/events/{id}/unskip` with `day`). A day that isn't one of the event's days is refused (400). Skip and Undo return to the edit page with a flash.

### 4.3 The Timetable page

- A **"+ New event"** button above the calendar.
- Below the calendar, **"My events"**, soonest first day first, one line each: title · repeat in words ("Once", "Every day", "Every 3 days", "Every week on Mon, Tue, Wed", "Every 2 weeks on Sat", "Every month on the 15th") · "05/10–20/12" (or the one day) · "17:00–19:00" · "⚠ 3 clashes" / "✓ No conflict" / "No days left" · **Edit**.
- Empty: "No events of your own yet."

### 4.4 The calendar, Overview and feed

- `Schedule.itemsBetween` also returns the student's own events' occurrences in the range, as `Item`s of kind **`mine`**, label "My event", title the event's title, room its place, source the edit page for that day (`/school/events/{id}/edit?day=…`).
- `/school/api/calendar` gives them the class name `item-mine` (purple) and, when that occurrence clashes, a "⚠ " before the title and the class `item-conflict` (red border).
- The week and day views show 07:00–23:00. A "Show more" button in the toolbar adds 23:00–07:00 (the whole day, in a scrolling box that opens at 07:00) and becomes "Show less"; this browser remembers the choice. While the night is hidden, the button counts the events it hides: "Show more (1 hidden)". Events can be at any time (the student's choice, 2026-10-01). "All day" and the other settings stay as they are.
- Overview Today and Tomorrow list them like joined events. The legend under the Timetable gets "My event".
- Mailbox's Join conflict marks (Mailbox events §4.5) now also count the student's own events, since they are part of the timetable.

---

## 5. Conflicts

### 5.1 What an occurrence is checked against

Everything else on the timetable at that time, as `Schedule.itemsBetween` gives it for the occurrence's day:
- classes (make-up classes included; cancelled classes and make-ups without a time left out), exams (without a length: 90 minutes, as Mailbox does), joined events;
- the student's other events' occurrences, **never the same event's**.

The check is `Conflicts.of` (Mailbox events §4.5): a clash is an overlap; one ending as the other starts is not.

### 5.2 How it's shown

- Form, after Check: one line per clashing occurrence, "Mon 05/10 17:00–19:00 ⚠ IT093IU Web Application (17:15–19:45)"; several clashes on one day are joined with " · ".
- Calendar: the clashing occurrence has "⚠ " before its title and a red border.
- My events list: the number of clashing occurrences in the whole series.
- The flash after Save (§4.1).
- A new code path computes these in one place, `MyEventConflicts`, which takes an event's occurrences and the busy list and returns the clashing occurrences with what they clash with. The calendar, the list, the form and the flash all use it.

---

## 6. Security and privacy

- Every query filters by the logged-in user. Another user's event, or an unknown id, is 404 on every page and POST.
- Every POST carries the CSRF token (403 without).
- Title, place and notes are shown with `th:text` and, in the calendar, with `textContent`, never as HTML.
- Own events stay in this app: they are never uploaded and the agent never sees them.

---

## 7. Testing

- **`Occurrences`** (unit): once; every 1 and every 3 days; weekly on Mon/Tue/Wed (the example: Mon 05/10 to Sun 20/12/2026 gives 33 days); every 2 weeks counted from the first day's week, when the first day is a Thursday; every month on the 31st (skips April, June…, February); every 2 months; the range asked for cuts the series at both ends; skipped days removed; 367 days maximum.
- **`EventForm.check()`** (unit): every message in §4.1; "these settings give no days".
- **`MyEventConflicts`** (unit): a class, an exam without a length, a joined event, another own event, the same event never, back-to-back not a clash, a cancelled class not a clash.
- **Pages** (MockMvc): create, edit, delete; Check lists clashes and saves nothing; Save's flash; skipped days kept or dropped on edit; skip and undo; a day that isn't an event day is 400; another user's event 404 on GET and every POST; POST without CSRF 403; My events list lines and repeat words; Overview Today shows an own event; the calendar feed has `mine` items with ⚠, `item-conflict` and the edit link; the Join page counts an own event as a conflict.
- **Migration**: both tables exist (`MigrationTest`); `SchoolTablesTest` saves and reloads an event with skips.

---

## 8. Build order

1. Migration, `SchoolMyEvent`, `SchoolMyEventSkip`, repositories.
2. `Occurrences` (pure).
3. `EventForm` and its check.
4. `Schedule` adds `mine` items; calendar feed, legend and 07:00–23:00; Overview.
5. `MyEventConflicts`; ⚠ in the feed.
6. The event form pages: new, edit, Check, Save, Delete.
7. Skip and Undo.
8. The My events list on the Timetable page.
9. README; the student tries it in the browser.

---

## 9. Risks

- **A long daily series makes the Timetable page work harder**: at most 367 occurrences per event, and conflicts are checked per page view. With a handful of events this is quick. If it ever gets slow, the list's clash counts can be cached per event.
- **Classes change after an event is saved** (a new timetable, a make-up class): the ⚠ marks follow, because conflicts are worked out on every view, not stored.
- **Time zones**: all event times are Vietnam wall-clock times, as the joined events are, so they never shift with the laptop's or server's time zone.
