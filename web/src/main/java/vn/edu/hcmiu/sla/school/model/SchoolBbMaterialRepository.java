package vn.edu.hcmiu.sla.school.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolBbMaterialRepository extends JpaRepository<SchoolBbMaterial, Integer> {

    List<SchoolBbMaterial> findByUserIdOrderById(Integer userId);

    @Modifying
    @Query("delete from SchoolBbMaterial m where m.userId = :userId")
    void deleteAllOfUser(Integer userId);

    List<SchoolBbMaterial> findByCourseIdOrderById(Integer courseId);
}
