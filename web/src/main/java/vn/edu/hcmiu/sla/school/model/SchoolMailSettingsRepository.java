package vn.edu.hcmiu.sla.school.model;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SchoolMailSettingsRepository extends JpaRepository<SchoolMailSettings, Integer> {

    /** Whether opening an email marks it Done for this user: on unless they turned it off. */
    default boolean autoDone(Integer userId) {
        return findById(userId).map(SchoolMailSettings::isAutoDone).orElse(true);
    }
}
