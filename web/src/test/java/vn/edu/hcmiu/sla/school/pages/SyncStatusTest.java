package vn.edu.hcmiu.sla.school.pages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import vn.edu.hcmiu.sla.school.pages.SyncStatus.MailProblem;
import vn.edu.hcmiu.sla.school.pages.SyncStatus.RunInfo;
import vn.edu.hcmiu.sla.school.pages.SyncStatus.Status;
import vn.edu.hcmiu.sla.school.pages.SyncStatus.SystemLine;

/** Java twin of tests/test_school_sync_status.py. */
class SyncStatusTest {

    // UTC. 07:05 UTC = 14:05 in Vietnam.
    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 7, 30);

    static LocalDateTime at(String hhmm) {
        return hhmm == null ? null : LocalDateTime.of(NOW.toLocalDate(), LocalTime.parse(hhmm));
    }

    static RunInfo run(String status, String started, String finished, String errorCode,
            Map<String, Map<String, String>> sections) {
        return new RunInfo(status, at(started), at(finished), errorCode, null, sections);
    }

    static RunInfo run(String status) {
        return run(status, "07:00", "07:05", null, null);
    }

    static Status status(RunInfo latest, LocalDateTime requested, boolean hasDevice, LocalDateTime lastSeen,
            LocalDateTime lastGood) {
        return SyncStatus.describe(NOW, requested, latest, lastGood, hasDevice, lastSeen);
    }

    static Status status(RunInfo latest) {
        return status(latest, null, true, NOW, null);
    }

    static final Map<String, String> OK = Map.of("status", "ok");

    static Map<String, String> bad(String code) {
        return Map.of("status", "failed", "error_code", code, "error_message", "x");
    }

    /** Parts in the order given, as the agent sends them. */
    @SafeVarargs
    static Map<String, Map<String, String>> parts(Map.Entry<String, Map<String, String>>... entries) {
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : entries) {
            sections.put(entry.getKey(), entry.getValue());
        }
        return sections;
    }

    static Map<String, Map<String, String>> edu() {
        return parts(Map.entry("timetable", OK), Map.entry("exams", OK));
    }

    @Test
    void noDeviceYetPointsToTheDevicesPage() {
        Status result = status(null, null, false, null, null);

        assertThat(result.state()).isEqualTo("no_device");
        assertThat(result.detail()).contains("Devices page");
        assertThat(result.detail()).contains("open School-Life-Assistant");
        assertThat(result.laptopWarning()).isNull();
    }

    @Test
    void neverSynced() {
        assertThat(status(null).state()).isEqualTo("never");
    }

    @Test
    void aSuccessfulSyncShowsVietnamTime() {
        Status result = status(run("success"), null, true, NOW, LocalDateTime.of(2026, 10, 1, 7, 5));

        assertThat(List.of(result.state(), result.headline())).containsExactly("success", "Synced at 14:05");
        assertThat(result.lastSyncedAt()).isEqualTo(LocalDateTime.of(2026, 10, 1, 7, 5));
    }

    @Test
    void aSyncFromAnEarlierDayShowsTheDate() {
        RunInfo earlier = new RunInfo("success", LocalDateTime.of(2026, 9, 29, 7, 0), LocalDateTime.of(2026, 9, 29, 7, 5),
                null, null, null);

        assertThat(status(earlier).headline()).isEqualTo("Synced on 29/09 14:05");
    }

    @Test
    void running() {
        assertThat(status(run("running", "07:25", null, null, null)).state()).isEqualTo("syncing");
    }

    @Test
    void aRunStuckFor20MinutesShowsAsFailed() {
        assertThat(status(run("running", "07:10", null, null, null)).state()).isEqualTo("failed");
    }

    @Test
    void syncNowWaitingForTheLaptop() {
        assertThat(status(run("success"), at("07:20"), true, NOW, null).state()).isEqualTo("requested");
    }

    @Test
    void aRequestAlreadyServedIsNotShown() {
        assertThat(status(run("success"), at("06:00"), true, NOW, null).state()).isEqualTo("success");
    }

    @Test
    void wrongPasswordPausesAndSaysHowToFixIt() {
        Status result = status(run("failed", "07:00", "07:05", "bad_credentials", null));

        assertThat(result.state()).isEqualTo("paused");
        assertThat(result.detail()).contains("in Accounts");
    }

    @Test
    void extraVerificationPausesAndSuggestsImport() {
        Status result = status(run("failed", "07:00", "07:05", "extra_verification", null));

        assertThat(result.state()).isEqualTo("paused");
        assertThat(result.detail()).contains("sla-agent import");
    }

    @Test
    void aNetworkFailureWillRetry() {
        Status result = status(run("failed", "07:00", "07:05", "network", null));

        assertThat(result.state()).isEqualTo("failed");
        assertThat(result.detail()).contains("automatically");
    }

    @Test
    void allPartsFailedUsesTheirError() {
        Status result = status(run("failed", "07:00", "07:05", null,
                parts(Map.entry("timetable", bad("edusoft_changed")), Map.entry("tuition", bad("edusoft_changed")))));

        assertThat(result.state()).isEqualTo("failed");
        assertThat(result.headline()).contains("changed");
    }

    @Test
    void partialNamesThePartsThatFailed() {
        Status result = status(run("partial", "07:00", "07:05", null,
                parts(Map.entry("timetable", OK), Map.entry("tuition", bad("edusoft_changed")))),
                null, true, NOW, LocalDateTime.of(2026, 10, 1, 7, 5));

        assertThat(List.of(result.state(), result.headline())).containsExactly("partial", "Partly synced at 14:05");
        assertThat(result.detail()).contains("tuition").doesNotContain("timetable");
    }

    @Test
    void partsAreNamedInTheSameOrderWhateverOrderTheDatabaseKeptThemIn() {
        // MySQL gives JSON keys back shortest first: exams, tuition, timetable.
        Status result = status(run("partial", "07:00", "07:05", null, parts(Map.entry("exams", bad("edusoft_changed")),
                Map.entry("tuition", OK), Map.entry("timetable", bad("edusoft_changed")))));

        assertThat(result.detail()).startsWith("Couldn't read: timetable, exam schedule.");
    }

    @Test
    void laptopThatNeverCheckedIn() {
        assertThat(status(null, null, true, null, null).laptopWarning()).contains("hasn't checked in yet");
        assertThat(status(null, null, true, null, null).laptopWarning()).contains("School-Life-Assistant");
    }

    @Test
    void laptopSilentForMoreThanAnHour() {
        assertThat(status(null, null, true, LocalDateTime.of(2026, 9, 30, 7, 0), null).laptopWarning())
                .isEqualTo("Your laptop hasn't checked in since Wed 30/09 14:00.");
    }

    @Test
    void laptopSeenWithinTheHourGivesNoWarning() {
        assertThat(status(null, null, true, NOW.minusMinutes(59), null).laptopWarning()).isNull();
        assertThat(status(null, null, true, NOW.minusMinutes(61), null).laptopWarning())
                .isEqualTo("Your laptop hasn't checked in since Thu 01/10 13:29.");
    }

    // ---- One line per system ------------------------------------------------------

    @Test
    void oneLinePerSystemFromTheLatestRunThatIncludedIt() {
        Map<String, Map<String, String>> sections = edu();
        sections.put("blackboard", bad("bad_credentials"));

        List<SystemLine> lines = SyncStatus.systemLines(List.of(run("partial", "07:00", "07:05", null, sections)), NOW);

        assertThat(lines).extracting(SystemLine::name, SystemLine::state)
                .containsExactly(tuple("EduSoft", "ok"),
                        tuple("Blackboard", "paused"));
        assertThat(lines.get(0).text()).isEqualTo("synced at 14:05");
        assertThat(lines.get(1).text()).isEqualTo("paused: wrong username or password. Change it in Accounts.");
    }

    @Test
    void aSystemNeverSyncedHasNoLine() {
        assertThat(SyncStatus.systemLines(List.of(run("success", "07:00", "07:05", null, edu())), NOW))
                .extracting(SystemLine::name).containsExactly("EduSoft");
    }

    @Test
    void aPartFailureIsShownAsPartlySynced() {
        Map<String, Map<String, String>> sections = edu();
        sections.put("tuition", bad("edusoft_changed"));

        assertThat(SyncStatus.systemLines(List.of(run("partial", "07:00", "07:05", null, sections)), NOW))
                .containsExactly(new SystemLine("EduSoft", "partial", "synced at 14:05, but couldn't read: tuition"));
    }

    @Test
    void blackboardFailureHeadlineNamesBlackboard() {
        Status result = status(run("failed", "07:00", "07:05", null, parts(Map.entry("blackboard", bad("bad_credentials")))));

        assertThat(result.state()).isEqualTo("paused");
        assertThat(result.headline()).contains("Blackboard");
        assertThat(result.detail()).contains("in Accounts");
    }

    // ---- Outlook ------------------------------------------------------------------

    @Test
    void outlookHasItsOwnLineAndSaysWhatToDo() {
        Map<String, Map<String, String>> sections = edu();
        sections.put("outlook", bad("outlook_blocked"));

        List<SystemLine> lines = SyncStatus.systemLines(List.of(run("partial", "07:00", "07:05", null, sections)), NOW);

        assertThat(lines).extracting(SystemLine::name, SystemLine::state)
                .containsExactly(tuple("EduSoft", "ok"), tuple("Outlook", "failed"));
        assertThat(lines.get(1).text()).isEqualTo(
                "Outlook didn't let the agent read your mail; check Outlook, then press Sync now.");
    }

    @Test
    void outlookFailureHeadlineNamesOutlook() {
        Status result = status(run("failed", "07:00", "07:05", null,
                parts(Map.entry("outlook", bad("outlook_not_set_up")))));

        assertThat(result.headline()).isEqualTo("Sync failed: classic Outlook isn't set up on your laptop");
        assertThat(result.detail()).contains("Open Outlook (classic)");
    }

    @Test
    void aPartlyFailedOutlookIsNamedAmongThePartsThatFailed() {
        Map<String, Map<String, String>> sections = edu();
        sections.put("outlook", bad("outlook_blocked"));

        assertThat(status(run("partial", "07:00", "07:05", null, sections)).detail())
                .isEqualTo("Couldn't read: Outlook. The previous data for it is still shown.");
    }

    @Test
    void mailProblemComesFromTheNewestRunThatReadOutlook() {
        RunInfo edusoftOnly = run("success", "07:00", "07:05", null, edu());
        RunInfo blocked = run("partial", "05:00", "05:05", null, parts(Map.entry("timetable", OK),
                Map.entry("outlook", bad("outlook_blocked"))));

        MailProblem problem = SyncStatus.mailProblem(List.of(edusoftOnly, blocked));

        assertThat(problem).isEqualTo(new MailProblem("Sync failed: Outlook didn't let the agent read your mail",
                SyncStatus.CHECK_OUTLOOK, at("05:05")));
    }

    @Test
    void noMailProblemWhenOutlookWasReadOrNeverTried() {
        RunInfo read = run("success", "07:00", "07:05", null, parts(Map.entry("outlook", OK)));

        assertThat(SyncStatus.mailProblem(List.of(read))).isNull();
        assertThat(SyncStatus.mailProblem(List.of(run("success", "07:00", "07:05", null, edu())))).isNull();
        assertThat(SyncStatus.mailProblem(List.of())).isNull();
    }

    @Test
    void anUnknownOutlookProblemStillSaysSomething() {
        RunInfo crashed = run("partial", "07:00", "07:05", null, parts(Map.entry("outlook", bad("unknown"))));

        assertThat(SyncStatus.mailProblem(List.of(crashed)).headline()).isEqualTo("Sync failed");
    }

    // ---- IUPay --------------------------------------------------------------------

    @Test
    void iupayHasItsOwnLineAfterEduSoft() {
        Map<String, Map<String, String>> sections = edu();
        sections.put("iupay", OK);

        assertThat(SyncStatus.systemLines(List.of(run("success", "07:00", "07:05", null, sections)), NOW))
                .extracting(SystemLine::name, SystemLine::state)
                .containsExactly(tuple("EduSoft", "ok"), tuple("IUPay", "ok"));
    }

    @ParameterizedTest
    @CsvSource({  // no apostrophes: CsvSource quotes with '
        "network, be reached",
        "extra_verification, asks for a captcha",
        "bad_credentials, recognise your student ID",
        "source_changed, data format changed",
    })
    void anIupayProblemIsNamedOnItsOwnLineAndNeverPauses(String code, String words) {
        Map<String, Map<String, String>> sections = edu();
        sections.put("iupay", bad(code));

        List<SystemLine> lines = SyncStatus.systemLines(List.of(run("partial", "07:00", "07:05", null, sections)), NOW);

        assertThat(lines.get(0)).isEqualTo(new SystemLine("EduSoft", "ok", "synced at 14:05"));
        assertThat(lines.get(1).name()).isEqualTo("IUPay");
        assertThat(lines.get(1).state()).isEqualTo("failed");
        assertThat(lines.get(1).text()).contains(words);
    }

    @Test
    void aRunWhereOnlyIupayFailedSaysIupayInTheHeadline() {
        Status result = status(run("failed", "07:00", "07:05", null, parts(Map.entry("iupay", bad("extra_verification")))));

        assertThat(List.of(result.state(), result.headline()))
                .containsExactly("failed", "Sync failed: IUPay now asks for a captcha");
    }

    @Test
    void aPartlySyncedRunNamesIupayAsTuition() {
        Map<String, Map<String, String>> sections = edu();
        sections.put("iupay", bad("network"));

        assertThat(status(run("partial", "07:00", "07:05", null, sections)).detail())
                .startsWith("Couldn't read: tuition (IUPay).");
    }

    @Test
    void accountLinesNameEverySystemEvenWithoutARun() {
        List<SystemLine> lines = SyncStatus.accountLines(List.of(run("success", "07:00", "07:05", null, edu())), NOW);

        assertThat(lines).extracting(SystemLine::name, SystemLine::state, SystemLine::text).containsExactly(
                tuple("EduSoft", "ok", "synced at 14:05"), tuple("IUPay", "none", "never synced"),
                tuple("Blackboard", "none", "not set up"), tuple("Outlook", "none", "not set up"));
    }
}
