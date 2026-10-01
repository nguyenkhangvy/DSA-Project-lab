package vn.edu.hcmiu.sla.school.pages;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import vn.edu.hcmiu.sla.school.VietnamTime;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRun;
import vn.edu.hcmiu.sla.school.sync.Scheduling;

/**
 * Turns the sync history into the status the user sees. Pure functions; the Java twin of
 * app/school/services/sync_status.py, with the same wording. Times are UTC, shown in Vietnam time.
 *
 * <p>A run's parts are always read in the order timetable, exams, tuition, iupay, blackboard, outlook: MySQL keeps the
 * keys of the JSON column in its own order, so the order they come back in means nothing.
 */
public final class SyncStatus {

    private SyncStatus() {
    }

    static final String RETRY = "It will be tried again automatically.";
    static final Duration LAPTOP_SILENT = Duration.ofHours(1); // the laptop checks in every minute while it's on
    static final List<String> PART_ORDER = List.of("timetable", "exams", "tuition", "iupay", "blackboard", "outlook");

    /** What a status says: state, headline, and what to do. */
    record Problem(String state, String headline, String detail) {
    }

    static final Map<String, Problem> PROBLEMS = Map.of(
            "bad_credentials", new Problem("paused", "Paused: EduSoft rejected your student ID or password",
                    "Enter them again in Accounts (open School-Life-Assistant on your laptop)."),
            "extra_verification", new Problem("paused", "Paused: EduSoft asked for extra verification",
                    "Automatic sync can't pass a CAPTCHA or code. Save the pages from your browser "
                            + "and use `sla-agent import`."),
            "network", new Problem("failed", "Sync failed: EduSoft couldn't be reached", RETRY),
            "session_expired", new Problem("failed", "Sync failed: EduSoft ended the session", RETRY),
            "edusoft_changed", new Problem("failed", "Sync failed: EduSoft's pages have changed",
                    "sla-agent needs an update to read the new pages."),
            "timeout", new Problem("failed", "The last sync didn't finish", RETRY));
    static final Problem UNKNOWN = new Problem("failed", "Sync failed", RETRY);

    static final Map<String, Problem> BLACKBOARD_PROBLEMS = Map.of(
            "bad_credentials", new Problem("paused", "Paused: Blackboard rejected your username or password",
                    "Enter them again in Accounts (open School-Life-Assistant on your laptop)."),
            "extra_verification", new Problem("paused", "Paused: Blackboard asked for extra verification",
                    "Automatic Blackboard sync can't pass a CAPTCHA, code or Microsoft sign-in."),
            "network", new Problem("failed", "Sync failed: Blackboard couldn't be reached", RETRY),
            "session_expired", new Problem("failed", "Sync failed: Blackboard ended the session", RETRY),
            "source_changed", new Problem("failed", "Sync failed: Blackboard's data format has changed",
                    "sla-agent needs an update to read it."));

    static final String CHECK_OUTLOOK = "Outlook may be showing a security warning, or your antivirus may be off. "
            + "The agent never clicks past it. Check Outlook, then press Sync now.";

    static final Map<String, Problem> OUTLOOK_PROBLEMS = Map.of(
            "outlook_not_set_up", new Problem("failed", "Sync failed: classic Outlook isn't set up on your laptop",
                    "Open Outlook (classic), sign in with your IU account, then press Sync now."),
            "outlook_blocked", new Problem("failed", "Sync failed: Outlook didn't let the agent read your mail",
                    CHECK_OUTLOOK));

    static final Map<String, Problem> IUPAY_PROBLEMS = Map.of(
            "network", new Problem("failed", "Sync failed: IUPay couldn't be reached", RETRY),
            "extra_verification", new Problem("failed", "Sync failed: IUPay now asks for a captcha",
                    "Tuition shows the last bills known. Check IUPay in your browser; the agent never passes a captcha."),
            "bad_credentials", new Problem("failed", "Sync failed: IUPay didn't recognise your student ID",
                    "Check the student ID in Accounts (open School-Life-Assistant on your laptop)."),
            "source_changed", new Problem("failed", "Sync failed: IUPay's data format has changed",
                    "sla-agent needs an update to read it."));

    /** IUPay's own words on its line; other codes use FAILURE_HINTS. IUPay never pauses. */
    static final Map<String, String> IUPAY_HINTS = Map.of(
            "extra_verification", "now asks for a captcha; tuition shows the last bills known.",
            "bad_credentials", "didn't recognise your student ID; check it in Accounts.");

    static final Map<String, String> PART_NAMES = Map.of(
            "timetable", "timetable", "exams", "exam schedule", "tuition", "tuition", "iupay", "tuition (IUPay)",
            "blackboard", "Blackboard", "outlook", "Outlook");

    /** A system and the parts of a sync that come from it. */
    record SystemParts(String name, List<String> parts) {
    }

