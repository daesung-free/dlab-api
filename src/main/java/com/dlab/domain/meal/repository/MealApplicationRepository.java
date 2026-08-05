package com.dlab.domain.meal.repository;

import com.dlab.domain.meal.entity.MealApplication;
import com.dlab.domain.meal.entity.MealType;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MealApplicationRepository extends JpaRepository<MealApplication, Long> {

    /** 3.31 {@code getMealApplyYN} — 그 끼니를 신청했는가. 취소분은 제외한다. */
    @Query("""
            SELECT COUNT(m) > 0 FROM MealApplication m
            WHERE m.enrollment.id = :enrollmentId
              AND m.mealDate = :mealDate
              AND m.mealType = :mealType
              AND m.canceledAt IS NULL
              AND m.deleted = false
            """)
    boolean isApplied(@Param("enrollmentId") Long enrollmentId,
                      @Param("mealDate") LocalDate mealDate,
                      @Param("mealType") MealType mealType);

    /** 3.30 {@code getMealApplyStdInfo} — 지점의 월별 신청 전체. */
    @Query("""
            SELECT m FROM MealApplication m
            JOIN FETCH m.enrollment e
            JOIN FETCH e.student
            WHERE m.academy.id = :academyId
              AND m.mealDate >= :from
              AND m.mealDate <= :to
              AND m.canceledAt IS NULL
              AND m.deleted = false
            ORDER BY m.mealDate ASC, m.mealType ASC
            """)
    List<MealApplication> findActiveByAcademyAndPeriod(@Param("academyId") Long academyId,
                                                       @Param("from") LocalDate from,
                                                       @Param("to") LocalDate to);
}
