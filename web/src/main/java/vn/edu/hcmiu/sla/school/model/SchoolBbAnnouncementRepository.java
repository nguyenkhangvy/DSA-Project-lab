package vn.edu.hcmiu.sla.school.model;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolBbAnnouncementRepository extends JpaRepository<SchoolBbAnnouncement, Integer> {

    List<SchoolBbAnnouncement> findByUserIdOrderById(Integer userId);

    @Modifying
    @Query("delete from SchoolBbAnnouncement a where a.userId = :userId")
    void deleteAllOfUser(Integer userId);

    @Query("select a from SchoolBbAnnouncement a join fetch a.course where a.userId = :userId")
    List<SchoolBbAnnouncement> findWithCourse(Integer userId);

    /** The newest announcements first, with their course. */
    @Query("select a from SchoolBbAnnouncement a join fetch a.course where a.userId = :userId "
            + "order by a.postedAt desc, a.id desc")
    List<SchoolBbAnnouncement> findLatest(Integer userId, Limit limit);

    List<SchoolBbAnnouncement> findByCourseIdOrderById(Integer courseId);
}
