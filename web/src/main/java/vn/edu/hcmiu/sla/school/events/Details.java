package vn.edu.hcmiu.sla.school.events;

import java.time.LocalTime;

/** What the event form sets on an event: place and notes may be null; start and end are Vietnam times. */
public record Details(String title, String place, String notes, Occurrences.Rule rule, LocalTime start, LocalTime end) {
}
