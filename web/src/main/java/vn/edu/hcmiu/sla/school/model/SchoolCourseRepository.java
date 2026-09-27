package vn.edu.hcmiu.sla.school.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolCourseRepository extends JpaRepository<SchoolCourse, Integer> {

    boolean existsByUserIdAndTermCode(Integer userId, String termCode);

    /** Deletes a term's courses; delete their classes first. */
    @Modifying
    @Query("delete from SchoolCourse c where c.userId = :userId and c.termCode = :termCode")
    void deleteTerm(Integer userId, String termCode);

    /** The user's timetable courses with their classes. */
    @Query("select c from SchoolCourse c left join fetch c.meetings m where c.userId = :userId order by c.id, m.id")
    List<SchoolCourse> findWithMeetings(Integer userId);
}
