package com.dlab.domain.plan.repository;

import com.dlab.domain.plan.entity.LearningPlan;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LearningPlanRepository extends JpaRepository<LearningPlan, Long> {

    @Query("""
            SELECT p FROM LearningPlan p
            WHERE p.enrollment.id = :enrollmentId AND p.planDate = :date AND p.deleted = false
            """)
    Optional<LearningPlan> findByDate(@Param("enrollmentId") Long enrollmentId,
                                      @Param("date") LocalDate date);

    /**
     * 날짜 범위. 주간 뷰와 통계가 함께 쓴다.
     *
     * <p>항목까지 함께 읽는다 — 7일치를 하나씩 조회하면 항목 조회가 날짜 수만큼 더 나간다.
     */
    @Query("""
            SELECT DISTINCT p FROM LearningPlan p
            LEFT JOIN FETCH p.items i
            WHERE p.enrollment.id = :enrollmentId
              AND p.planDate BETWEEN :from AND :to
              AND p.deleted = false
            ORDER BY p.planDate
            """)
    List<LearningPlan> findRange(@Param("enrollmentId") Long enrollmentId,
                                 @Param("from") LocalDate from,
                                 @Param("to") LocalDate to);
}
