package vn.edu.hcmiu.sla.school.model;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SchoolTuitionBillRepository extends JpaRepository<SchoolTuitionBill, Integer> {

    /** In the order IUPay listed them. */
    List<SchoolTuitionBill> findByUserIdOrderById(Integer userId);

    @Modifying
    @Query("delete from SchoolTuitionBill b where b.userId = :userId")
    void deleteAllOfUser(Integer userId);
}
