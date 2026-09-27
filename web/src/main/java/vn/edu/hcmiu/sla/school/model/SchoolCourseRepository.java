package vn.edu.hcmiu.sla.school.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolCourseRepository extends JpaRepository<SchoolCourse, Integer> {

    boolean existsByUserIdAndTermCode(Integer userId, String termCode);

    /** Deletes a term's courses; delete their classes first. */
    @Modifying
    @Query("delete from SchoolCourse c where c.userId = :userId and c.termCode = :termCode")
    void deleteTerm(Integer userId, String termCode);
}
