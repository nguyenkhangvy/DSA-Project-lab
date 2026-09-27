package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolExamRepository extends JpaRepository<SchoolExam, Integer> {

    List<SchoolExam> findByUserIdAndTermCode(Integer userId, String termCode);

    @Modifying
    @Query("delete from SchoolExam e where e.userId = :userId and e.termCode = :termCode")
    void deleteTerm(Integer userId, String termCode);

    /** Exams starting in [start, end), in time order. */
    @Query("select e from SchoolExam e where e.userId = :userId and e.startAt >= :start and e.startAt < :end "
            + "order by e.startAt, e.id")
    List<SchoolExam> findStarting(Integer userId, LocalDateTime start, LocalDateTime end);
}
