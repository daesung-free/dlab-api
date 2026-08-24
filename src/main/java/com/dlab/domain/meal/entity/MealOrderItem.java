package com.dlab.domain.meal.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 급식 주문 항목 — 날짜 × 끼니.
 *
 * <p><b>취소가 이 단위다.</b> 앱 API도 항목 단위로 지운다
 * ({@code DELETE /meals/orders/{id}/items/{itemId}}).
 */
@Getter
@Entity
@Table(name = "meal_order_item")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MealOrderItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private MealOrder order;

    @Column(name = "meal_date", nullable = false)
    private LocalDate mealDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "meal_type", nullable = false, length = 10)
    private MealType mealType;

    @Column(name = "canceled_at")
    private Instant canceledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_path", length = 10)
    private CancelPath cancelPath;

    /**
     * ★ <b>신청 시점 단가 스냅샷</b>이다. 마스터({@code meal_policy.unit_price})를
     * 다시 읽어 계산하면, 단가를 올리는 순간 <b>과거 주문 금액이 소급해서 바뀐다</b> —
     * 이미 결제·정산이 끝난 달의 금액이 달라지면 맞출 방법이 없다.
     * 청구({@code billing.billed_amount})에서 같은 판단을 했다.
     *
     * <p>단가가 등록되기 전에 만들어진 주문은 비어 있다.
     */
    @Column(name = "unit_price")
    private Integer unitPrice;

    MealOrderItem(MealOrder order, LocalDate mealDate, MealType mealType) {
        this(order, mealDate, mealType, null);
    }

    MealOrderItem(MealOrder order, LocalDate mealDate, MealType mealType, Integer unitPrice) {
        this.order = order;
        this.mealDate = mealDate;
        this.mealType = mealType;
        this.unitPrice = unitPrice;
    }

    public boolean isActive() {
        return canceledAt == null && !isDeleted();
    }

    /**
     * 이 끼니의 금액. 단가가 없으면 0이다 — <b>금액을 아는 것처럼 굴지 않는다.</b>
     * 청구를 만들 때는 {@code MealPolicy.isPriced()}로 먼저 걸러야 한다.
     */
    public int amount() {
        return unitPrice == null ? 0 : unitPrice;
    }

    /**
     * 취소.
     *
     * <p><b>물리 삭제하지 않는다</b> — 언제 누가 어느 경로로 취소했는지가 정산 근거다.
     * 특히 {@link CancelPath#CLOSURE}는 나중에 환불 대상을 찾는 유일한 단서다.
     */
    public void cancel(Instant at, CancelPath path) {
        this.canceledAt = at;
        this.cancelPath = path;
    }
}
