package com.dlab.domain.meal.repository;

import com.dlab.domain.meal.entity.MealOrder;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MealOrderRepository extends JpaRepository<MealOrder, Long> {

    /** 그 학생의 그 달 주문. 한 달에 하나만 만든다 — 있으면 항목을 더한다. */
    @Query("""
            SELECT o FROM MealOrder o
            WHERE o.enrollment.id = :enrollmentId
              AND o.targetMonth = :targetMonth
              AND o.status <> com.dlab.domain.meal.entity.MealOrderStatus.CANCELLED
              AND o.deleted = false
            """)
    Optional<MealOrder> findActiveByEnrollmentAndMonth(@Param("enrollmentId") Long enrollmentId,
                                                       @Param("targetMonth") LocalDate targetMonth);

    /** 관리자 목록(결제·취소 내역 탭). */
    @Query("""
            SELECT o FROM MealOrder o
            JOIN FETCH o.enrollment e
            JOIN FETCH e.student
            WHERE o.academy.id = :academyId
              AND o.targetMonth = :targetMonth
              AND o.deleted = false
            ORDER BY o.createdAt DESC
            """)
    List<MealOrder> findByAcademyAndMonth(@Param("academyId") Long academyId,
                                          @Param("targetMonth") LocalDate targetMonth);
}
