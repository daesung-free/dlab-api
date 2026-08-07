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

    MealOrderItem(MealOrder order, LocalDate mealDate, MealType mealType) {
        this.order = order;
        this.mealDate = mealDate;
        this.mealType = mealType;
    }

    public boolean isActive() {
        return canceledAt == null && !isDeleted();
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
