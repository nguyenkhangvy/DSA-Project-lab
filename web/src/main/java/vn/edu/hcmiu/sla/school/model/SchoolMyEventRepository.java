package vn.edu.hcmiu.sla.school.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** The student's own events, always with their skipped days (pages read them outside a transaction). */
public interface SchoolMyEventRepository extends JpaRepository<SchoolMyEvent, Integer> {

    /** The events whose first and last day may put a day in [from, to], soonest first day first. */
    @Query("select distinct e from SchoolMyEvent e left join fetch e.skips "
            + "where e.userId = :userId and e.firstDay <= :to and e.lastDay >= :from order by e.firstDay, e.id")
    List<SchoolMyEvent> findOverlapping(Integer userId, LocalDate from, LocalDate to);

    @Query("select distinct e from SchoolMyEvent e left join fetch e.skips where e.userId = :userId "
            + "order by e.firstDay, e.id")
    List<SchoolMyEvent> findAllOfUser(Integer userId);

    @Query("select e from SchoolMyEvent e left join fetch e.skips where e.id = :id and e.userId = :userId")
    Optional<SchoolMyEvent> findOfUser(Integer id, Integer userId);
}
