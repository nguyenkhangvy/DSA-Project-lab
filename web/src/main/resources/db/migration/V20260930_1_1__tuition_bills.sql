-- Tuition bills from IUPay (docs/superpowers/specs/2026-09-30-iupay-tuition-design.md, section 4.1).
-- Each good IUPay read replaces the student's bills. Amounts are VND; due_date and paid_on are Vietnam dates.
CREATE TABLE school_tuition_bills (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    bill_no VARCHAR(40) NOT NULL,
    term_code VARCHAR(20) NOT NULL,
    term_name VARCHAR(255) NULL,
    description VARCHAR(1000) NOT NULL,
    fee_type VARCHAR(255) NULL,
    amount BIGINT NOT NULL,
    discount BIGINT NOT NULL,
    fee BIGINT NOT NULL,
    status VARCHAR(12) NOT NULL,
    due_date DATE NULL,
    paid_on DATE NULL,
    channel VARCHAR(100) NULL,
    PRIMARY KEY (id),
    UNIQUE (user_id, bill_no),
    CONSTRAINT fk_school_tuition_bills_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- When IUPay was last read successfully (UTC); no row means never.
CREATE TABLE school_tuition_status (
    user_id INT NOT NULL,
    checked_at DATETIME NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_school_tuition_status_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
