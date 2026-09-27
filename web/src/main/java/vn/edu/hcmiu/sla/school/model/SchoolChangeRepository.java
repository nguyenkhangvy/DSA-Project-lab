package vn.edu.hcmiu.sla.school.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SchoolChangeRepository extends JpaRepository<SchoolChange, Integer> {

    List<SchoolChange> findBySyncRunIdOrderById(Integer syncRunId);

    List<SchoolChange> findTop10ByUserIdOrderByIdDesc(Integer userId);
}
