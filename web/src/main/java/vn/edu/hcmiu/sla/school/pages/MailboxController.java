package vn.edu.hcmiu.sla.school.pages;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import vn.edu.hcmiu.sla.auth.AppUser;
import vn.edu.hcmiu.sla.core.Flash;
import vn.edu.hcmiu.sla.school.VietnamTime;
import vn.edu.hcmiu.sla.school.mail.MailSessions;
import vn.edu.hcmiu.sla.school.mail.MailSessions.Line;
import vn.edu.hcmiu.sla.school.mail.Mailbox;
import vn.edu.hcmiu.sla.school.mail.Mailbox.Card;
import vn.edu.hcmiu.sla.school.mail.Mailbox.Session;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoice;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoiceRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailJoined;
import vn.edu.hcmiu.sla.school.model.SchoolMailJoinedRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailSessionRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailSettings;
import vn.edu.hcmiu.sla.school.model.SchoolMailSettingsRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailStatusRepository;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRunRepository;
import vn.edu.hcmiu.sla.school.pages.SyncStatus.RunInfo;

/**
 * The Mailbox tab: the student's emails in priority boxes, with Done, Move to…, opening (which marks a card
 * opened, and Done when auto-Done is on) and Join… for events. They only change the app, never the real mailbox.
 * A card of another user is 404.
 */
@Controller
@RequestMapping("/school/mailbox")
public class MailboxController {

    static final String CATEGORY_ERROR = "Choose a first category, and a different second one or none.";
    static final String PAST_DAY = "Choose a day that isn't over yet.";
    static final String NO_START = "Give the new session a start time.";
    static final String END_BEFORE_START = "The end must be after the start.";
    static final String TOO_MANY = "One email can have at most 10 joined sessions.";
    static final String PLACE_TOO_LONG = "The place can be at most 100 characters.";
    static final int MAX_JOINED = 10;
    static final int MAX_PLACE = 100;

    private final Clock clock;
    private final SchoolMailRepository mails;
    private final SchoolMailChoiceRepository choices;
    private final SchoolMailSessionRepository sessions;
    private final SchoolMailSettingsRepository settings;
    private final SchoolMailJoinedRepository joined;
    private final MailSessions mailSessions;
    private final SchoolMailStatusRepository statuses;
    private final SchoolSyncRunRepository runs;

    public MailboxController(Clock clock, SchoolMailRepository mails, SchoolMailChoiceRepository choices,
            SchoolMailSessionRepository sessions, SchoolMailSettingsRepository settings,
            SchoolMailJoinedRepository joined, MailSessions mailSessions, SchoolMailStatusRepository statuses,
            SchoolSyncRunRepository runs) {
        this.clock = clock;
        this.mails = mails;
        this.choices = choices;
        this.sessions = sessions;
        this.settings = settings;
        this.joined = joined;
        this.mailSessions = mailSessions;
        this.statuses = statuses;
        this.runs = runs;
    }

    /** Now in UTC, as stored. */
    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** Now in Vietnam (wall clock), as sessions are written. */
    private LocalDateTime nowInVietnam() {
        return VietnamTime.of(now()).toLocalDateTime();
    }

    private Mailbox.View view(Integer userId) {
        Map<String, SchoolMailChoice> byKey = choices.findByUserId(userId).stream()
                .collect(Collectors.toMap(SchoolMailChoice::getMailKey, Function.identity()));
        Map<String, List<Session>> sessionsByKey = sessions.findOfUser(userId).stream()
                .collect(Collectors.groupingBy(s -> s.getMail().getMailKey(),
                        Collectors.mapping(s -> new Session(s.getDay(), s.getStart(), s.getEnd()), Collectors.toList())));
        return Mailbox.build(mails.findByUserIdOrderByReceivedAtDescIdDesc(userId), byKey, sessionsByKey,
                joined.mailKeysOf(userId), nowInVietnam());
    }

