package vn.edu.hcmiu.sla.school.model;

/** A Blackboard course on the Courses page: its name and how much it holds. */
public record CourseCard(Integer id, String name, String courseCode, long announcements, long assignments) {
}
