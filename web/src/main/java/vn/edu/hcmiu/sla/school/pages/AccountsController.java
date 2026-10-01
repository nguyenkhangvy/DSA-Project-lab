package vn.edu.hcmiu.sla.school.pages;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import vn.edu.hcmiu.sla.auth.AppUser;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRunRepository;
import vn.edu.hcmiu.sla.school.pages.SyncStatus.RunInfo;
import vn.edu.hcmiu.sla.school.sync.DeviceKeys;

/**
 * School → Accounts (docs/superpowers/specs/2026-10-01-accounts-window-design.md, 5.1): what each system's sync says
 * and the laptops, with a link that opens the School-Life-Assistant window on the laptop, where accounts are entered
 * and changed. The site never asks for or shows a password, student ID or username.
 */
@Controller
public class AccountsController {

    private final Clock clock;
    private final DeviceKeys deviceKeys;
    private final SchoolSyncRunRepository runs;

    public AccountsController(Clock clock, DeviceKeys deviceKeys, SchoolSyncRunRepository runs) {
        this.clock = clock;
        this.deviceKeys = deviceKeys;
        this.runs = runs;
    }

    @GetMapping("/school/accounts")
    String accounts(@AuthenticationPrincipal AppUser user, Model model) {
        LocalDateTime now = LocalDateTime.now(clock);
        model.addAttribute("accountLines", SyncStatus.accountLines(
                runs.recentRuns(user.id()).stream().map(RunInfo::of).toList(), now));
        model.addAttribute("devices", deviceKeys.active(user.id()));
        return "school/accounts";
    }
}
