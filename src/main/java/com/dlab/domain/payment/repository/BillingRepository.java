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

    /**
     * 그 학생의 그 달 교습비 청구가 이미 있는가.
     *
     * <p><b>중복 발행을 막는다.</b> 데스크가 두 번 누르거나 입학 처리를 다시 태우면
     * 같은 달 청구가 두 건 생기고, 그러면 미납액이 두 배로 잡힌 채 독촉이 나간다.
     * 취소된 건은 다시 발행해야 하므로 뺀다.
     */
    @Query("""
            SELECT COUNT(b) > 0 FROM Billing b
            WHERE b.enrollment.id = :enrollmentId
              AND b.serviceYear = :year AND b.serviceMonth = :month
              AND b.billingType = com.dlab.domain.payment.entity.BillingType.TUITION
              AND b.status <> com.dlab.domain.payment.entity.BillingStatus.CANCELLED
              AND b.deleted = false
            """)
    boolean existsTuitionFor(@Param("enrollmentId") Long enrollmentId,
                             @Param("year") Short year, @Param("month") Short month);

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
