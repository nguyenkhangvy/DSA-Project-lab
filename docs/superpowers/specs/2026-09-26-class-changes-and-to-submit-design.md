# School-Life-Assistant: class changes from announcements, and "To submit"

**Date:** 2026-09-26
**Scope:** (1) show online, cancelled and make-up classes in the timetable when a Blackboard announcement says so; (2) list the assignments still to submit on the Overview, with a direct link to each
**Owner:** Nguyen Khang Vy
**Status:** Built (see docs/superpowers/plans/2026-09-26-class-changes-and-to-submit.md)
**Builds on:** [Blackboard design](2026-09-26-blackboard-design.md) and [EduSoft-first Phase 1 design](2026-09-25-edusoft-first-phase1-design.md). Everything there stays the same unless this document says otherwise.

---

## 1. Goal

Lecturers announce class changes on Blackboard, but the timetable still shows the class as normal. Real announcements from this semester (checked 2026-09-26):

| Course | Announcement title | Meaning |
|---|---|---|
| Probability (MA026IU) | ONLINE CLASS ON SEPTEMBER 24 | Thu 24/09 13:15 class is online (MS Teams) |
| Web Application Development (IT093IU) | Online Class Notification – Web Application – 22 September 2026 | Tue 22/09 08:00 class is online |
| Physics 4 (PH012IU) | Link học online sáng thứ Sáu, 18/9/2026, 8g00-9g40 | Fri 18/09 08:00 class is online |
| Probability (MA026IU) | Cancel class on September 17 | Thu 17/09 class is cancelled ("the makeup schedule will be announced later") |

Each date matches a real class of that course in the EduSoft timetable.

The Overview's "Due soon" box also lists assignments the student has already submitted, and its links open only the course's home page.

### Decided with the student

- Purple means **any class an announcement changed**: online classes and make-up classes. The legend says "Online / make-up".
- A **cancelled** class stays in its slot, grey and crossed out.
- The Overview lists **only assignments still to submit**, with a direct link to each.
- Detection is done by **pattern reading in the web app** (free, no data sent anywhere). AI reading was rejected (cost, and it would send announcements to another company). Marking classes by hand may come later as a way to correct mistakes.

### Not in scope

- Ranges and repeating changes ("online from 12 Oct", "every Saturday from week 7")
- Correcting a detected change by hand
- Changing anything on Blackboard or EduSoft

---

## 2. Class changes from announcements

### 2.1 Where it runs

In the web app, as a pure function over data already stored: the Blackboard announcements and the EduSoft class meetings. No database change. One small laptop-agent change: announcement text keeps one line per paragraph, list item or line break, so paragraphs stay separate sentences. It runs whenever the calendar feed or the Overview is built, so it always reflects the newest announcements and timetable.

New file `app/school/services/class_changes.py`. `schedule.py`'s `Item` gains two fields, `change` (`None` / `"online"` / `"cancelled"` / `"makeup"`) and `link` (the app's page for the announcement's course). `items_between` applies the changes, so the calendar and the Overview's Today/Tomorrow show the same thing.

### 2.2 Which announcements apply to which classes

- An announcement belongs to its Blackboard course's `course_code`. Announcements from a lecture course and its lab courses (same code) all count.
- It applies to the timetable courses with the same `course_code`.

### 2.3 Reading an announcement

1. **Sentences.** The title is one sentence. The text is split into sentences at `.`, `!`, `?` followed by a space, and at line breaks.
2. **Change words** (any letter case):
   - online: `online`, `trực tuyến`
   - cancelled: `cancel`, `cancels`, `canceled`, `cancelled`, `canceling`, `cancelling`, `cancellation(s)`, `no class(es)`, `nghỉ`, `hủy`, `huỷ`
   - make-up: `make-up`, `makeup`, `make up`, `bù` (as in `học bù`, `dạy bù`)
   
   Change words and class words count only as whole words.
