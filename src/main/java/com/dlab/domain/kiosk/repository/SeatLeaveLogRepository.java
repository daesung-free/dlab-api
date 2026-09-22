package com.dlab.domain.kiosk.repository;

import com.dlab.domain.kiosk.entity.SeatLeaveLog;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatLeaveLogRepository extends JpaRepository<SeatLeaveLog, Long> {

    /**
     * 이미 받은 행 ID들.
     *
     * <p><b>한 번에 조회한다.</b> 재전송은 여러 건이 한꺼번에 오는데 건마다 조회하면
     * 배치 크기만큼 쿼리가 나간다.
     */
    @Query("""
            SELECT l.sourceRowId FROM SeatLeaveLog l
            WHERE l.academy.id = :academyId AND l.sourceRowId IN :sourceRowIds
            """)
    List<Long> findExistingSourceRowIds(@Param("academyId") Long academyId,
                                        @Param("sourceRowIds") Collection<Long> sourceRowIds);

    /** 학생을 못 찾아 보류된 행. 나중에 이어 붙이는 운영 화면에서 쓴다. */
    @Query("""
            SELECT l FROM SeatLeaveLog l
            WHERE l.academy.id = :academyId AND l.enrollment IS NULL AND l.deleted = false
            ORDER BY l.occurredAt DESC
            """)
    List<SeatLeaveLog> findUnresolved(@Param("academyId") Long academyId);

    /**
     * 지점의 기간 로그. 관리자 이탈 현황이 이탈·복귀를 짝지을 때 쓴다.
     *
     * <p>학생을 못 찾은 행도 함께 온다(LEFT JOIN) — 빠뜨리면 그 이탈이 화면에서 사라진다.
     */
    @Query("""
            SELECT l FROM SeatLeaveLog l
            LEFT JOIN FETCH l.enrollment e
            LEFT JOIN FETCH e.student
            WHERE l.academy.id = :academyId
              AND l.occurredAt >= :from AND l.occurredAt < :to
              AND l.deleted = false
            ORDER BY l.occurredAt, l.id
            """)
    List<SeatLeaveLog> findByAcademyBetween(@Param("academyId") Long academyId,
                                            @Param("from") Instant from, @Param("to") Instant to);

    /**
     * 학생별 기간 로그. 미복귀 감지가 <b>확정되면</b> 여기를 쓴다.
     *
     * <p>지금은 읽는 곳이 없다 — 임계값(I-16)·벌점 트리거(I-5) 대기 중이다.
     */
    @Query("""
            SELECT l FROM SeatLeaveLog l
            WHERE l.enrollment.id = :enrollmentId
              AND l.occurredAt BETWEEN :from AND :to
              AND l.deleted = false
            ORDER BY l.occurredAt
            """)
    List<SeatLeaveLog> findByEnrollment(@Param("enrollmentId") Long enrollmentId,
                                        @Param("from") Instant from, @Param("to") Instant to);
}
