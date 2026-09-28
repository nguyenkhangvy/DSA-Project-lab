package vn.edu.hcmiu.sla.school;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import jakarta.persistence.EntityManager;

import vn.edu.hcmiu.sla.auth.AppUser;
import vn.edu.hcmiu.sla.auth.User;
import vn.edu.hcmiu.sla.school.model.SchoolBbAnnouncement;
import vn.edu.hcmiu.sla.school.model.SchoolBbAssignment;
import vn.edu.hcmiu.sla.school.model.SchoolBbCourse;
import vn.edu.hcmiu.sla.school.model.SchoolBbMaterial;
import vn.edu.hcmiu.sla.school.model.SchoolCourse;
import vn.edu.hcmiu.sla.school.model.SchoolExam;
import vn.edu.hcmiu.sla.school.model.SchoolMail;
import vn.edu.hcmiu.sla.school.model.SchoolMailChange;
import vn.edu.hcmiu.sla.school.model.SchoolTuition;

/** Rows for School page tests, saved straight into the test database. All times are UTC. */
public final class SchoolTestData {

    public static final String BB = "https://blackboard.hcmiu.edu.vn/x";

    /** A class: start and end in UTC, and its room. */
    public record Meeting(LocalDateTime start, LocalDateTime end, String room) {
    }

    private final EntityManager db;

    public SchoolTestData(EntityManager db) {
        this.db = db;
    }

    /** A new account; the returned principal logs it in with {@code user(...)} in MockMvc. */
    public AppUser user(String email) {
        User user = new User(email, "An", "x", LocalDateTime.of(2026, 9, 1, 0, 0));
        db.persist(user);
        return AppUser.of(user);
    }

    public void course(AppUser user, String code, String name, Meeting... meetings) {
        SchoolCourse course = new SchoolCourse(user.id(), "20261", code, name, null, null, null);
        for (Meeting m : meetings) {
            course.addMeeting(m.start(), m.end(), m.room());
        }
        db.persist(course);
        db.flush();
    }

    public void exam(AppUser user, String code, String name, LocalDateTime start, String room, String type) {
        db.persist(new SchoolExam(user.id(), "20261", code, name, type, start, null, room, null));
        db.flush();
    }

    public void tuition(AppUser user, long balance, LocalDate dueDate, String statusText) {
        db.persist(new SchoolTuition(user.id(), "20261", balance, 0, balance, dueDate, statusText, List.of()));
        db.flush();
    }

    /** A Blackboard course; add announcements, assignments and materials to it before calling {@link #save}. */
    public SchoolBbCourse bbCourse(AppUser user, String code, String name) {
        return new SchoolBbCourse(user.id(), "_" + code + "_" + name.length(), code, name, BB);
    }

    public SchoolBbCourse announce(SchoolBbCourse course, String title, String text, LocalDateTime postedAt) {
        course.getAnnouncements().add(new SchoolBbAnnouncement(course, "_a" + course.getAnnouncements().size(), title,
                text, postedAt, BB));
        return course;
    }

    public SchoolBbCourse assign(SchoolBbCourse course, String name, LocalDateTime dueAt, String status, Double score,
            Double pointsPossible, String feedback) {
        course.getAssignments().add(new SchoolBbAssignment(course, "_x" + course.getAssignments().size(), name, dueAt,
                pointsPossible, score, null, status, feedback, BB));
        return course;
    }

    public SchoolBbCourse material(SchoolBbCourse course, String title, String kind, String path,
            LocalDateTime createdAt) {
        course.getMaterials().add(new SchoolBbMaterial(course, "_m" + course.getMaterials().size(), title, kind, path,
                createdAt, BB));
        return course;
    }

    /** Saves a Blackboard course with everything added to it; returns its id (its page is /school/courses/{id}). */
    public Integer save(SchoolBbCourse course) {
        db.persist(course);
        db.flush();
        return course.getId();
    }

    /**
     * A lecturer's email as the laptop sorted it; add class changes with {@link #emailChange}, then {@link #save}.
     * blackboardTitle: set when it is Blackboard's copy of an announcement.
     */
    public SchoolMail lecturerEmail(AppUser user, String key, LocalDateTime receivedAt, String blackboardTitle) {
        return new SchoolMail(user.id(), key, "00A1", null, receivedAt, "Tran Van An", "tvan@hcmiu.edu.vn",
                blackboardTitle != null ? "Course_S1: " + blackboardTitle : "Class notice", List.of("class"), true,
                List.of(), true, blackboardTitle);
    }

    public SchoolMail emailChange(SchoolMail mail, String code, String kind, LocalDate day, LocalTime start,
            LocalTime end, String room) {
        mail.getChanges().add(new SchoolMailChange(mail, code, kind, day, start, end, room));
        return mail;
    }

    public void save(SchoolMail mail) {
        db.persist(mail);
        db.flush();
    }
}
