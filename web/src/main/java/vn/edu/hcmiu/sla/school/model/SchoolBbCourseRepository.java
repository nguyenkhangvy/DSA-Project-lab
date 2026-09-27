package vn.edu.hcmiu.sla.school.model;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolBbCourseRepository extends JpaRepository<SchoolBbCourse, Integer> {

    List<SchoolBbCourse> findByUserIdOrderById(Integer userId);

    /** Deletes the user's Blackboard courses with their announcements, assignments and materials. */
    @Modifying
    @Query("delete from SchoolBbCourse c where c.userId = :userId")
    void deleteAllOfUser(Integer userId);

    Optional<SchoolBbCourse> findByIdAndUserId(Integer id, Integer userId);

    /** The user's courses by name, with how many announcements and assignments each has. */
    @Query("select new vn.edu.hcmiu.sla.school.model.CourseCard(c.id, c.name, c.courseCode, "
            + "(select count(a) from SchoolBbAnnouncement a where a.course = c), "
            + "(select count(x) from SchoolBbAssignment x where x.course = c)) "
            + "from SchoolBbCourse c where c.userId = :userId order by c.name, c.id")
    List<CourseCard> findCards(Integer userId);
}
