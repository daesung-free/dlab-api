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
        addItem(mealDate, mealType, null);
    }

    /**
     * @param unitPrice <b>신청 시점 단가 스냅샷</b>. 나중에 마스터를 다시 읽어 계산하면
     *                  단가 인상이 과거 주문에 소급된다({@link MealOrderItem#getUnitPrice()})
     */
    public void addItem(LocalDate mealDate, MealType mealType, Integer unitPrice) {
        items.add(new MealOrderItem(this, mealDate, mealType, unitPrice));
    }

    /**
     * 살아 있는 항목의 금액 합계.
     *
     * <p>주문에 총액 컬럼을 두지 않는 이유는 청구와 같다 — 취소가 항목 단위로 들어오는데
     * 컬럼으로 두면 항목 합계와 어긋났을 때 <b>어느 쪽이 맞는지 알 수 없다.</b>
     */
    public int totalAmount() {
        return activeItems().stream().mapToInt(MealOrderItem::amount).sum();
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

    /**
     * 발행된 청구.
     *
     * <p><b>발행 후 취소</b> 때문에 연결해 둔다. 급식은 날짜·끼니 단위로 취소되는데,
     * 청구를 낸 뒤 취소되면 <b>청구액과 실제 이용액이 어긋난다.</b> 그 차액이 곧
     * 환불 대상이고, 연결이 없으면 되짚을 방법이 없다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "billing_id")
    private com.dlab.domain.payment.entity.Billing billing;

    public void assignBilling(com.dlab.domain.payment.entity.Billing billing) {
        this.billing = billing;
        this.status = MealOrderStatus.ISSUED;
    }

    public boolean isBilled() {
        return billing != null;
    }

    /**
     * 발행 후 취소된 만큼의 <b>환불 대상 금액</b>.
     *
     * <p>청구액에서 지금 살아 있는 금액을 뺀다. 청구 전이면 0이다 —
     * 아직 받은 돈이 없으니 돌려줄 것도 없다.
     *
     * <p>⚠️ <b>실제 환불(PG 취소)은 아직 없다.</b> 여기까지는 "얼마가 환불 대상인가"다.
     * 급식은 날짜·끼니 단위 취소라 교습비의 구간·일할 환불을 타지 않는다.
     */
    public int refundableAmount() {
        return billing == null ? 0 : Math.max(0, billing.getBilledAmount() - totalAmount());
    }
}
