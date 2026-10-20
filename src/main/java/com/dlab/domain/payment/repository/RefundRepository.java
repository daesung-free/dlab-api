package com.dlab.domain.payment.repository;

import com.dlab.domain.payment.entity.Refund;
import com.dlab.domain.payment.entity.RefundStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    /**
     * 그 청구에 <b>살아 있는</b> 환불이 있는가.
     *
     * <p>★ {@code FAILED} 는 제외한다 — 실패분은 고쳐서 <b>다시 걸 수 있어야</b> 한다.
     * 이력은 남기고(「시도했다」는 사실이다) 재시도만 허용한다.
     */
    @Query("""
            SELECT r FROM Refund r
            WHERE r.billing.id = :billingId
              AND r.deleted = false
              AND r.status <> com.dlab.domain.payment.entity.RefundStatus.FAILED
            """)
    Optional<Refund> findActiveByBilling(@Param("billingId") Long billingId);

    /**
     * 계좌 이체 대상 — <b>데스크가 매일 보는 목록</b>이다.
     *
     * <p>우리가 이체를 실행하지 않으므로(사람이 은행에서 한다) 이 목록이 비지 않으면
     * 누군가 받을 돈을 아직 못 받은 상태다.
     */
    @Query("""
            SELECT r FROM Refund r
            JOIN FETCH r.billing b
            JOIN FETCH b.enrollment e
            JOIN FETCH e.student
            WHERE r.academy.id = :academyId AND r.year = :year
              AND r.status = :status
              AND r.deleted = false
            ORDER BY r.createdAt ASC
            """)
    List<Refund> findByStatus(@Param("academyId") Long academyId, @Param("year") short year,
                              @Param("status") RefundStatus status);

    @Query("""
            SELECT r FROM Refund r
            JOIN FETCH r.billing b
            JOIN FETCH b.enrollment e
            JOIN FETCH e.student
            WHERE r.academy.id = :academyId AND r.year = :year AND r.deleted = false
            ORDER BY r.createdAt DESC
            """)
    List<Refund> findAllOf(@Param("academyId") Long academyId, @Param("year") short year);
}
