package vn.edu.hcmiu.sla.school.model;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SchoolSyncDeviceRepository extends JpaRepository<SchoolSyncDevice, Integer> {

    Optional<SchoolSyncDevice> findByTokenHashAndRevokedAtIsNull(String tokenHash);

    /** The devices that can still sync, oldest first. Cancelled ones stay (sync history refers to them). */
    List<SchoolSyncDevice> findByUserIdAndRevokedAtIsNullOrderByCreatedAtAscIdAsc(Integer userId);

    Optional<SchoolSyncDevice> findByIdAndUserId(Integer id, Integer userId);
}
