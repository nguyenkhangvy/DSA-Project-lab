package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolClassMeetingRepository extends JpaRepository<SchoolClassMeeting, Integer> {

    @Query("select m from SchoolClassMeeting m join fetch m.course c where c.userId = :userId and c.termCode = :termCode")
    List<SchoolClassMeeting> findTerm(Integer userId, String termCode);

    @Modifying
    @Query("delete from SchoolClassMeeting m where m.course.id in "
            + "(select c.id from SchoolCourse c where c.userId = :userId and c.termCode = :termCode)")
    void deleteTerm(Integer userId, String termCode);

    /** Classes starting in [start, end), with their course, in time order. */
    @Query("select m from SchoolClassMeeting m join fetch m.course where m.userId = :userId "
            + "and m.startAt >= :start and m.startAt < :end order by m.startAt, m.id")
    List<SchoolClassMeeting> findStarting(Integer userId, LocalDateTime start, LocalDateTime end);
}
