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

    /**
     * 관리자 목록(결제·취소 내역 탭).
     *
     * <p>★ <b>항목·청구까지 함께 읽는다.</b> 화면이 주문마다 끼니 목록과 금액을 그리는데
     * 지연 로딩으로 두면 <b>쿼리가 주문 수만큼 나가고</b>(재원생 전체가 대상이다),
     * 트랜잭션 밖에서 접근하면 아예 터진다({@code open-in-view: false}).
     *
     * <p>항목은 컬렉션이라 조인 결과에 행이 불어나므로 {@code DISTINCT}로 접는다.
     * 컬렉션 fetch join은 <b>하나뿐</b>이어야 한다 — 둘이면 곱집합이 된다.
     * 청구는 {@code ManyToOne}이라 행이 늘지 않는다.
     */
    @Query("""
            SELECT DISTINCT o FROM MealOrder o
            JOIN FETCH o.enrollment e
            JOIN FETCH e.student
            LEFT JOIN FETCH o.items
            LEFT JOIN FETCH o.billing
            WHERE o.academy.id = :academyId
              AND o.targetMonth = :targetMonth
              AND o.deleted = false
            ORDER BY o.createdAt DESC
            """)
    List<MealOrder> findByAcademyAndMonth(@Param("academyId") Long academyId,
                                          @Param("targetMonth") LocalDate targetMonth);
}
