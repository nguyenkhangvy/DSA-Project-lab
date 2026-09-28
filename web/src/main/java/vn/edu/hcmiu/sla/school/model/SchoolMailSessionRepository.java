package vn.edu.hcmiu.sla.school.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolMailSessionRepository extends JpaRepository<SchoolMailSession, Integer> {

    /** A user's sessions from email, with their email, in time order. */
    @Query("select s from SchoolMailSession s join fetch s.mail m where m.userId = :userId order by s.day, s.start")
    List<SchoolMailSession> findOfUser(Integer userId);

    @Modifying
    @Query("delete from SchoolMailSession s where s.mail.id in (select m.id from SchoolMail m where m.userId = :userId)")
    void deleteAllOfUser(Integer userId);
}