    static final List<SystemParts> SYSTEMS = List.of(
            new SystemParts("EduSoft", List.of("timetable", "exams", "tuition")), // tuition: runs from before IUPay
            new SystemParts("IUPay", List.of("iupay")),
            new SystemParts("Blackboard", List.of("blackboard")),
            new SystemParts("Outlook", List.of("outlook")));

    static final Map<String, String> PAUSE_HINTS = Map.of(
            "EduSoft/bad_credentials", "paused: wrong student ID or password. Change it in Accounts.",
            "Blackboard/bad_credentials", "paused: wrong username or password. Change it in Accounts.",
            "EduSoft/extra_verification", "paused: asked for extra verification (CAPTCHA or code).",
            "Blackboard/extra_verification",
            "paused: asked for extra verification (CAPTCHA, code or Microsoft sign-in).");

    static final Map<String, String> FAILURE_HINTS = Map.of(
            "network", "couldn't be reached; it will be tried again automatically.",
            "session_expired", "ended the session; it will be tried again automatically.",
            "edusoft_changed", "its pages changed; sla-agent needs an update.",
            "source_changed", "its data format changed; sla-agent needs an update.",
            "outlook_not_set_up", "classic Outlook isn't set up on your laptop; open it and sign in, then press Sync now.",
            "outlook_blocked", "Outlook didn't let the agent read your mail; check Outlook, then press Sync now.");

    /** One sync run, as the status needs it. */
    public record RunInfo(String status, LocalDateTime startedAt, LocalDateTime finishedAt, String errorCode,
            String errorMessage, Map<String, Map<String, String>> sections) {

        public static RunInfo of(SchoolSyncRun run) {
            return new RunInfo(run.getStatus(), run.getStartedAt(), run.getFinishedAt(), run.getErrorCode(),
                    run.getErrorMessage(), run.getSections());
        }

        Map<String, Map<String, String>> parts() {
            return sections == null ? Map.of() : sections;
        }
    }

    /** One line per system on the status card. state: ok / partial / failed / paused. */
    public record SystemLine(String name, String state, String text) {
    }

    /** state: no_device / never / requested / syncing / success / partial / failed / paused. */
    public record Status(String state, String headline, String detail, LocalDateTime lastSyncedAt,
            String laptopWarning) {
    }

    private static final DateTimeFormatter DAY_CLOCK = DateTimeFormatter.ofPattern("dd/MM HH:mm");

    /** "at 14:05" today (in Vietnam), else "on 29/09 14:05". */
    static String at(LocalDateTime moment, LocalDateTime now) {
        if (VietnamTime.date(moment).equals(VietnamTime.date(now))) {
            return "at " + VietnamTime.clock(moment);
        }
        return "on " + DAY_CLOCK.format(VietnamTime.of(moment));
    }

    private static List<String> failedParts(Map<String, Map<String, String>> sections, List<String> parts) {
        List<String> failed = new ArrayList<>();
        for (String part : parts) {
            Map<String, String> result = sections.get(part);
            if (result != null && "failed".equals(result.get("status"))) {
                failed.add(part);
            }
        }
        return failed;
    }

    private static String partNames(List<String> parts) {
        return String.join(", ", parts.stream().map(p -> PART_NAMES.getOrDefault(p, p)).toList());
    }

    /** One line per system, from the newest finished run that included it (runs newest first). */
    public static List<SystemLine> systemLines(List<RunInfo> runs, LocalDateTime now) {
        List<SystemLine> lines = new ArrayList<>();
        for (SystemParts system : SYSTEMS) {
            RunInfo run = runs.stream()
                    .filter(r -> !r.status().equals(SchoolSyncRun.RUNNING)
                            && system.parts().stream().anyMatch(r.parts()::containsKey))
                    .findFirst().orElse(null);
            if (run == null) {
                continue;
            }
            List<String> included = system.parts().stream().filter(run.parts()::containsKey).toList();
            List<String> failed = failedParts(run.parts(), included);
            if (failed.isEmpty()) {
                lines.add(new SystemLine(system.name(), "ok", "synced " + at(run.finishedAt(), now)));
                continue;
            }
            String code = run.parts().get(failed.get(0)).get("error_code");
            String pause = PAUSE_HINTS.get(system.name() + "/" + code);
            if (pause != null) {
                lines.add(new SystemLine(system.name(), "paused", pause));
            } else if (failed.size() < included.size()) {
                lines.add(new SystemLine(system.name(), "partial",
                        "synced " + at(run.finishedAt(), now) + ", but couldn't read: " + partNames(failed)));
            } else {
                String hint = system.name().equals("IUPay") ? IUPAY_HINTS.get(code) : null;
                lines.add(new SystemLine(system.name(), "failed", hint != null ? hint
                        : FAILURE_HINTS.getOrDefault(code, "sync failed; it will be tried again automatically.")));
            }
        }
        return lines;
    }

