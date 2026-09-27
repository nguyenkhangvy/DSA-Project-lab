package vn.edu.hcmiu.sla.school.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolBbCourseRepository extends JpaRepository<SchoolBbCourse, Integer> {

    List<SchoolBbCourse> findByUserIdOrderById(Integer userId);

    /** Deletes the user's Blackboard courses with their announcements, assignments and materials. */
    @Modifying
    @Query("delete from SchoolBbCourse c where c.userId = :userId")
    void deleteAllOfUser(Integer userId);
}
