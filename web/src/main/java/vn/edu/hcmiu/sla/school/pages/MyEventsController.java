package vn.edu.hcmiu.sla.school.pages;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

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

    /** The form page; event is null for a new event, checked null until Check is pressed. */
    String page(Model model, SchoolMyEvent event, EventForm form, Map<String, String> errors, MyEvents.Checked checked) {
        model.addAttribute("event", event);
        model.addAttribute("form", form);
        model.addAttribute("errors", errors);
        model.addAttribute("checked", checked);
        model.addAttribute("focus", null);
        model.addAttribute("skippedDays", java.util.List.of());
        return "school/event-form";
    }

    @GetMapping("/new")
    String newEvent(Model model) {
        return page(model, null, EventForm.fresh(VietnamTime.date(now())), Map.of(), null);
    }

    @PostMapping("/new")
    String create(@AuthenticationPrincipal AppUser user, @ModelAttribute("form") EventForm form,
            @RequestParam(defaultValue = "save") String action, Model model, RedirectAttributes redirect) {
        Map<String, String> errors = form.check();
        if (!errors.isEmpty()) {
            return page(model, null, form, errors, null);
        }
        Details details = form.details();
        if (CHECK.equals(action)) {
            return page(model, null, form, errors, myEvents.check(user.id(), null, details));
        }
        SchoolMyEvent saved = myEvents.create(user.id(), details, now());
        Flash.success(redirect, MyEvents.savedFlash(details.title(), myEvents.check(user.id(), saved.getId(), details)));
        return "redirect:/school/timetable";
    }

    @GetMapping("/{id}/edit")
    String edit(@AuthenticationPrincipal AppUser user, @PathVariable int id, Model model) {
        SchoolMyEvent event = owned(user, id);
        return page(model, event, EventForm.of(event), Map.of(), null);
    }

    @PostMapping("/{id}/edit")
    String update(@AuthenticationPrincipal AppUser user, @PathVariable int id, @ModelAttribute("form") EventForm form,
            @RequestParam(defaultValue = "save") String action, Model model, RedirectAttributes redirect) {
        SchoolMyEvent event = owned(user, id);
        Map<String, String> errors = form.check();
        if (!errors.isEmpty()) {
            return page(model, event, form, errors, null);
        }
        Details details = form.details();
        if (CHECK.equals(action)) {
            return page(model, event, form, errors, myEvents.check(user.id(), id, details));
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
}
