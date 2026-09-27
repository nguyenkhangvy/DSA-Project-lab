package vn.edu.hcmiu.sla.school.model;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SchoolSyncDeviceRepository extends JpaRepository<SchoolSyncDevice, Integer> {

    Optional<SchoolSyncDevice> findByTokenHashAndRevokedAtIsNull(String tokenHash);
}
