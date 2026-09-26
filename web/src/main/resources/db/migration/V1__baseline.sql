-- The tables the Python site had created (Alembic revision 7d2f4b9c1e30).
-- On that existing database Flyway only records this file as done (baseline-on-migrate);
-- on an empty database (a teammate's laptop, the tests) it creates them.
-- Written to run on MySQL 8 and on H2 in MySQL mode.

CREATE TABLE users (
    id INT NOT NULL AUTO_INCREMENT,
    email VARCHAR(255) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email)
);

CREATE TABLE school_sync_devices (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    name VARCHAR(100) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    created_at DATETIME NOT NULL,
    last_seen_at DATETIME NULL,
    revoked_at DATETIME NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_school_sync_devices_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_school_sync_devices_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX ix_school_sync_devices_user_id ON school_sync_devices (user_id);

CREATE TABLE school_sync_settings (
    user_id INT NOT NULL,
    interval_hours INT NOT NULL,
    sync_requested_at DATETIME NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_school_sync_settings_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE school_sync_runs (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    device_id INT NULL,
    `trigger` VARCHAR(20) NOT NULL,
    started_at DATETIME NOT NULL,
    finished_at DATETIME NULL,
    status VARCHAR(10) NOT NULL,
    error_code VARCHAR(40) NULL,
    error_message VARCHAR(500) NULL,
    sections JSON NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_school_sync_runs_device FOREIGN KEY (device_id) REFERENCES school_sync_devices (id) ON DELETE SET NULL,
    CONSTRAINT fk_school_sync_runs_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX ix_school_sync_runs_user_id ON school_sync_runs (user_id);
CREATE INDEX ix_school_sync_runs_user_started ON school_sync_runs (user_id, started_at);

CREATE TABLE school_changes (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    sync_run_id INT NOT NULL,
    section VARCHAR(20) NOT NULL,
    kind VARCHAR(10) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    created_at DATETIME NOT NULL,
    seen_at DATETIME NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_school_changes_run FOREIGN KEY (sync_run_id) REFERENCES school_sync_runs (id) ON DELETE CASCADE,
    CONSTRAINT fk_school_changes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX ix_school_changes_sync_run_id ON school_changes (sync_run_id);
CREATE INDEX ix_school_changes_user_id ON school_changes (user_id);

CREATE TABLE school_courses (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    term_code VARCHAR(20) NOT NULL,
    course_code VARCHAR(20) NOT NULL,
    course_name VARCHAR(255) NOT NULL,
    group_code VARCHAR(20) NULL,
    credits DECIMAL(4, 1) NULL,
    lecturer VARCHAR(255) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_school_courses_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX ix_school_courses_user_id ON school_courses (user_id);

CREATE TABLE school_class_meetings (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    course_id INT NOT NULL,
    start_at DATETIME NOT NULL,
    end_at DATETIME NOT NULL,
    room VARCHAR(50) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_school_class_meetings_course FOREIGN KEY (course_id) REFERENCES school_courses (id) ON DELETE CASCADE,
    CONSTRAINT fk_school_class_meetings_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX ix_school_class_meetings_course_id ON school_class_meetings (course_id);
CREATE INDEX ix_school_class_meetings_user_id ON school_class_meetings (user_id);
CREATE INDEX ix_school_class_meetings_user_start ON school_class_meetings (user_id, start_at);

CREATE TABLE school_exams (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    term_code VARCHAR(20) NOT NULL,
    course_code VARCHAR(20) NOT NULL,
    course_name VARCHAR(255) NOT NULL,
    exam_type VARCHAR(10) NOT NULL,
    start_at DATETIME NOT NULL,
    duration_min INT NULL,
    room VARCHAR(50) NULL,
    notes VARCHAR(500) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_school_exams_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX ix_school_exams_user_id ON school_exams (user_id);
CREATE INDEX ix_school_exams_user_start ON school_exams (user_id, start_at);

CREATE TABLE school_tuition (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    term_code VARCHAR(20) NOT NULL,
    amount_due BIGINT NOT NULL,
    amount_paid BIGINT NOT NULL,
    balance BIGINT NOT NULL,
    due_date DATE NULL,
    status_text VARCHAR(255) NULL,
    items JSON NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_school_tuition_user_term UNIQUE (user_id, term_code),
    CONSTRAINT fk_school_tuition_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX ix_school_tuition_user_id ON school_tuition (user_id);

CREATE TABLE school_events (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    title VARCHAR(200) NOT NULL,
    start_at DATETIME NOT NULL,
    end_at DATETIME NOT NULL,
    all_day BOOLEAN NOT NULL,
    location VARCHAR(255) NULL,
    notes TEXT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_school_events_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX ix_school_events_user_id ON school_events (user_id);
CREATE INDEX ix_school_events_user_start ON school_events (user_id, start_at);

CREATE TABLE school_bb_courses (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    bb_id VARCHAR(64) NOT NULL,
    course_code VARCHAR(20) NULL,
    name VARCHAR(255) NOT NULL,
    url VARCHAR(500) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_school_bb_courses_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX ix_school_bb_courses_user_id ON school_bb_courses (user_id);

CREATE TABLE school_bb_announcements (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    course_id INT NOT NULL,
    bb_id VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL,
    text TEXT NOT NULL,
    posted_at DATETIME NULL,
    url VARCHAR(500) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_school_bb_announcements_course FOREIGN KEY (course_id) REFERENCES school_bb_courses (id) ON DELETE CASCADE,
    CONSTRAINT fk_school_bb_announcements_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX ix_school_bb_announcements_course_id ON school_bb_announcements (course_id);
CREATE INDEX ix_school_bb_announcements_user_id ON school_bb_announcements (user_id);

CREATE TABLE school_bb_assignments (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    course_id INT NOT NULL,
    bb_id VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    due_at DATETIME NULL,
    points_possible DOUBLE NULL,
    score DOUBLE NULL,
    grade_text VARCHAR(50) NULL,
    status VARCHAR(20) NOT NULL,
    feedback TEXT NULL,
    url VARCHAR(500) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_school_bb_assignments_course FOREIGN KEY (course_id) REFERENCES school_bb_courses (id) ON DELETE CASCADE,
    CONSTRAINT fk_school_bb_assignments_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX ix_school_bb_assignments_course_id ON school_bb_assignments (course_id);
CREATE INDEX ix_school_bb_assignments_user_due ON school_bb_assignments (user_id, due_at);
CREATE INDEX ix_school_bb_assignments_user_id ON school_bb_assignments (user_id);

CREATE TABLE school_bb_materials (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    course_id INT NOT NULL,
    bb_id VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL,
    kind VARCHAR(10) NOT NULL,
    path VARCHAR(500) NOT NULL,
    created_at DATETIME NULL,
    url VARCHAR(500) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_school_bb_materials_course FOREIGN KEY (course_id) REFERENCES school_bb_courses (id) ON DELETE CASCADE,
    CONSTRAINT fk_school_bb_materials_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX ix_school_bb_materials_course_id ON school_bb_materials (course_id);
CREATE INDEX ix_school_bb_materials_user_id ON school_bb_materials (user_id);
