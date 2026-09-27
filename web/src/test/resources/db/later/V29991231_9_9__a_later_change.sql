-- For MigrationTest only: a later migration like a teammate's first one (see the README), with a key to users.
CREATE TABLE later_items (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_later_items_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
