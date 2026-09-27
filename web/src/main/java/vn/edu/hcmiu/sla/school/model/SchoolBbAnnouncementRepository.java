package vn.edu.hcmiu.sla.school.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolBbAnnouncementRepository extends JpaRepository<SchoolBbAnnouncement, Integer> {

    List<SchoolBbAnnouncement> findByUserIdOrderById(Integer userId);

    @Modifying
    @Query("delete from SchoolBbAnnouncement a where a.userId = :userId")
    void deleteAllOfUser(Integer userId);
}
