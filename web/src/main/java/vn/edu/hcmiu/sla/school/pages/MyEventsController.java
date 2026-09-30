package vn.edu.hcmiu.sla.school.pages;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import vn.edu.hcmiu.sla.auth.AppUser;
import vn.edu.hcmiu.sla.core.Flash;
import vn.edu.hcmiu.sla.school.VietnamTime;
import vn.edu.hcmiu.sla.school.events.Details;
import vn.edu.hcmiu.sla.school.events.MyEvents;
import vn.edu.hcmiu.sla.school.events.Occurrences;
import vn.edu.hcmiu.sla.school.model.SchoolMyEvent;

/** The student's own events: new, edit, Check, Save and Delete (docs/superpowers/specs/2026-09-30-my-events-design.md, 4). */
@Controller
@RequestMapping("/school/events")
public class MyEventsController {

    static final String CHECK = "check";

    private final Clock clock;
    private final MyEvents myEvents;

    public MyEventsController(Clock clock, MyEvents myEvents) {
        this.clock = clock;
        this.myEvents = myEvents;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private SchoolMyEvent owned(AppUser user, int id) {
        return myEvents.find(user.id(), id).orElseThrow(MyEventsController::notFound);
    }

    /** A day of an event, as the edit page shows it: "Tue 06/10", and whether it is skipped. */
    public record DayChoice(LocalDate day, String label, boolean skipped) {
    }

    private static DayChoice choice(SchoolMyEvent event, LocalDate day) {
        return new DayChoice(day, VietnamTime.dayLabel(day), event.isSkipped(day));
    }

    /** "2026-10-06" when it is one of the event's days (skipped or not); otherwise 400. */
    private static LocalDate eventDay(SchoolMyEvent event, String text) {
        try {
            LocalDate day = LocalDate.parse(text);
            if (Occurrences.falls(event.rule(), day)) {
                return day;
            }
        } catch (DateTimeParseException error) {
            // falls through to 400
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }

    /** The form page; event is null for a new event, checked null until Check is pressed. */
    String page(Model model, SchoolMyEvent event, EventForm form, Map<String, String> errors, MyEvents.Checked checked,
            DayChoice focus) {
        model.addAttribute("event", event);
        model.addAttribute("form", form);
        model.addAttribute("errors", errors);
        model.addAttribute("checked", checked);
        model.addAttribute("focus", focus);
        model.addAttribute("skippedDays", event == null ? List.of()
                : event.getSkips().stream().map(skip -> choice(event, skip.getDay())).toList());
        return "school/event-form";
    }

    @GetMapping("/new")
    String newEvent(Model model) {
        return page(model, null, EventForm.fresh(VietnamTime.date(now())), Map.of(), null, null);
    }

    @PostMapping("/new")
    String create(@AuthenticationPrincipal AppUser user, @ModelAttribute("form") EventForm form,
            @RequestParam(defaultValue = "save") String action, Model model, RedirectAttributes redirect) {
        Map<String, String> errors = form.check();
        if (!errors.isEmpty()) {
            return page(model, null, form, errors, null, null);
        }
        Details details = form.details();
        if (CHECK.equals(action)) {
            return page(model, null, form, errors, myEvents.check(user.id(), null, details), null);
        }
        SchoolMyEvent saved = myEvents.create(user.id(), details, now());
        Flash.success(redirect, MyEvents.savedFlash(details.title(), myEvents.check(user.id(), saved.getId(), details)));
        return "redirect:/school/timetable";
    }

    @GetMapping("/{id}/edit")
    String edit(@AuthenticationPrincipal AppUser user, @PathVariable int id,
            @RequestParam(required = false) String day, Model model) {
        SchoolMyEvent event = owned(user, id);
        DayChoice focus = null;
        if (day != null) {
            try {
                LocalDate asked = LocalDate.parse(day);
                focus = Occurrences.falls(event.rule(), asked) ? choice(event, asked) : null;
            } catch (DateTimeParseException error) {
                focus = null;
            }
        }
        return page(model, event, EventForm.of(event), Map.of(), null, focus);
    }

    @PostMapping("/{id}/edit")
    String update(@AuthenticationPrincipal AppUser user, @PathVariable int id, @ModelAttribute("form") EventForm form,
            @RequestParam(defaultValue = "save") String action, Model model, RedirectAttributes redirect) {
        SchoolMyEvent event = owned(user, id);
        Map<String, String> errors = form.check();
        if (!errors.isEmpty()) {
            return page(model, event, form, errors, null, null);
        }
        Details details = form.details();
        if (CHECK.equals(action)) {
            return page(model, event, form, errors, myEvents.check(user.id(), id, details), null);
        }
        myEvents.update(user.id(), id, details, now()).orElseThrow(MyEventsController::notFound);
        Flash.success(redirect, MyEvents.savedFlash(details.title(), myEvents.check(user.id(), id, details)));
        return "redirect:/school/timetable";
    }

    @PostMapping("/{id}/delete")
    String delete(@AuthenticationPrincipal AppUser user, @PathVariable int id, RedirectAttributes redirect) {
        SchoolMyEvent event = owned(user, id);
        myEvents.delete(user.id(), id);
        Flash.success(redirect, "Deleted \"" + event.getTitle() + "\".");
        return "redirect:/school/timetable";
    }

    @PostMapping("/{id}/skip")
    String skip(@AuthenticationPrincipal AppUser user, @PathVariable int id, @RequestParam String day,
            RedirectAttributes redirect) {
        LocalDate skipped = eventDay(owned(user, id), day);
        try {
            myEvents.skip(user.id(), id, skipped);
        } catch (DataIntegrityViolationException doubleClick) {
            // The other click of a double click skipped this day first: it is skipped, as asked.
        }
        Flash.success(redirect, VietnamTime.dayLabel(skipped) + " is skipped.");
        return "redirect:/school/events/" + id + "/edit?day=" + skipped;
    }

    @PostMapping("/{id}/unskip")
    String unskip(@AuthenticationPrincipal AppUser user, @PathVariable int id, @RequestParam String day,
            RedirectAttributes redirect) {
        LocalDate back = eventDay(owned(user, id), day);
        myEvents.unskip(user.id(), id, back);
        Flash.success(redirect, VietnamTime.dayLabel(back) + " is back.");
        return "redirect:/school/events/" + id + "/edit?day=" + back;
    }
}
