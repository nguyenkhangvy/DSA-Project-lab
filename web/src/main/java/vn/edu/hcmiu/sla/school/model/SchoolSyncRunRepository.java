package vn.edu.hcmiu.sla.school.model;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SchoolSyncRunRepository extends JpaRepository<SchoolSyncRun, Integer> {

    Optional<SchoolSyncRun> findFirstByUserIdOrderByStartedAtDescIdDesc(Integer userId);

    Optional<SchoolSyncRun> findFirstByUserIdAndStatusInOrderByStartedAtDescIdDesc(Integer userId,
            Collection<String> statuses);

    List<SchoolSyncRun> findByUserIdAndStatus(Integer userId, String status);

    List<SchoolSyncRun> findTop10ByUserIdOrderByStartedAtDescIdDesc(Integer userId);
}
