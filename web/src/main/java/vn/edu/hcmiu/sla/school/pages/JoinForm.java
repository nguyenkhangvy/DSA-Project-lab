package vn.edu.hcmiu.sla.school.pages;

import java.util.ArrayList;
import java.util.List;

/**
 * Join…: the sessions ticked (by MailSessions.Line.id, e.g. "2026-09-29T13:30"), one session added by hand (day,
 * start and an optional end; day "" means none), and an optional place.
 */
public class JoinForm {

    private List<String> sessions = new ArrayList<>();
    private String day = "";
    private String start = "";
    private String end = "";
    private String place = "";

    public JoinForm() {
    }

    JoinForm(List<String> sessions, String place) {
        this.sessions = new ArrayList<>(sessions);
        this.place = place;
    }

    public List<String> getSessions() {
        return sessions;
    }

    public void setSessions(List<String> sessions) {
        this.sessions = sessions == null ? new ArrayList<>() : sessions;
    }

    public String getDay() {
        return day;
    }

    public void setDay(String day) {
        this.day = day == null ? "" : day.strip();
    }

    public String getStart() {
        return start;
    }

    public void setStart(String start) {
        this.start = start == null ? "" : start.strip();
    }

    public String getEnd() {
        return end;
    }

    public void setEnd(String end) {
        this.end = end == null ? "" : end.strip();
    }

    public String getPlace() {
        return place;
    }

    public void setPlace(String place) {
        this.place = place == null ? "" : place.strip();
    }
}
