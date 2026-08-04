package com.dlab.domain.penalty.repository;

import com.dlab.domain.penalty.entity.PenaltyPoint;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PenaltyPointRepository extends JpaRepository<PenaltyPoint, Long> {

    boolean existsByIdempotencyKeyAndDeletedFalse(String idempotencyKey);

    /** 학생별 내역. 앱 Daily Report와 관리자 상세가 함께 쓴다. */
    @Query("""
            SELECT p FROM PenaltyPoint p
              JOIN FETCH p.penaltyItem
            WHERE p.deleted = false
              AND p.enrollment.id = :enrollmentId
            ORDER BY p.occurredAt DESC
            """)
    List<PenaltyPoint> findByEnrollment(@Param("enrollmentId") Long enrollmentId);

    /** 누적 점수. 앱 홈 3지표의 "벌점 현황"이 이 값이다. */
    @Query("""
            SELECT COALESCE(SUM(p.points), 0) FROM PenaltyPoint p
            WHERE p.deleted = false
              AND p.enrollment.id = :enrollmentId
            """)
    int sumPointsByEnrollment(@Param("enrollmentId") Long enrollmentId);

    /**
     * 지점 전체의 기간 내 상벌점. 키오스크 {@code getPointStdList}가 쓴다.
     *
     * <p><b>학생 단건 조회가 아니다</b> — 키오스크가 지점 전체를 한 번 받아
     * {@code std_no}로 쪼개 10분간 캐싱하는 구조다(doc/kiosk StudentDetailDsaService).
     */
    @Query("""
            SELECT p FROM PenaltyPoint p
            JOIN FETCH p.enrollment e
            JOIN FETCH p.penaltyItem
            WHERE p.academy.id = :academyId
              AND p.occurredAt >= :from
              AND p.occurredAt < :to
              AND p.deleted = false
            ORDER BY p.occurredAt ASC
            """)
    List<PenaltyPoint> findByAcademyAndPeriod(@Param("academyId") Long academyId,
                                              @Param("from") java.time.Instant from,
                                              @Param("to") java.time.Instant to);
}
