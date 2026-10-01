package vn.edu.hcmiu.sla.school.sync;

import java.time.Duration;
import java.time.LocalDateTime;

/** When should the laptop agent sync? A pure function, so it is easy to test. All times are UTC. */
public final class Scheduling {

    private Scheduling() {
    }

    public static final Duration RUN_TIMEOUT = Duration.ofMinutes(15); // a run older than this is treated as stuck
    public static final Duration MIN_MANUAL_GAP = Duration.ofMinutes(5); // "Sync now" can't start syncs closer than this
    public static final Duration MIN_SCHEDULED_GAP = Duration.ofHours(1); // failed scheduled syncs retry at most hourly
    public static final Duration FULL_SYNC_EVERY = Duration.ofMinutes(30); // EduSoft, IUPay, Blackboard and Outlook

    /** reason: never / running / requested / too_soon / interval / not_due. */
    public record Decision(boolean due, String reason) {
    }

    public static Decision decide(LocalDateTime now, LocalDateTime syncRequestedAt,
            LocalDateTime lastAttemptStartedAt, LocalDateTime lastSuccessStartedAt, LocalDateTime runningSince) {
        if (runningSince != null && Duration.between(runningSince, now).compareTo(RUN_TIMEOUT) < 0) {
            return new Decision(false, "running");
        }

        Duration sinceAttempt = lastAttemptStartedAt == null ? null : Duration.between(lastAttemptStartedAt, now);

        if (syncRequestedAt != null
                && (lastAttemptStartedAt == null || syncRequestedAt.isAfter(lastAttemptStartedAt))) {
            if (sinceAttempt != null && sinceAttempt.compareTo(MIN_MANUAL_GAP) < 0) {
                return new Decision(false, "too_soon");
            }
            return new Decision(true, "requested");
        }

        if (lastAttemptStartedAt == null) {
            return new Decision(true, "never");
        }

        if (lastSuccessStartedAt == null
                || Duration.between(lastSuccessStartedAt, now).compareTo(FULL_SYNC_EVERY) >= 0) {
            // Only a failed attempt waits an hour before the next try; a success is due again after 30 minutes.
            boolean lastFailed = lastSuccessStartedAt == null || lastAttemptStartedAt.isAfter(lastSuccessStartedAt);
            if (lastFailed && sinceAttempt.compareTo(MIN_SCHEDULED_GAP) < 0) {
                return new Decision(false, "too_soon");
            }
            return new Decision(true, "interval");
        }

        return new Decision(false, "not_due");
    }
}
