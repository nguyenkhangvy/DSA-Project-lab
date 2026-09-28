package vn.edu.hcmiu.sla.school.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolMailChangeRepository extends JpaRepository<SchoolMailChange, Integer> {

    /** A user's class changes from email, with their email. */
    @Query("select c from SchoolMailChange c join fetch c.mail m where m.userId = :userId")
    List<SchoolMailChange> findOfUser(Integer userId);

    @Modifying
    @Query("delete from SchoolMailChange c where c.mail.id in (select m.id from SchoolMail m where m.userId = :userId)")
    void deleteAllOfUser(Integer userId);
}
