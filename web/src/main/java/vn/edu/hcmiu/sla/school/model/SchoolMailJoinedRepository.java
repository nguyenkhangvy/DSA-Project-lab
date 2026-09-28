package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SchoolMailJoinedRepository extends JpaRepository<SchoolMailJoined, Integer> {

    /** A user's joined sessions on the days [from, to], in time order. */
    List<SchoolMailJoined> findByUserIdAndDayBetweenOrderByDayAscStartAsc(Integer userId, LocalDate from, LocalDate to);

    /** A user's joined sessions of these emails (one card's keys), in time order. */
    List<SchoolMailJoined> findByUserIdAndMailKeyInOrderByDayAscStartAsc(Integer userId, Collection<String> mailKeys);
}
