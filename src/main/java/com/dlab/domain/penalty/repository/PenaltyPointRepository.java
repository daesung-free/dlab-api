package com.dlab.domain.penalty.repository;

import com.dlab.domain.penalty.entity.PenaltyPoint;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PenaltyPointRepository extends JpaRepository<PenaltyPoint, Long> {

    boolean existsByIdempotencyKeyAndDeletedFalse(String idempotencyKey);

    /** 이 등록 건에 상벌점 이력이 있는가 — 학생 삭제를 막는 근거다. */
    boolean existsByEnrollmentIdAndDeletedFalse(Long enrollmentId);

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
     * 여러 학생의 <b>누적</b> 점수를 한 번에.
     *
     * <h2>★ 목록이 행마다 조회하면 안 된다</h2>
     * 상벌점 목록은 「누적 38점 (이번 달 +3)」을 <b>학생마다</b> 보여야 한다(디랩 피드백 5-7).
     * 행마다 {@link #sumPointsByEnrollment} 를 부르면 <b>200명이면 쿼리가 200번</b> 나간다.
     *
     * <p>⚠️ <b>기간을 걸지 않는다 — 누적이다.</b> 제적 기준 40점이 누적이라, 기간 합계만
     * 보이면 학부모가 「이번 달 +3」만 보고 안심한다. 그게 디랩 지적의 핵심이다.
     */
    @Query("""
            SELECT p.enrollment.id, COALESCE(SUM(p.points), 0) FROM PenaltyPoint p
            WHERE p.deleted = false
              AND p.enrollment.id IN :enrollmentIds
            GROUP BY p.enrollment.id
            """)
    List<Object[]> sumPointsByEnrollments(
            @Param("enrollmentIds") java.util.Collection<Long> enrollmentIds);

    /**
     * 학생 단위 누적 집계 (디랩 1021 피드백 2-5).
     *
     * <p>디랩 원문: <i>"전체 재원생의 현재 누적 상벌점 점수가 조회되면 좋을 것 같습니다.
     * <b>누적상점이 가장높은학생 순</b>으로 정렬되거나 <b>누적 벌점이 가장 높은학생 순</b>으로
     * 정렬되는 데이터가 필요합니다"</i>
     *
     * <h2>★★ 상점 합과 벌점 합을 따로 낸다</h2>
     * 합계만 내면 <b>「상점 169 · 벌점 −152 · 합 17」인 학생과 「상점 0 · 벌점 0」인 학생이
     * 같아 보인다.</b> 디랩이 둘을 각각 정렬하고 싶다고 한 이유다.
     *
     * <p>벌점은 음수로 저장된다(시드 주석: <i>"통계가 부호로 상점·벌점을 가른다"</i>) —
     * 그래서 <b>부호로 가르고 벌점은 절댓값</b>으로 낸다. 화면에 음수를 내보내면
     * 「벌점 높은 순」이 뒤집힌다.
     *
     * <p>⚠️ <b>기간을 걸지 않는다 — 누적이다.</b> 제적 기준 40점이 누적이고, 기간 합계만
     * 보이면 임계에 가까운 학생이 드러나지 않는다.
     *
     * <p>⚠️ 상벌점이 <b>한 건도 없는 학생은 이 결과에 안 나온다</b>(그룹이 안 생긴다) —
     * 부르는 쪽이 재원생 목록과 합쳐 0으로 채운다. 안 하면 「상벌점 없는 학생」이
     * 목록에서 사라진다.
     *
     * @return {@code [enrollmentId, 상점합(양수), 벌점합(절댓값), 순합계]}
     */
    @Query("""
            SELECT p.enrollment.id,
                   COALESCE(SUM(CASE WHEN p.points > 0 THEN p.points ELSE 0 END), 0),
                   COALESCE(SUM(CASE WHEN p.points < 0 THEN -p.points ELSE 0 END), 0),
                   COALESCE(SUM(p.points), 0)
            FROM PenaltyPoint p
            WHERE p.deleted = false
              AND p.enrollment.id IN :enrollmentIds
            GROUP BY p.enrollment.id
            """)
    List<Object[]> aggregateByEnrollments(
            @Param("enrollmentIds") java.util.Collection<Long> enrollmentIds);

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

    /**
     * 관리자 목록. 지점·기간 + 조건으로 훑는다.
     *
     * <p>{@code category}·{@code sources}·{@code enrollmentStatus}는 {@code null}이면 전체다 —
     * 화면 칩 필터가 미선택 상태일 때 조건이 빠져야 한다.
     *
     * <p><b>{@code sources}가 목록인 이유</b>: 화면이 '수기'와 '자동' 둘로 묶는데
     * '자동'은 {@code KIOSK}·{@code ROUTINE} 두 값의 OR다. 단일 파라미터로는 못 보낸다.
     */
    @Query("""
            SELECT p FROM PenaltyPoint p
            JOIN FETCH p.enrollment e
            JOIN FETCH e.student
            JOIN FETCH p.penaltyItem i
            WHERE p.academy.id = :academyId
              AND p.occurredAt >= :from
              AND p.occurredAt < :to
              AND (:category IS NULL OR i.category = :category)
              AND (:sources IS NULL OR p.source IN :sources)
              AND (:enrollmentStatus IS NULL OR e.enrollmentStatus = :enrollmentStatus)
              AND p.deleted = false
            ORDER BY p.occurredAt DESC
            """)
    List<PenaltyPoint> search(@Param("academyId") Long academyId,
                              @Param("from") java.time.Instant from,
                              @Param("to") java.time.Instant to,
                              @Param("category") com.dlab.domain.penalty.entity.PenaltyCategory category,
                              @Param("sources") java.util.Collection<com.dlab.domain.penalty.entity.PenaltySource> sources,
                              @Param("enrollmentStatus") com.dlab.domain.user.entity.EnrollmentStatus enrollmentStatus);
}
