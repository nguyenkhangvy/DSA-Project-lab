package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolBbAssignmentRepository extends JpaRepository<SchoolBbAssignment, Integer> {

    List<SchoolBbAssignment> findByUserIdOrderById(Integer userId);

    @Modifying
    @Query("delete from SchoolBbAssignment a where a.userId = :userId")
    void deleteAllOfUser(Integer userId);

    /** Deadlines in [start, end), soonest first, with their course. */
    @Query("select a from SchoolBbAssignment a join fetch a.course where a.userId = :userId "
            + "and a.dueAt >= :start and a.dueAt < :end order by a.dueAt, a.id")
    List<SchoolBbAssignment> findDue(Integer userId, LocalDateTime start, LocalDateTime end);

    /** Not handed in yet and due since the given time, soonest first, with their course. */
    @Query("select a from SchoolBbAssignment a join fetch a.course where a.userId = :userId "
            + "and a.status = 'not_graded' and a.dueAt >= :since order by a.dueAt, a.id")
    List<SchoolBbAssignment> findToSubmit(Integer userId, LocalDateTime since);

    List<SchoolBbAssignment> findByCourseIdOrderById(Integer courseId);
}
