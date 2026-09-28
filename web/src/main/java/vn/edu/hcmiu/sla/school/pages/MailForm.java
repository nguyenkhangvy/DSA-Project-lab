package vn.edu.hcmiu.sla.school.pages;

import java.util.ArrayList;
import java.util.List;

/** Move to…: one or two categories, and whether the email is from a lecturer. category2 "" means none. */
public class MailForm {

    private String category1 = "";
    private String category2 = "";
    private boolean fromLecturer;

    public MailForm() {
    }

    MailForm(List<String> categories, boolean fromLecturer) {
        this.category1 = categories.isEmpty() ? "" : categories.get(0);
        this.category2 = categories.size() < 2 ? "" : categories.get(1);
        this.fromLecturer = fromLecturer;
    }

    /** The chosen categories, in the order chosen. */
    List<String> categories() {
        List<String> chosen = new ArrayList<>();
        chosen.add(category1);
        if (!category2.isEmpty()) {
            chosen.add(category2);
        }
        return chosen;
    }

    public String getCategory1() {
        return category1;
    }

    public void setCategory1(String category1) {
        this.category1 = category1 == null ? "" : category1.strip();
    }

    public String getCategory2() {
        return category2;
    }

    public void setCategory2(String category2) {
        this.category2 = category2 == null ? "" : category2.strip();
    }

    public boolean isFromLecturer() {
        return fromLecturer;
    }

    public void setFromLecturer(boolean fromLecturer) {
        this.fromLecturer = fromLecturer;
    }
}
