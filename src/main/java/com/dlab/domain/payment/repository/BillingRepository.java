package com.dlab.domain.payment.repository;

import com.dlab.domain.payment.entity.Billing;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BillingRepository extends JpaRepository<Billing, Long> {

    /**
     * 3.29 {@code getReceiptInfo} — 그 학생의 청구 전체.
     *
     * <p><b>완납 건도 내린다.</b> 키오스크가 수납 내역을 보여주는 화면이라
     * 미납만 주면 "낸 것"이 사라진다.
     */
    @Query("""
            SELECT b FROM Billing b
            LEFT JOIN FETCH b.transactions
            WHERE b.enrollment.id = :enrollmentId
              AND b.status <> com.dlab.domain.payment.entity.BillingStatus.CANCELLED
              AND b.deleted = false
            ORDER BY b.dueDate ASC NULLS LAST, b.id ASC
            """)
    List<Billing> findByEnrollment(@Param("enrollmentId") Long enrollmentId);

    /** 지점·연도 청구 전체. 수납현황·미납자 추출이 쓴다. */
    @Query("""
            SELECT b FROM Billing b
            JOIN FETCH b.enrollment e
            JOIN FETCH e.student
            WHERE b.academy.id = :academyId AND b.year = :year AND b.deleted = false
            ORDER BY b.id DESC
            """)
    List<Billing> findByAcademyAndYear(@Param("academyId") Long academyId,
                                       @Param("year") short year);
}
