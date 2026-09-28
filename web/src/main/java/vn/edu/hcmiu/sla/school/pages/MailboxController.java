package vn.edu.hcmiu.sla.school.pages;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import vn.edu.hcmiu.sla.auth.AppUser;
import vn.edu.hcmiu.sla.core.Flash;
import vn.edu.hcmiu.sla.school.VietnamTime;
import vn.edu.hcmiu.sla.school.mail.Mailbox;
import vn.edu.hcmiu.sla.school.mail.Mailbox.Card;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoice;
import vn.edu.hcmiu.sla.school.model.SchoolMailChoiceRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailRepository;
import vn.edu.hcmiu.sla.school.model.SchoolMailStatusRepository;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRunRepository;
import vn.edu.hcmiu.sla.school.pages.SyncStatus.RunInfo;

/**
 * The Mailbox tab: the student's emails in priority boxes, with Done and Move to…. Both only change the app,
 * never the real mailbox. A card of another user is 404.
 */
@Controller
@RequestMapping("/school/mailbox")
public class MailboxController {

    static final String CATEGORY_ERROR = "Choose a first category, and a different second one or none.";

    private final Clock clock;
    private final SchoolMailRepository mails;
    private final SchoolMailChoiceRepository choices;
    private final SchoolMailStatusRepository statuses;
    private final SchoolSyncRunRepository runs;

    public MailboxController(Clock clock, SchoolMailRepository mails, SchoolMailChoiceRepository choices,
            SchoolMailStatusRepository statuses, SchoolSyncRunRepository runs) {
        this.clock = clock;
        this.mails = mails;
        this.choices = choices;
        this.statuses = statuses;
        this.runs = runs;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private Mailbox.View view(Integer userId) {
        Map<String, SchoolMailChoice> byKey = choices.findByUserId(userId).stream()
                .collect(Collectors.toMap(SchoolMailChoice::getMailKey, Function.identity()));
        return Mailbox.build(mails.findByUserIdOrderByReceivedAtDescIdDesc(userId), byKey, VietnamTime.date(now()));
    }

    /** The user's card whose newest email has this key, else 404. */
    private Card card(Integer userId, String key) {
        Card card = view(userId).card(key);
        if (card == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return card;
    }

    private SchoolMailChoice choice(Integer userId, String key) {
        return choices.findByUserIdAndMailKey(userId, key).orElseGet(() -> new SchoolMailChoice(userId, key, now()));
    }

    @GetMapping
    String mailbox(@AuthenticationPrincipal AppUser user, Model model) {
        model.addAttribute("view", view(user.id()));
        model.addAttribute("status", statuses.findById(user.id()).orElse(null));
        model.addAttribute("problem", SyncStatus.mailProblem(
                runs.findTop10ByUserIdOrderByStartedAtDescIdDesc(user.id()).stream().map(RunInfo::of).toList()));
        model.addAttribute("categories", Mailbox.CATEGORIES);
        return "school/mailbox";
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
