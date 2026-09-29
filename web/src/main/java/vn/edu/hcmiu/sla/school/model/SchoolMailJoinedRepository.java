package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SchoolMailJoinedRepository extends JpaRepository<SchoolMailJoined, Integer> {

    /** A user's joined sessions on the days [from, to], in time order. */
    List<SchoolMailJoined> findByUserIdAndDayBetweenOrderByDayAscStartAsc(Integer userId, LocalDate from, LocalDate to);

    /** A user's joined sessions from this day on, in time order. */
    List<SchoolMailJoined> findByUserIdAndDayGreaterThanEqualOrderByDayAscStartAsc(Integer userId, LocalDate from);

    /** The emails a user joined a session of. */
    @Query("select distinct j.mailKey from SchoolMailJoined j where j.userId = :userId")
    Set<String> mailKeysOf(Integer userId);

    /** A user's joined sessions of these emails (one card's keys), in time order. */
    List<SchoolMailJoined> findByUserIdAndMailKeyInOrderByDayAscStartAsc(Integer userId, Collection<String> mailKeys);
}
