package com.dlab.domain.routine.repository;

import com.dlab.domain.routine.entity.DailyRoutineResult;
import com.dlab.domain.routine.entity.RoutineResultStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DailyRoutineResultRepository extends JpaRepository<DailyRoutineResult, Long> {

    Optional<DailyRoutineResult> findByRoutineIdAndEnrollmentIdAndResultDate(
            Long routineId, Long enrollmentId, LocalDate resultDate);

    /** 반 단위 그리드 — 이 루틴·이 날짜의 전원. 시트가 "개별 폼 금지"라 그리드가 기본이다. */
    @Query("""
            SELECT r FROM DailyRoutineResult r
            JOIN FETCH r.enrollment e
            JOIN FETCH e.student
            WHERE r.routine.id = :routineId AND r.resultDate = :date AND r.deleted = false
            ORDER BY e.studentNo
            """)
    List<DailyRoutineResult> findGrid(@Param("routineId") Long routineId,
                                      @Param("date") LocalDate date);

    /**
     * 학생의 그날 결과 — 앱 A-11.
     *
     * <p>상태 필터를 <b>여기서 걸지 않는다</b>. 검수 중인 항목도 "오늘 할 일"로는 보여야 하고,
     * 점수만 가리면 되기 때문이다 — 응답 조립 단계에서 판단한다.
     */
    @Query("""
            SELECT r FROM DailyRoutineResult r
            JOIN FETCH r.routine
            WHERE r.enrollment.id = :enrollmentId AND r.resultDate = :date AND r.deleted = false
            ORDER BY r.routine.sortOrder, r.routine.id
            """)
    List<DailyRoutineResult> findByStudentAndDate(@Param("enrollmentId") Long enrollmentId,
                                                  @Param("date") LocalDate date);

    /** 기간 내 결과 — Daily Report의 "데일리테스트 횟수"가 이걸 센다. */
    @Query("""
            SELECT r FROM DailyRoutineResult r
            JOIN FETCH r.routine
            WHERE r.enrollment.id = :enrollmentId
              AND r.resultDate >= :from AND r.resultDate <= :to
              AND r.deleted = false
            ORDER BY r.resultDate, r.routine.sortOrder
            """)
    List<DailyRoutineResult> findByStudentAndPeriod(@Param("enrollmentId") Long enrollmentId,
                                                    @Param("from") LocalDate from,
                                                    @Param("to") LocalDate to);

    /** 일괄 공개 대상 — 검수까지 끝난 것만. */
    List<DailyRoutineResult> findByRoutineIdAndResultDateAndStatusAndDeletedFalse(
            Long routineId, LocalDate resultDate, RoutineResultStatus status);
}
