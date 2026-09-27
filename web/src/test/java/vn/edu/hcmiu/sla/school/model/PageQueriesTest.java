package vn.edu.hcmiu.sla.school.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import jakarta.persistence.EntityManager;

import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.annotation.Transactional;

import vn.edu.hcmiu.sla.auth.AppUser;
import vn.edu.hcmiu.sla.school.SchoolTestData;
import vn.edu.hcmiu.sla.school.SchoolTestData.Meeting;

/**
 * The queries behind the School pages load what their templates show next to each row. The site runs
 * with open-in-view off, so a template reading a course that wasn't loaded fails, but only in real
 * requests: page tests share the test's cache, where the course is always there. These tests clear the
 * cache first and check what each query really loaded.
 */
@SpringBootTest
@Transactional
class PageQueriesTest {

    static final LocalDateTime LONG_AGO = LocalDateTime.of(2000, 1, 1, 0, 0);
    static final LocalDateTime FAR_AHEAD = LocalDateTime.of(2100, 1, 1, 0, 0);

    @Autowired
    EntityManager db;

    @Autowired
    SchoolClassMeetingRepository meetings;

    @Autowired
    SchoolCourseRepository courses;

    @Autowired
    SchoolBbAnnouncementRepository announcements;

    @Autowired
    SchoolBbAssignmentRepository assignments;

    AppUser an;

    @BeforeEach
    void rowsInTheDatabaseOnly() {
        SchoolTestData data = new SchoolTestData(db);
        an = data.user("an@example.com");
        data.course(an, "IT093IU", "Web Application Development",
                new Meeting(LocalDateTime.of(2026, 9, 29, 1, 0), LocalDateTime.of(2026, 9, 29, 3, 30), "A2.508"));
        SchoolBbCourse course = data.bbCourse(an, "IT093IU", "Web Application Development");
        data.announce(course, "No class on Thursday", "Class is cancelled.", LocalDateTime.of(2026, 9, 28, 2, 0));
        data.assign(course, "Lab 3", LocalDateTime.of(2026, 10, 2, 16, 59), "not_graded", null, null, null);
        data.save(course);
        db.clear(); // from here on, only what a query loads is in memory
    }

    @Test
    void toSubmitLoadsEachAssignmentsCourse() {
        assertThat(assignments.findToSubmit(an.id(), LONG_AGO)).isNotEmpty()
                .allSatisfy(a -> assertThat(Hibernate.isInitialized(a.getCourse())).isTrue());
    }

    @Test
    void deadlinesLoadEachAssignmentsCourse() {
        assertThat(assignments.findDue(an.id(), LONG_AGO, FAR_AHEAD)).isNotEmpty()
                .allSatisfy(a -> assertThat(Hibernate.isInitialized(a.getCourse())).isTrue());
    }

    @Test
    void latestAnnouncementsLoadTheirCourse() {
        assertThat(announcements.findLatest(an.id(), Limit.of(3))).isNotEmpty()
                .allSatisfy(a -> assertThat(Hibernate.isInitialized(a.getCourse())).isTrue());
    }

    @Test
    void announcementsForClassChangesLoadTheirCourse() {
        assertThat(announcements.findWithCourse(an.id())).isNotEmpty()
                .allSatisfy(a -> assertThat(Hibernate.isInitialized(a.getCourse())).isTrue());
    }

    @Test
    void classesLoadTheirCourse() {
        assertThat(meetings.findStarting(an.id(), LONG_AGO, FAR_AHEAD)).isNotEmpty()
                .allSatisfy(m -> assertThat(Hibernate.isInitialized(m.getCourse())).isTrue());
    }

    @Test
    void timetableCoursesLoadTheirClasses() {
        assertThat(courses.findWithMeetings(an.id())).isNotEmpty()
                .allSatisfy(c -> assertThat(Hibernate.isInitialized(c.getMeetings())).isTrue());
    }
}
