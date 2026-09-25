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

    /**
     * 같은 학생에게 <b>같은 이름</b>으로 이미 나간 청구가 있나 — 특강비 등 일반 청구의
     * 중복 발행을 막는다.
     *
     * <p>교습비는 이용월로 막지만({@link #existsTuitionFor}) 특강비는 이용월이 없다.
     * 데스크가 저장을 두 번 누르면 <b>같은 특강이 두 건 잡혀 미납이 두 배</b>가 되고
     * 그대로 독촉이 나간다.
     *
     * <p>취소된 건은 세지 않는다 — 잘못 발행해 취소한 뒤 다시 내야 하기 때문이다.
     */
    @Query("""
            SELECT COUNT(b) > 0 FROM Billing b
            WHERE b.enrollment.id = :enrollmentId
              AND b.billingType = :type
              AND b.name = :name
              AND b.status <> com.dlab.domain.payment.entity.BillingStatus.CANCELLED
              AND b.deleted = false
            """)
    boolean existsSameNamed(@Param("enrollmentId") Long enrollmentId,
                            @Param("type") com.dlab.domain.payment.entity.BillingType type,
                            @Param("name") String name);

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

    /**
     * 수납현황 — 지점·연도의 청구를 <b>수납 합계와 함께</b> 가져온다 (F-4.8-1).
     *
     * <p>미납액은 {@code billedAmount − 수납합계}인데, 엔티티의 {@code unpaidAmount()}는
     * 거래를 메모리에서 훑는다. <b>지점 전체를 그렇게 하면 청구 수만큼 거래를 다 끌어온다</b> —
     * 여기서는 DB가 합계를 내게 한다.
     *
     * <p>취소된 거래는 빼고 센다. 납부기한이 없는 청구도 있으므로 기간 필터는
     * <b>기한이 있는 건에만</b> 걸린다 — 안 그러면 기한 없는 청구가 통째로 사라진다.
     *
     * <p>⚠️ {@code from}·{@code to}에 {@code null}을 넘기지 말 것.
     * PostgreSQL이 {@code :param IS NULL}만 보고는 타입을 추론하지 못해
     * <b>"could not determine data type"</b>으로 깨진다 — 호출자가 넓은 기본값을 넣는다.
     */
    @Query("""
            SELECT b, COALESCE(SUM(CASE WHEN tx.canceledAt IS NULL AND tx.deleted = false
                                        THEN tx.amount ELSE 0 END), 0)
            FROM Billing b
            JOIN FETCH b.enrollment e
            JOIN FETCH e.student
            LEFT JOIN b.transactions tx
            WHERE b.academy.id = :academyId AND b.year = :year
              AND b.status <> com.dlab.domain.payment.entity.BillingStatus.CANCELLED
              AND b.deleted = false
              AND (b.dueDate IS NULL OR (b.dueDate >= :from AND b.dueDate <= :to))
            GROUP BY b, e, e.student
            ORDER BY b.dueDate ASC NULLS LAST, b.id ASC
            """)
    List<Object[]> findWithReceived(@Param("academyId") Long academyId,
                                    @Param("year") short year,
                                    @Param("from") java.time.LocalDate from,
                                    @Param("to") java.time.LocalDate to);
}
