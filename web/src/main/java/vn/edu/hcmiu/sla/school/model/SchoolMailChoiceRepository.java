package vn.edu.hcmiu.sla.school.model;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolMailChoiceRepository extends JpaRepository<SchoolMailChoice, Integer> {

    List<SchoolMailChoice> findByUserId(Integer userId);

    Optional<SchoolMailChoice> findByUserIdAndMailKey(Integer userId, String mailKey);

    /** Choices for emails that are no longer in the Inbox. */
    @Modifying
    @Query("delete from SchoolMailChoice c where c.userId = :userId and c.mailKey not in :keep")
    void deleteOthers(Integer userId, Collection<String> keep);

    @Modifying
    @Query("delete from SchoolMailChoice c where c.userId = :userId")
    void deleteAllOfUser(Integer userId);
}
