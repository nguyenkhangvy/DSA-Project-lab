package vn.edu.hcmiu.sla.school.pages;

import java.time.Clock;
import java.time.LocalDateTime;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import vn.edu.hcmiu.sla.auth.AppUser;
import vn.edu.hcmiu.sla.core.Flash;
import vn.edu.hcmiu.sla.school.model.SchoolSyncDevice;
import vn.edu.hcmiu.sla.school.sync.DeviceKeys;

/**
 * The Devices page: add a laptop (its key is shown once), rename it, or cancel it so its key stops
 * working. Another user's device is 404.
 */
@Controller
@RequestMapping("/school/devices")
public class DevicesController {

    private final Clock clock;
    private final DeviceKeys deviceKeys;

    public DevicesController(Clock clock, DeviceKeys deviceKeys) {
        this.clock = clock;
        this.deviceKeys = deviceKeys;
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private String page(AppUser user, Model model) {
        model.addAttribute("devices", deviceKeys.active(user.id()));
        return "school/devices";
    }

    @GetMapping
    String devices(@AuthenticationPrincipal AppUser user, Model model) {
        model.addAttribute("form", new DeviceForm());
        return page(user, model);
    }

    @PostMapping
    String add(@AuthenticationPrincipal AppUser user, @Valid @ModelAttribute("form") DeviceForm form,
            BindingResult result, Model model, HttpServletResponse response) {
        if (!result.hasErrors()) {
            String rawKey = deviceKeys.create(user.id(), form.getName(), LocalDateTime.now(clock)).rawKey();
            model.addAttribute("newKey", rawKey);
            model.addAttribute("form", new DeviceForm());
            response.setHeader("Cache-Control", "no-store"); // the key is shown once; the browser keeps no copy
        }
        return page(user, model);
    }

    @PostMapping("/{deviceId}/rename")
    String rename(@AuthenticationPrincipal AppUser user, @PathVariable int deviceId,
            @Valid @ModelAttribute("form") DeviceForm form, BindingResult result, RedirectAttributes redirect) {
        deviceKeys.own(user.id(), deviceId).orElseThrow(DevicesController::notFound);
        if (result.hasErrors()) {
            Flash.error(redirect, "A device name is required (up to 100 characters).");
        } else {
            deviceKeys.rename(user.id(), deviceId, form.getName());
            Flash.success(redirect, "Device renamed.");
        }
        return "redirect:/school/devices";
    }

    @PostMapping("/{deviceId}/revoke")
    String revoke(@AuthenticationPrincipal AppUser user, @PathVariable int deviceId, RedirectAttributes redirect) {
        SchoolSyncDevice device = deviceKeys.revoke(user.id(), deviceId, LocalDateTime.now(clock))
                .orElseThrow(DevicesController::notFound);
        Flash.success(redirect, "“" + device.getName() + "” can no longer sync.");
        return "redirect:/school/devices";
    }
}
