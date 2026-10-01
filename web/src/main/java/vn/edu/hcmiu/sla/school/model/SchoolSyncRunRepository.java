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

    Optional<SchoolSyncRun> findFirstByUserIdAndStatusNotOrderByIdDesc(Integer userId, String status);

    /** The 10 newest full runs and the newest mail-only run, newest first: what the status box and Mailbox read. */
    default List<SchoolSyncRun> recentRuns(Integer userId) {
        List<SchoolSyncRun> recent = new ArrayList<>(
                findTop10ByUserIdAndTriggerNotOrderByStartedAtDescIdDesc(userId, SchoolSyncRun.MAIL));
        findFirstByUserIdAndTriggerOrderByStartedAtDescIdDesc(userId, SchoolSyncRun.MAIL).ifPresent(recent::add);
        recent.sort(Comparator.comparing(SchoolSyncRun::getStartedAt, Comparator.reverseOrder())
                .thenComparing(SchoolSyncRun::getId, Comparator.reverseOrder()));
        return recent;
    }
}
