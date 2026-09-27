package vn.edu.hcmiu.sla.school.model;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/** A Blackboard course, with its announcements, assignments and materials. */
@Entity
@Table(name = "school_bb_courses")
public class SchoolBbCourse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "bb_id", nullable = false, length = 64)
    private String bbId;

    @Column(name = "course_code", length = 20)
    private String courseCode;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, length = 500)
    private String url;

    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SchoolBbAnnouncement> announcements = new ArrayList<>();

    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SchoolBbAssignment> assignments = new ArrayList<>();

    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SchoolBbMaterial> materials = new ArrayList<>();

    protected SchoolBbCourse() {
    }

    public SchoolBbCourse(Integer userId, String bbId, String courseCode, String name, String url) {
        this.userId = userId;
        this.bbId = bbId;
        this.courseCode = courseCode;
        this.name = name;
        this.url = url;
    }

    public Integer getId() {
        return id;
    }

    public Integer getUserId() {
        return userId;
    }

    public String getBbId() {
        return bbId;
    }

    public String getCourseCode() {
        return courseCode;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public List<SchoolBbAnnouncement> getAnnouncements() {
        return announcements;
    }

    public List<SchoolBbAssignment> getAssignments() {
        return assignments;
    }

    public List<SchoolBbMaterial> getMaterials() {
        return materials;
    }
}
