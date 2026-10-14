package com.dlab.domain.meal.repository;

import com.dlab.domain.meal.entity.MealOrderItem;
import com.dlab.domain.meal.entity.MealType;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MealOrderItemRepository extends JpaRepository<MealOrderItem, Long> {

    /**
     * 3.31 {@code getMealApplyYN} — 그 끼니를 신청했는가.
     *
     * <p><b>결제 여부는 아직 안 본다.</b> 결제(E-3)가 붙으면 주문 상태가 {@code PAID}인지도
     * 함께 봐야 한다 — 지금 요구하면 결제가 없어 아무도 통과하지 못하고,
     * 키오스크 폴백까지 겹쳐 급식이 통째로 뚫린다.
     */
    @Query("""
            SELECT COUNT(i) > 0 FROM MealOrderItem i
            WHERE i.order.enrollment.id = :enrollmentId
              AND i.mealDate = :mealDate
              AND i.mealType = :mealType
              AND i.canceledAt IS NULL
              AND i.deleted = false
              AND i.order.status <> com.dlab.domain.meal.entity.MealOrderStatus.CANCELLED
              AND i.order.deleted = false
            """)
    boolean isApplied(@Param("enrollmentId") Long enrollmentId,
                      @Param("mealDate") LocalDate mealDate,
                      @Param("mealType") MealType mealType);

    /** 3.30 {@code getMealApplyStdInfo} — 지점의 월별 신청 전체. */
    @Query("""
            SELECT i FROM MealOrderItem i
            JOIN FETCH i.order o
            JOIN FETCH o.enrollment e
            JOIN FETCH e.student
            WHERE o.academy.id = :academyId
              AND i.mealDate >= :from
              AND i.mealDate <= :to
              AND i.canceledAt IS NULL
              AND i.deleted = false
              AND o.status <> com.dlab.domain.meal.entity.MealOrderStatus.CANCELLED
              AND o.deleted = false
            ORDER BY i.mealDate ASC, i.mealType ASC
            """)
    List<MealOrderItem> findActiveByAcademyAndPeriod(@Param("academyId") Long academyId,
                                                     @Param("from") LocalDate from,
                                                     @Param("to") LocalDate to);

    /**
     * 그날 살아 있는 신청 <b>명단</b> — 학생까지 함께 가져온다 (피드백 5-4).
     *
     * <p>{@link #findActiveByAcademyAndDate}와 대상은 같지만 <b>용도가 다르다.</b>
     * 그쪽은 일괄 취소용이라 학생을 볼 필요가 없고, 여기는 이름·학번을 화면에 찍는다 —
     * 등록 건·학생을 fetch join 하지 않으면 <b>행마다 쿼리가 두 번씩 더 나간다.</b>
     * 그날 전원이 대상인 화면이라 수백 건에서 그대로 드러난다.
     *
     * <p>정렬을 쿼리에서 끝낸다 — 끼니별로 나눈 뒤 각각 다시 정렬하면 같은 기준을
     * 두 군데서 들고 있게 된다.
     */
    @Query("""
            SELECT i FROM MealOrderItem i
            JOIN FETCH i.order o
            JOIN FETCH o.enrollment e
            JOIN FETCH e.student s
            WHERE o.academy.id = :academyId
              AND i.mealDate = :date
              AND i.canceledAt IS NULL
              AND i.deleted = false
              AND o.status <> com.dlab.domain.meal.entity.MealOrderStatus.CANCELLED
              AND o.deleted = false
            ORDER BY i.mealType ASC, e.studentNo ASC, s.name ASC
            """)
    List<MealOrderItem> findRosterByAcademyAndDate(@Param("academyId") Long academyId,
                                                   @Param("date") LocalDate date);

    /** 그날 살아 있는 신청 전체. 중단일 등록이 이걸 일괄 취소한다. */
    @Query("""
            SELECT i FROM MealOrderItem i
            JOIN FETCH i.order o
            WHERE o.academy.id = :academyId
              AND i.mealDate = :date
              AND i.canceledAt IS NULL
              AND i.deleted = false
              AND o.deleted = false
            """)
    List<MealOrderItem> findActiveByAcademyAndDate(@Param("academyId") Long academyId,
                                                   @Param("date") LocalDate date);

    /**
     * 그 학생의 <b>지정일 이후</b> 살아 있는 신청.
     *
     * <p>퇴원 후속처리가 쓴다. <b>지난 날짜는 건드리지 않는다</b> — 이미 먹은 급식이라
     * 취소하면 정산이 어긋난다.
     */
    @Query("""
            SELECT i FROM MealOrderItem i
            JOIN FETCH i.order o
            WHERE o.enrollment.id = :enrollmentId
              AND i.mealDate >= :from
              AND i.canceledAt IS NULL
              AND i.deleted = false
              AND o.status <> com.dlab.domain.meal.entity.MealOrderStatus.CANCELLED
              AND o.deleted = false
            ORDER BY i.mealDate ASC, i.mealType ASC
            """)
    List<MealOrderItem> findActiveByEnrollmentFrom(@Param("enrollmentId") Long enrollmentId,
                                                   @Param("from") LocalDate from);
}