    static final List<String> OPTIONAL_SYSTEMS = List.of("Blackboard", "Outlook");

    /** Every system, for the Accounts page: its status-card line, or "not set up" / "never synced" without one. */
    public static List<SystemLine> accountLines(List<RunInfo> runs, LocalDateTime now) {
        List<SystemLine> lines = systemLines(runs, now);
        List<SystemLine> all = new ArrayList<>();
        for (SystemParts system : SYSTEMS) {
            all.add(lines.stream().filter(line -> line.name().equals(system.name())).findFirst()
                    .orElse(new SystemLine(system.name(), "none",
                            OPTIONAL_SYSTEMS.contains(system.name()) ? "not set up" : "never synced")));
        }
        return all;
    }

    static String laptopWarning(LocalDateTime now, LocalDateTime lastSeenAt) {
        if (lastSeenAt == null) {
            return "Your laptop hasn't checked in yet. Open School-Life-Assistant on it to set it up.";
        }
        if (Duration.between(lastSeenAt, now).compareTo(LAPTOP_SILENT) > 0) {
            return "Your laptop hasn't checked in since " + VietnamTime.when(lastSeenAt) + ".";
        }
        return null;
    }

    public static Status describe(LocalDateTime now, LocalDateTime syncRequestedAt, RunInfo latest,
            LocalDateTime lastGoodFinishedAt, boolean hasDevice, LocalDateTime lastSeenAt) {
        if (!hasDevice) {
            return new Status("no_device", "Not set up yet",
                    "Add your laptop on the Devices page, then open School-Life-Assistant on it.", null, null);
        }
        String warning = laptopWarning(now, lastSeenAt);

        boolean running = latest != null && latest.status().equals(SchoolSyncRun.RUNNING);
        if (running && Duration.between(latest.startedAt(), now).compareTo(Scheduling.RUN_TIMEOUT) < 0) {
            return new Status("syncing", "Syncing…", null, lastGoodFinishedAt, warning);
        }
        if (syncRequestedAt != null && (latest == null || syncRequestedAt.isAfter(latest.startedAt()))) {
            return new Status("requested", "Sync requested, waiting for your laptop",
                    "Your laptop checks in every minute while it's on.", lastGoodFinishedAt, warning);
        }
        if (latest == null) {
            return new Status("never", "Never synced", "Your laptop will sync at its next check-in.",
                    lastGoodFinishedAt, warning);
        }

        Problem problem = null;
        if (running) {
            problem = PROBLEMS.get("timeout");
        } else if (latest.status().equals(SchoolSyncRun.FAILED)) {
            String code = latest.errorCode();
            Map<String, Problem> problems = PROBLEMS;
            if (code == null) {
                List<String> failed = failedParts(latest.parts(), PART_ORDER);
                code = failed.isEmpty() ? null : latest.parts().get(failed.get(0)).get("error_code");
                if (!failed.isEmpty() && failed.get(0).equals("blackboard")) {
                    problems = BLACKBOARD_PROBLEMS;
                } else if (!failed.isEmpty() && failed.get(0).equals("outlook")) {
                    problems = OUTLOOK_PROBLEMS;
                } else if (!failed.isEmpty() && failed.get(0).equals("iupay")) {
                    problems = IUPAY_PROBLEMS;
                }
            }
            problem = code == null ? UNKNOWN : problems.getOrDefault(code, UNKNOWN);
        } else if (latest.status().equals(SchoolSyncRun.PARTIAL)) {
            problem = new Problem("partial", "Partly synced " + at(latest.finishedAt(), now),
                    "Couldn't read: " + partNames(failedParts(latest.parts(), PART_ORDER))
                            + ". The previous data for it is still shown.");
        }
        if (problem != null) {
            return new Status(problem.state(), problem.headline(), problem.detail(), lastGoodFinishedAt, warning);
        }
        return new Status("success", "Synced " + at(latest.finishedAt(), now), null, lastGoodFinishedAt, warning);
    }

    /** A problem reading the Inbox, for the top of Mailbox: headline, what to do, and when. */
    public record MailProblem(String headline, String detail, LocalDateTime at) {
    }

    /** The Outlook problem of the newest finished run that included Outlook (runs newest first), or null. */
    public static MailProblem mailProblem(List<RunInfo> runs) {
        RunInfo run = runs.stream()
                .filter(r -> !r.status().equals(SchoolSyncRun.RUNNING) && r.parts().containsKey("outlook"))
                .findFirst().orElse(null);
        if (run == null || !"failed".equals(run.parts().get("outlook").get("status"))) {
            return null;
        }
        Problem problem = OUTLOOK_PROBLEMS.getOrDefault(run.parts().get("outlook").get("error_code"), UNKNOWN);
        return new MailProblem(problem.headline(), problem.detail(), run.finishedAt());
    }
}
