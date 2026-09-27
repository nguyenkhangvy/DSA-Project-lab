package vn.edu.hcmiu.sla.school.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolExamRepository extends JpaRepository<SchoolExam, Integer> {

    List<SchoolExam> findByUserIdAndTermCode(Integer userId, String termCode);

    @Modifying
    @Query("delete from SchoolExam e where e.userId = :userId and e.termCode = :termCode")
    void deleteTerm(Integer userId, String termCode);
}