3. **Dates** in the same sentence:
   - `September 24`, `Sep 24`, `Sept. 24`, `September 24th`, `24 September`, `24th September`, each with an optional year (`, 2026` or ` 2026`)
   - `18/9`, `18/09`, `18/9/2026`, and `18-9-2026` (a dash only with the year, so hour and period ranges like `8-10` or `tiết 10-12` are not dates): always day/month (Vietnamese order)
   - `ngày 18 tháng 9`, optionally `năm 2026`
   - A date without a year gets the year that puts it closest to the posting date.
   - Dates before the posting day (Vietnam time) are ignored; they are about the past.
4. **The sentence must also mention a class:** `class(es)`, `lecture(s)`, `session(s)`, `lesson(s)`, `lớp`, `buổi`, `tiết`, and `học`/`dạy` only in `học online`, `học trực tuyến`, `học bù`, `dạy bù`, `nghỉ học`. ("Submit your report online by 24/9" changes nothing; "classmates" or "classroom" doesn't count.) ("… cho môn học" doesn't count.)
5. **Each date takes the nearest cancel or make-up word in its sentence** (by distance in characters). Online words decide only when the sentence has neither, so "Make-up class online on 3/10" is an online make-up class. A sentence without a change word, or without a date, changes nothing.
6. **Times** (only used for make-up classes), in the same sentence: `8:00`, `08:00`, `8:00 AM`, `1:15 PM`, `13h15`, `8h`, `8g00`, `8g`; a range joined by `-`, `–`, `to` or `đến` gives the end time. A make-up's time and room come from the part of the sentence after its date (up to the next date), or else from the part before it. In a range, a start without AM/PM takes the end's AM/PM when that keeps it before the end (`1:15 to 3:45 PM` is 13:15–15:45).

### 2.4 What a change does

- **online** on a date where the course has a class: that class becomes online. Its room shows "Online".
- **cancelled** on a date where the course has a class: that class becomes cancelled.
- **online / cancelled on a date without a class of that course:** nothing (the date was about something else).
- **make-up:** an extra class of that course on that date.
  - With a start time: it starts then. It ends at the end time if one is given, otherwise after the course's usual class length (its most common meeting length, or 90 minutes if it has none). No make-up is added on a day the course has a normal class (such a date names the original class, as in "the make-up for the class on 24/9").
  - Without a time: a note in the calendar's all-day row, "Make-up class: <course> (time not given, see announcement)", or "… see email" when a lecturer's email announced it; the note links to it.
  - The room is "Online" if the sentence also has an online word; otherwise the room is taken from the sentence if it looks like an IU room (`A2.401`, `LA1.605`, `R109`), else empty.
- **Two announcements about the same course and date:** the newest one wins, separately for class changes (online, cancelled) and make-ups, so a later make-up notice doesn't erase a cancellation.
- One announcement that can't be read is skipped (logged); it never breaks a page.

### 2.5 What the student sees

- **Timetable legend:** Class (blue), Exam (red), Deadline (orange), **Online / make-up (purple)**, **Cancelled (grey, crossed out)**.
- **Calendar entries:**
  - online: purple, "Online: Probability, Statistic & Random Process", room line "Online"
  - make-up: purple, "Make-up: …"
  - cancelled: grey, crossed out, "Cancelled: …"
  - Clicking a purple or grey entry opens the app's page for the course that posted the announcement, where the announcement (with its Teams code or link) is shown.
- **All-day row:** its label changes from "Due" to "All day", since it can now hold make-up notes as well as deadlines (deadlines keep their "Due 23:59: …" titles).
- **Overview, Today / Tomorrow:** the same labels: a purple "Online" or "Make-up" tag, or the class crossed out with "Cancelled".
- Colours follow the always-light theme: purple `#6f42c1` with white text; cancelled uses the page's grey text on a light grey background.

---

## 3. "To submit" and direct assignment links

### 3.1 Direct links (laptop agent)

