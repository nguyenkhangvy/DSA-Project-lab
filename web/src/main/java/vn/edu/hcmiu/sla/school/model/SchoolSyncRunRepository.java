package vn.edu.hcmiu.sla.school.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SchoolSyncRunRepository extends JpaRepository<SchoolSyncRun, Integer> {

    Optional<SchoolSyncRun> findFirstByUserIdOrderByStartedAtDescIdDesc(Integer userId);

    Optional<SchoolSyncRun> findFirstByUserIdAndStatusInOrderByStartedAtDescIdDesc(Integer userId,
            Collection<String> statuses);

    List<SchoolSyncRun> findByUserIdAndStatus(Integer userId, String status);

    List<SchoolSyncRun> findTop10ByUserIdOrderByStartedAtDescIdDesc(Integer userId);

    Optional<SchoolSyncRun> findFirstByUserIdAndTriggerNotOrderByStartedAtDescIdDesc(Integer userId, String trigger);

    Optional<SchoolSyncRun> findFirstByUserIdAndTriggerNotAndStatusInOrderByStartedAtDescIdDesc(Integer userId,
            String trigger, Collection<String> statuses);

    List<SchoolSyncRun> findTop10ByUserIdAndTriggerNotOrderByStartedAtDescIdDesc(Integer userId, String trigger);

    Optional<SchoolSyncRun> findFirstByUserIdAndTriggerOrderByStartedAtDescIdDesc(Integer userId, String trigger);

    Optional<SchoolSyncRun> findFirstByUserIdOrderByIdDesc(Integer userId);

    /** The 10 newest full runs and the newest mail-only run, newest first: what the status box and Mailbox read. */
    default List<SchoolSyncRun> recentRuns(Integer userId) {
        List<SchoolSyncRun> recent = new ArrayList<>(
                findTop10ByUserIdAndTriggerNotOrderByStartedAtDescIdDesc(userId, SchoolSyncRun.MAIL));
        findFirstByUserIdAndTriggerOrderByStartedAtDescIdDesc(userId, SchoolSyncRun.MAIL).ifPresent(recent::add);
        recent.sort(Comparator.comparing(SchoolSyncRun::getStartedAt, Comparator.reverseOrder())
                .thenComparing(SchoolSyncRun::getId, Comparator.reverseOrder()));
        return recent;
    }

    /**
     * What live pages compare to know something new happened: it grows when a sync starts and again when it ends
     * (2 x the newest run's id, plus 1 once that run has finished), so the page can show "Syncing…" too. 0 before
     * the first run.
     */
    default int version(Integer userId) {
        return findFirstByUserIdOrderByIdDesc(userId)
                .map(run -> 2 * run.getId() + (run.getStatus().equals(SchoolSyncRun.RUNNING) ? 0 : 1)).orElse(0);
    }
}
