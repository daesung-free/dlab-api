package com.dlab.domain.statistics.repository;

import com.dlab.domain.attendance.entity.AttendanceDailyStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 통계 집계 (F-4.11-11).
 *
 * <p><b>원시 로그를 훑지 않는다.</b> 출결·순공은 새벽 배치가
 * {@code attendance_daily_status}에 학생×하루 1행으로 집계해둔 값을 읽는다 —
 * {@code attendance_tagging_log}는 안 본다.
 *
 * <p>{@code academyId}가 {@code null}이면 전 지점이다. 쿼리마다 {@code :academyId IS NULL}로
 * 분기해 <b>메서드를 두 벌로 만들지 않는다</b> — 두 벌이면 한쪽만 고쳐 값이 갈린다.
 */
public interface StatisticsRepository extends JpaRepository<AttendanceDailyStatus, Long> {

    /** 재원생 수. 상태별로 센다. */
    @Query("""
            SELECT e.enrollmentStatus, COUNT(e)
            FROM StudentEnrollment e
            WHERE (:academyId IS NULL OR e.academy.id = :academyId)
              AND e.year = :year
              AND e.current = true
              AND e.deleted = false
            GROUP BY e.enrollmentStatus
            """)
    List<Object[]> countByEnrollmentStatus(@Param("academyId") Long academyId,
                                           @Param("year") short year);

    /** 출결 — 확정된 일자 상태별 건수. */
    @Query("""
            SELECT d.finalStatus, COUNT(d)
            FROM AttendanceDailyStatus d
            WHERE (:academyId IS NULL OR d.academy.id = :academyId)
              AND d.attendanceDate >= :from AND d.attendanceDate <= :to
              AND d.deleted = false
            GROUP BY d.finalStatus
            """)
    List<Object[]> countByDailyStatus(@Param("academyId") Long academyId,
                                      @Param("from") LocalDate from,
                                      @Param("to") LocalDate to);

    /**
     * 순공시간 합계·평균.
     *
     * <p><b>배치가 저장해둔 값을 더한다</b> — 재계산이 아니다.
     * {@code studyMinutes}가 {@code null}인 행(확정 전·결석)은 SUM/AVG에서 자동으로 빠진다.
     */
    @Query("""
            SELECT COALESCE(SUM(d.studyMinutes), 0), COALESCE(AVG(d.studyMinutes), 0),
                   COUNT(d.studyMinutes)
            FROM AttendanceDailyStatus d
            WHERE (:academyId IS NULL OR d.academy.id = :academyId)
              AND d.attendanceDate >= :from AND d.attendanceDate <= :to
              AND d.deleted = false
            """)
    List<Object[]> studyTimeTotals(@Param("academyId") Long academyId,
                                   @Param("from") LocalDate from,
                                   @Param("to") LocalDate to);

    /**
     * 순공시간 상위 학생. 대시보드가 랭킹으로 쓴다.
     *
     * <p><b>순공 기록이 아예 없는 학생은 뺀다</b>({@code HAVING ... IS NOT NULL}) —
     * 0분으로 채우면 결석·미확정인 학생이 꼴찌로 줄줄이 붙어 랭킹이 재원생 명단이 된다.
     */
    @Query("""
            SELECT e.studentNo, s.name, SUM(d.studyMinutes)
            FROM AttendanceDailyStatus d
            JOIN d.enrollment e
            JOIN e.student s
            WHERE (:academyId IS NULL OR d.academy.id = :academyId)
              AND d.attendanceDate >= :from AND d.attendanceDate <= :to
              AND d.deleted = false
            GROUP BY e.studentNo, s.name
            HAVING SUM(d.studyMinutes) IS NOT NULL
            ORDER BY SUM(d.studyMinutes) DESC
            """)
    List<Object[]> studyTimeRanking(@Param("academyId") Long academyId,
                                    @Param("from") LocalDate from,
                                    @Param("to") LocalDate to,
                                    org.springframework.data.domain.Pageable pageable);

    /** 상벌점 — 상점·벌점 합계. 벌점은 음수로 저장돼 있어 부호로 가른다. */
    @Query("""
            SELECT COALESCE(SUM(CASE WHEN p.points > 0 THEN p.points ELSE 0 END), 0),
                   COALESCE(SUM(CASE WHEN p.points < 0 THEN p.points ELSE 0 END), 0),
                   COUNT(p)
            FROM PenaltyPoint p
            WHERE (:academyId IS NULL OR p.academy.id = :academyId)
              AND p.occurredAt >= :from AND p.occurredAt < :to
              AND p.deleted = false
            """)
    List<Object[]> penaltyTotals(@Param("academyId") Long academyId,
                                 @Param("from") Instant from,
                                 @Param("to") Instant to);

    /** 급식 신청 — 끼니별 건수. 취소분은 뺀다. */
    @Query("""
            SELECT i.mealType, COUNT(i)
            FROM MealOrderItem i
            WHERE (:academyId IS NULL OR i.order.academy.id = :academyId)
              AND i.mealDate >= :from AND i.mealDate <= :to
              AND i.canceledAt IS NULL
              AND i.deleted = false
              AND i.order.deleted = false
            GROUP BY i.mealType
            """)
    List<Object[]> mealCounts(@Param("academyId") Long academyId,
                              @Param("from") LocalDate from,
                              @Param("to") LocalDate to);

    /** 급식 취소 건수 — 경로별. 환불 대상 파악에 쓴다. */
    @Query("""
            SELECT i.cancelPath, COUNT(i)
            FROM MealOrderItem i
            WHERE (:academyId IS NULL OR i.order.academy.id = :academyId)
              AND i.mealDate >= :from AND i.mealDate <= :to
              AND i.canceledAt IS NOT NULL
              AND i.deleted = false
            GROUP BY i.cancelPath
            """)
    List<Object[]> mealCancelCounts(@Param("academyId") Long academyId,
                                    @Param("from") LocalDate from,
                                    @Param("to") LocalDate to);

    /**
     * 매출 — 청구 유형별 청구액·수납액.
     *
     * <p>수납액은 거래에서 합산한다(취소분 제외) — 청구에 컬럼으로 두지 않았다.
     */
    @Query("""
            SELECT b.billingType,
                   COALESCE(SUM(b.billedAmount), 0),
                   COALESCE((SELECT SUM(t.amount) FROM PaymentTransaction t
                             WHERE t.billing.billingType = b.billingType
                               AND t.billing.year = :year
                               AND (:academyId IS NULL OR t.billing.academy.id = :academyId)
                               AND t.canceledAt IS NULL AND t.deleted = false), 0),
                   COUNT(b)
            FROM Billing b
            WHERE (:academyId IS NULL OR b.academy.id = :academyId)
              AND b.year = :year
              AND b.status <> com.dlab.domain.payment.entity.BillingStatus.CANCELLED
              AND b.deleted = false
            GROUP BY b.billingType
            """)
    List<Object[]> revenueByType(@Param("academyId") Long academyId, @Param("year") short year);
}