A Blackboard gradebook column for an assignment or test carries `contentId`, the ID of the item in the course. Blackboard's own link for that item is:

`https://blackboard.hcmiu.edu.vn/webapps/blackboard/execute/displayIndividualContent?course_id=<course id>&content_id=<contentId>`

(seen in the API's `links` for the item, e.g. `course_id=_35337_1&content_id=_452089_1`). The agent uses it as the assignment's `url` when `contentId` looks like a Blackboard ID (`_<digits>_<digits>`). Columns without one (grade columns a teacher typed in, such as "Midterm") keep the course link. Links refresh at the next sync. No contract or database change: `url` already exists and must already start with `https://blackboard.hcmiu.edu.vn/`.

### 3.2 The Overview's "To submit" box

It replaces "Due soon".

- **Listed:** assignments with a due date and status `not_graded` (Blackboard shows no attempt), due from 7 days ago onwards, soonest first. Those already past their due time get a red **Overdue** tag.
- **Left out:** submitted (`needs_grading`), graded and exempt items; items with no due date (usually hand-typed columns like "Attendance"); items overdue by more than 7 days.
- **Each row:** due time in Vietnam time, name, course, and **"Open assignment ↗"** (new tab, `rel="noopener noreferrer"`).
- **Empty box:** "Nothing left to submit."
- A submitted item leaves the box at the next sync, not instantly.

### 3.3 Calendar

Every deadline still shows. Those done (submitted, graded or exempt) get a check mark: "✓ Due 23:59: Exercise 1 · …".

---

## 4. Security and privacy

- Nothing new is sent anywhere. Detection runs in the web app on data it already has.
- Announcement text keeps being shown with Jinja autoescaping; calendar text keeps going in with `textContent`.
- Links built by the agent must still start with `https://blackboard.hcmiu.edu.vn/` (the contract checks it).
- Every query stays filtered by the logged-in user.

---

## 5. Testing

- **Reader (pure functions):** the 4 real announcements above (codes and links removed) give exactly the listed changes. Cases that must change nothing:
  - "Logistics Reminder" (dates next to "in-person", a range "from weeks commencing on 12 Oct")
  - "The makeup schedule will be announced later" (no date)
  - "Submit your report online by 24/9" (no class word)
  - dates inside links (Teams and Meet links are full of digits and dashes)
  - a deadline date in the same text as "online" when the course has no class that day
  - a date before the posting day
  - Also: each date format, Vietnamese words, year guessing across New Year, nearest-word choice in a sentence with two changes, make-up with and without a time, the newest announcement winning.
- **Calendar feed and Overview:** purple and grey entries with the right titles, rooms and links; another user's announcements never change my timetable; make-up without a time goes to the all-day row.
- **Agent:** `contentId` gives the direct link; a missing or odd `contentId` gives the course link.
- **Overview "To submit":** unsubmitted items with links; overdue within 7 days tagged; submitted, graded, exempt, undated and older overdue items hidden; empty text.
- **Calendar:** ✓ for done deadlines.
- **Browser check (Edge):** timetable and Overview at 1400×1000 and 390×844, device in dark and light mode.

---

## 6. Build order

1. Agent: direct assignment links.
2. Overview "To submit" box and calendar ✓.
3. Announcement reader (pure functions) with the real-text tests.
4. Apply changes in `schedule.items_between`; calendar feed, Overview Today/Tomorrow, legend, CSS, all-day label.
5. Browser check and a real sync.

---

## 7. Risks

- **Wording the reader doesn't know** means a change is missed, and the class shows as normal. Every change is linked to its announcement, so the student can check.
- **A wrong detection:** a date and a change word in one sentence that mean something else could mark a class. The same-sentence rule (2.3) and the class-must-exist rule for online/cancelled (2.4) keep this rare; the entry links to the announcement that caused it.
- **Make-up times written as periods** ("tiết 7-9") aren't read in this version; such a make-up becomes an all-day note.
