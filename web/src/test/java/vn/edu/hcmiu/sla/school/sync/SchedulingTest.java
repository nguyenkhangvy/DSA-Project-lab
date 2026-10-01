package vn.edu.hcmiu.sla.school.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** When the laptop syncs: full syncs every 30 minutes, Sync now, retries and running runs. */
class SchedulingTest {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 12, 0);

    /** "11:55" on 1 October 2026, or null for an empty cell. */
    static LocalDateTime t(String hhmm) {
        return hhmm == null ? null : LocalDateTime.of(NOW.toLocalDate(), LocalTime.parse(hhmm));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            first ever check                                            |       |       |       |       | true  | never
            a sync is running                                           |       | 11:55 | 00:00 | 11:55 | false | running
            a run stuck for 20 minutes no longer blocks Sync now        | 11:50 | 11:40 | 00:00 | 11:40 | true  | requested
            Sync now pressed after the last attempt                     | 11:59 | 11:00 | 11:00 |       | true  | requested
            Sync now pressed but the last attempt was 4 minutes ago     | 11:59 | 11:56 | 09:00 |       | false | too_soon
            Sync now pressed exactly 5 minutes after the last attempt   | 11:59 | 11:55 | 09:00 |       | true  | requested
            an old Sync now request that was already served             | 11:40 | 11:45 | 11:45 |       | false | not_due
            30 minutes passed since the last success                    |       | 11:30 | 11:30 |       | true  | interval
            30 minutes not yet passed                                   |       | 11:31 | 11:31 |       | false | not_due
            a success 30 minutes ago is due although under an hour ago  |       | 11:30 | 11:30 |       | true  | interval
            a failed retry 20 minutes after the last success waits      |       | 11:40 | 11:20 |       | false | too_soon
            last sync failed 30 minutes ago: wait for the hourly retry  |       | 11:30 | 00:00 |       | false | too_soon
            last sync failed an hour ago: retry                         |       | 11:00 | 00:00 |       | true  | interval
            never succeeded, last attempt failed 2 hours ago            |       | 10:00 |       |       | true  | interval
            """)
    void decide(String name, String requested, String lastAttempt, String lastSuccess, String runningSince,
            boolean due, String reason) {
        Scheduling.Decision decision = Scheduling.decide(NOW, t(requested), t(lastAttempt), t(lastSuccess),
                t(runningSince));

        assertThat(decision).isEqualTo(new Scheduling.Decision(due, reason));
    }

    @Test
    void aFullSyncIsDueEvery30Minutes() {
        assertThat(Scheduling.FULL_SYNC_EVERY).hasMinutes(30);
    }
}
