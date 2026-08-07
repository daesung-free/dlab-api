package com.dlab.domain.meal.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 급식 주문 — <b>한 달치 묶음</b> (F-4.5 · A-9).
 *
 * <p>요구사항이 "월말에 다음 달 한 달치 일괄 신청"이고 결제도 한 번에 하므로,
 * <b>결제가 붙을 자리가 주문</b>이다. 취소는 항목 단위다.
 */
@Getter
@Entity
@Table(name = "meal_order")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MealOrder extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    /** 대상 월. 그 달 <b>1일</b>로 저장한다 — 범위 조회가 쉽다. */
    @Column(name = "target_month", nullable = false)
    private LocalDate targetMonth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MealOrderStatus status = MealOrderStatus.PENDING;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<MealOrderItem> items = new ArrayList<>();

    public MealOrder(StudentEnrollment enrollment, YearMonth targetMonth) {
        this.academy = enrollment.getAcademy();
        this.enrollment = enrollment;
        this.targetMonth = targetMonth.atDay(1);
        this.status = MealOrderStatus.PENDING;
    }

    public void addItem(LocalDate mealDate, MealType mealType) {
        items.add(new MealOrderItem(this, mealDate, mealType));
    }

    public YearMonth month() {
        return YearMonth.from(targetMonth);
    }

    /** 살아 있는(취소 안 된) 항목. */
    public List<MealOrderItem> activeItems() {
        return items.stream().filter(MealOrderItem::isActive).toList();
    }

    /**
     * 주문 전체 취소.
     *
     * <p>항목이 하나도 안 남으면 주문도 취소로 내린다 — 빈 주문이 목록에
     * "신청 중"으로 남으면 결제·정산에서 셀 대상이 모호해진다.
     */
    public void cancelIfEmpty(Instant at) {
        if (activeItems().isEmpty() && status != MealOrderStatus.CANCELLED) {
            this.status = MealOrderStatus.CANCELLED;
        }
    }
}