    /** The user's card whose newest email has this key, else 404. */
    private Card card(Integer userId, String key) {
        Card card = view(userId).card(key);
        if (card == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return card;
    }

    /** The user's card with this key that Join… is open to (Card.joinable), else 404. */
    private Card joinableCard(Integer userId, String key) {
        Card card = card(userId, key);
        if (!card.joinable()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return card;
    }

    private SchoolMailChoice choice(Integer userId, String key) {
        return choices.findByUserIdAndMailKey(userId, key).orElseGet(() -> new SchoolMailChoice(userId, key, now()));
    }

    /**
     * The tab. Only a browser on Windows can be on the laptop whose sla-mail: link opens the email, so only there
     * does a click on the subject count as opening it; elsewhere "Web ↗" does.
     */
    @GetMapping
    String mailbox(@AuthenticationPrincipal AppUser user,
            @RequestHeader(value = HttpHeaders.USER_AGENT, defaultValue = "") String browser, Model model) {
        Mailbox.View view = view(user.id());
        model.addAttribute("onWindows", browser.contains("Windows"));
        model.addAttribute("view", view);
        model.addAttribute("lines", mailSessions.lines(user.id(),
                view.boxes().stream().flatMap(b -> b.cards().stream()).toList(), nowInVietnam()));
        model.addAttribute("status", statuses.findById(user.id()).orElse(null));
        model.addAttribute("problem", SyncStatus.mailProblem(
                runs.recentRuns(user.id()).stream().map(RunInfo::of).toList()));
        model.addAttribute("labels", Mailbox.LABELS);
        model.addAttribute("autoDone", settings.autoDone(user.id()));
        model.addAttribute("version", runs.version(user.id()));
        model.addAttribute("gone", mailSessions.gone(user.id(), view.keys(), nowInVietnam()));
        return "school/mailbox";
    }

    /**
     * The student opened this card's email from its subject or "Web ↗" (mailbox.js). It is marked opened, and
     * Done too when auto-Done is on, unless it is an event or school task still ahead. Answers {"done": …}.
     */
    @PostMapping("/{key}/opened")
    @ResponseBody
    Map<String, Boolean> opened(@AuthenticationPrincipal AppUser user, @PathVariable String key) {
        Card card = card(user.id(), key);
        SchoolMailChoice choice = choice(user.id(), card.key());
        choice.open(now());
        if (settings.autoDone(user.id()) && card.doneWhenOpened()) {
            choice.setDone(true, now());
        }
        choices.save(choice);
        return Map.of("done", choice.isDone());
    }

    @PostMapping("/settings")
    String saveSettings(@AuthenticationPrincipal AppUser user, @RequestParam(defaultValue = "false") boolean autoDone) {
        SchoolMailSettings mine = settings.findById(user.id()).orElseGet(() -> new SchoolMailSettings(user.id(), true));
        mine.setAutoDone(autoDone);
        settings.save(mine);
        return "redirect:/school/mailbox";
    }

    @PostMapping("/{key}/done")
    String done(@AuthenticationPrincipal AppUser user, @PathVariable String key) {
        return setDone(user.id(), key, true);
    }

    @PostMapping("/{key}/undone")
    String undone(@AuthenticationPrincipal AppUser user, @PathVariable String key) {
        return setDone(user.id(), key, false);
    }

    private String setDone(Integer userId, String key, boolean done) {
        Card card = card(userId, key);
        SchoolMailChoice choice = choice(userId, card.key());
        choice.setDone(done, now());
        choices.save(choice);
        return "redirect:/school/mailbox#mail-" + card.key();
    }

    @GetMapping("/{key}/edit")
    String edit(@AuthenticationPrincipal AppUser user, @PathVariable String key, Model model) {
        Card card = card(user.id(), key);
        return editPage(model, card, new MailForm(card.categories(), card.fromLecturer()), null);
    }

    private String editPage(Model model, Card card, MailForm form, String error) {
        model.addAttribute("card", card);
        model.addAttribute("form", form);
        model.addAttribute("error", error);
        model.addAttribute("categories", Mailbox.CATEGORIES);
        return "school/mailbox-edit";
    }

    @PostMapping("/{key}/edit")
    String move(@AuthenticationPrincipal AppUser user, @PathVariable String key, @ModelAttribute("form") MailForm form,
            Model model, RedirectAttributes redirect) {
        Card card = card(user.id(), key);
        if (!Mailbox.validCategories(form.categories())) {
            return editPage(model, card, form, CATEGORY_ERROR);
        }
        for (String mailKey : card.keys()) {
            SchoolMailChoice choice = choice(user.id(), mailKey);
            choice.move(form.categories(), form.isFromLecturer(), now());
            choices.save(choice);
        }
        Flash.success(redirect, "Moved. The app will keep this email where you put it.");
        return "redirect:/school/mailbox#mail-" + card.key();
    }

    @GetMapping("/{key}/join")
    String join(@AuthenticationPrincipal AppUser user, @PathVariable String key, Model model) {
        Card card = joinableCard(user.id(), key);
        List<Line> lines = mailSessions.lines(user.id(), card, nowInVietnam());
        String place = mailSessions.joinedOf(user.id(), card.keys()).stream().map(SchoolMailJoined::getPlace)
                .filter(Objects::nonNull).findFirst().orElse("");
        return joinPage(model, card, lines, new JoinForm(lines.stream().filter(Line::joined).map(Line::id).toList(),
                place), null);
    }

    private String joinPage(Model model, Card card, List<Line> lines, JoinForm form, String error) {
        model.addAttribute("card", card);
        model.addAttribute("lines", lines);
        model.addAttribute("anyJoined", lines.stream().anyMatch(Line::joined));
        model.addAttribute("form", form);
        model.addAttribute("error", error);
        return "school/mailbox-join";
    }

    /**
     * Saves the ticked sessions and the one added by hand in place of the card's joined sessions that haven't
     * ended; sessions already over stay in the Timetable.
     */
    @PostMapping("/{key}/join")
    String saveJoin(@AuthenticationPrincipal AppUser user, @PathVariable String key,
            @ModelAttribute("form") JoinForm form, Model model, RedirectAttributes redirect) {
        Card card = joinableCard(user.id(), key);
        LocalDateTime now = nowInVietnam();
        List<Line> lines = mailSessions.lines(user.id(), card, now);
        List<SchoolMailJoined> rows = new ArrayList<>();
        String place = form.getPlace().isEmpty() ? null : form.getPlace();
        String title = card.subject().isEmpty() ? "(no subject)" : card.subject();
        for (Line line : lines) {
            if (form.getSessions().contains(line.id())) {
                rows.add(new SchoolMailJoined(user.id(), card.key(), line.session().day(), line.session().start(),
                        line.session().end(), title, place, card.trainingPoints(), !line.found(), now()));
            }
        }
        List<SchoolMailJoined> upcoming = mailSessions.upcomingJoined(user.id(), card.keys(), now);
        List<Integer> upcomingIds = upcoming.stream().map(SchoolMailJoined::getId).toList();
        List<SchoolMailJoined> over = mailSessions.joinedOf(user.id(), card.keys()).stream()
                .filter(row -> !upcomingIds.contains(row.getId())).toList();
        String error = form.getPlace().length() > MAX_PLACE ? PLACE_TOO_LONG : null;
        if (error == null && !form.getDay().isEmpty()) {
            error = addByHand(form, now.toLocalDate(), over, rows, user.id(), card, title, place);
        }
        // The limit is on all of the email's joined sessions: those already over stay, so they count too.
        if (error == null && rows.size() + over.size() > MAX_JOINED) {
            error = TOO_MANY;
        }
        if (error != null) {
            return joinPage(model, card, lines, form, error);
        }
        try {
            mailSessions.replaceUpcoming(user.id(), card.keys(), now, rows);
        } catch (DataIntegrityViolationException doubleClick) {
            // Another Save of this form (the other click of a double click) wrote the same sessions first; this
            // Save was undone whole, so save it again over that one.
            mailSessions.replaceUpcoming(user.id(), card.keys(), now, rows.stream().map(SchoolMailJoined::copy).toList());
        }
        Flash.success(redirect, rows.isEmpty() ? "You left this event. It is no longer in your Timetable."
                : "Joined. It is in your Timetable now.");
        return "redirect:/school/mailbox#mail-" + card.key();
    }

    /** The session added by hand, added to rows unless it is already there or over; or what is wrong with it. */
    private String addByHand(JoinForm form, LocalDate today, List<SchoolMailJoined> over, List<SchoolMailJoined> rows,
            Integer userId, Card card, String title, String place) {
        LocalDate day;
        LocalTime start;
        LocalTime end;
        try {
            day = LocalDate.parse(form.getDay());
        } catch (DateTimeParseException error) {
            return PAST_DAY;
        }
        if (day.isBefore(today)) {
            return PAST_DAY;
        }
        try {
            start = form.getStart().isEmpty() ? null : LocalTime.parse(form.getStart());
            end = form.getEnd().isEmpty() ? null : LocalTime.parse(form.getEnd());
        } catch (DateTimeParseException error) {
            return NO_START;
        }
        if (start == null) {
            return NO_START;
        }
        if (end != null && !end.isAfter(start)) {
            return END_BEFORE_START;
        }
        LocalTime startTime = start;
        if (Stream.concat(rows.stream(), over.stream())
                .noneMatch(r -> r.getDay().equals(day) && r.getStart().equals(startTime))) {
            rows.add(new SchoolMailJoined(userId, card.key(), day, start, end, title, place, card.trainingPoints(),
                    true, now()));
        }
        return null;
    }

    /**
     * Removes the card's joined sessions that haven't ended; those already over stay in the Timetable. A joined
     * event whose email is no longer in the Mailbox can be left too, while it has sessions ahead.
     */
    @PostMapping("/{key}/leave")
    String leave(@AuthenticationPrincipal AppUser user, @PathVariable String key, RedirectAttributes redirect) {
        Card card = view(user.id()).card(key);
        List<String> keys = card != null ? card.keys() : List.of(key);
        LocalDateTime now = nowInVietnam();
        if (card != null ? !card.joinable() : mailSessions.upcomingJoined(user.id(), keys, now).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        mailSessions.leave(user.id(), keys, now);
        Flash.success(redirect, "You left this event. It is no longer in your Timetable.");
        return "redirect:/school/mailbox" + (card != null ? "#mail-" + card.key() : "");
    }

    @PostMapping("/{key}/automatic")
    String automatic(@AuthenticationPrincipal AppUser user, @PathVariable String key, RedirectAttributes redirect) {
        Card card = card(user.id(), key);
        for (String mailKey : card.keys()) {
            choices.findByUserIdAndMailKey(user.id(), mailKey).ifPresent(choice -> {
                choice.backToAutomatic(now());
                choices.save(choice);
            });
        }
        Flash.success(redirect, "Back to automatic: the laptop's sorting applies again.");
        return "redirect:/school/mailbox#mail-" + card.key();
    }
}
