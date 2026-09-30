-- The student's own events on the Timetable (docs/superpowers/specs/2026-09-30-my-events-design.md, section 3.1).
-- One row per event holds its repeat rule; days and times are Vietnam time.
CREATE TABLE school_my_events (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    title VARCHAR(100) NOT NULL,
    place VARCHAR(100) NULL,
    notes VARCHAR(500) NULL,
    first_day DATE NOT NULL,
    last_day DATE NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    repeat_kind VARCHAR(6) NOT NULL,
    every_n INT NOT NULL,
    weekdays VARCHAR(20) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_school_my_events_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- The days the student skipped; they go with their event.
CREATE TABLE school_my_event_skips (
    id INT NOT NULL AUTO_INCREMENT,
    event_id INT NOT NULL,
    skip_day DATE NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (event_id, skip_day),
    CONSTRAINT fk_school_my_event_skips_event FOREIGN KEY (event_id) REFERENCES school_my_events (id) ON DELETE CASCADE
);
