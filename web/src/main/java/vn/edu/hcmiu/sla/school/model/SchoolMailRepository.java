package vn.edu.hcmiu.sla.school.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolMailRepository extends JpaRepository<SchoolMail, Integer> {

    /** Newest first. */
    List<SchoolMail> findByUserIdOrderByReceivedAtDescIdDesc(Integer userId);

    @Modifying
    @Query("delete from SchoolMail m where m.userId = :userId")
    void deleteAllOfUser(Integer userId);
}
