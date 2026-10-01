package vn.edu.hcmiu.sla.school.sync;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.hcmiu.sla.school.model.SchoolSyncDevice;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRun;
import vn.edu.hcmiu.sla.school.model.SchoolSyncRunRepository;
import vn.edu.hcmiu.sla.school.model.SchoolSyncSettings;
import vn.edu.hcmiu.sla.school.model.SchoolSyncSettingsRepository;

/** Sync run bookkeeping: settings, the latest runs, and starting a run. */
@Service
public class SyncRuns {

    static final String TIMED_OUT = "The sync didn't finish within 15 minutes.";

    /** Another run started less than 15 minutes ago and hasn't finished. */
    public static class RunInProgress extends RuntimeException {
    }

    /** The user's settings and whether a sync is due. */
    public record Check(SchoolSyncSettings settings, Scheduling.Decision decision) {
    }

    private final SchoolSyncSettingsRepository settings;
    private final SchoolSyncRunRepository runs;

    public SyncRuns(SchoolSyncSettingsRepository settings, SchoolSyncRunRepository runs) {
        this.settings = settings;
        this.runs = runs;
    }

    /** The user's sync settings, made with the default interval the first time. */
    @Transactional
    public SchoolSyncSettings settings(Integer userId) {
        return settings.findById(userId).orElseGet(
                () -> settings.save(new SchoolSyncSettings(userId, SchoolSyncSettings.DEFAULT_INTERVAL_HOURS)));
    }

    /** "Sync now": the laptop's next check-in is told a sync is due. */
    @Transactional
    public void requestSync(Integer userId, LocalDateTime now) {
        settings(userId).setSyncRequestedAt(now);
    }

    /** The newest run, or the newest with one of these statuses. */
    @Transactional(readOnly = true)
    public Optional<SchoolSyncRun> latestRun(Integer userId, String... statuses) {
        if (statuses.length == 0) {
            return runs.findFirstByUserIdOrderByStartedAtDescIdDesc(userId);
        }
        return runs.findFirstByUserIdAndStatusInOrderByStartedAtDescIdDesc(userId, List.of(statuses));
    }

    /** The newest full run (not a mail-only one), or the newest full run with one of these statuses. */
    @Transactional(readOnly = true)
    public Optional<SchoolSyncRun> latestFullRun(Integer userId, String... statuses) {
        if (statuses.length == 0) {
            return runs.findFirstByUserIdAndTriggerNotOrderByStartedAtDescIdDesc(userId, SchoolSyncRun.MAIL);
        }
        return runs.findFirstByUserIdAndTriggerNotAndStatusInOrderByStartedAtDescIdDesc(userId, SchoolSyncRun.MAIL,
                List.of(statuses));
    }

    @Transactional
    public Check check(Integer userId, LocalDateTime now) {
        SchoolSyncSettings mine = settings(userId);
        Optional<SchoolSyncRun> last = latestFullRun(userId);
        Optional<SchoolSyncRun> lastGood = latestFullRun(userId, SchoolSyncRun.SUCCESS, SchoolSyncRun.PARTIAL);
        Optional<SchoolSyncRun> running = latestFullRun(userId, SchoolSyncRun.RUNNING);
        return new Check(mine, Scheduling.decide(
                now,
                mine.getSyncRequestedAt(),
                last.map(SchoolSyncRun::getStartedAt).orElse(null),
                lastGood.map(SchoolSyncRun::getStartedAt).orElse(null),
                running.map(SchoolSyncRun::getStartedAt).orElse(null)));
    }

    /** Closes stuck runs as timed out, then opens a new one; {@link RunInProgress} if one is still going. */
    @Transactional
    public SchoolSyncRun start(SchoolSyncDevice device, String trigger, LocalDateTime now) {
        for (SchoolSyncRun run : runs.findByUserIdAndStatus(device.getUserId(), SchoolSyncRun.RUNNING)) {
            if (Duration.between(run.getStartedAt(), now).compareTo(Scheduling.RUN_TIMEOUT) < 0) {
                throw new RunInProgress();
            }
            run.finish(SchoolSyncRun.FAILED, now, "timeout", TIMED_OUT);
        }
        return runs.save(new SchoolSyncRun(device.getUserId(), device.getId(), trigger, now));
    }
}
