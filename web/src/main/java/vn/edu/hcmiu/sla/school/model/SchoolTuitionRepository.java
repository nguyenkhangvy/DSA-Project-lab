package vn.edu.hcmiu.sla.school.model;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolTuitionRepository extends JpaRepository<SchoolTuition, Integer> {

    Optional<SchoolTuition> findByUserIdAndTermCode(Integer userId, String termCode);

    @Modifying
    @Query("delete from SchoolTuition t where t.userId = :userId and t.termCode = :termCode")
    void deleteTerm(Integer userId, String termCode);

    List<SchoolTuition> findByUserIdOrderByTermCodeDesc(Integer userId);
}
