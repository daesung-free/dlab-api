package com.dlab.domain.payment.entity;

import com.dlab.domain.audit.AuditLabel;
import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 교습비 할인 정책 (1016 Q3·Q4).
 *
 * <h2>★★ 종류 목록을 몰라도 착수할 수 있어야 한다</h2>
 * 「할인 종류 전체 목록 + 비율/금액 구분」과 「월별로 달라지는 규칙」이 미회신이다.
 * 코드에 박으면 <b>답이 올 때까지 교습비 청구가 멈춘다.</b> 그래서 데이터로 둔다 —
 * {@code penalty_rule} · {@code scholarship_cancel_rule} 과 같은 방식이고, <b>행만 채우면 돈다.</b>
 *
 * <h2>★ 월 범위를 둔다</h2>
 * 1021 답변서에 <b>「1월 30% · 2~3월 20%」</b> 같은 예시가 있어 같은 할인이 달마다 다른
 * 비율로 적용된다. 비워 두면 연중 동일하다.
 * ⚠️ 그 숫자는 <b>예시값이라고 명시</b>됐으므로 시드에 넣지 않았다.
 *
 * <h2>⚠️ 독서실비에는 붙지 않는다</h2>
 * 0820 규정상 독서실비는 할인이 없다. 판정은
 * {@link BillingItemType#isDiscountable()} 한 곳을 쓴다 — 통과시키면 <b>환불 차감이 정가보다
 * 작아져 학원이 손해를 본다.</b>
 *
 * <h2>★ 기본은 꺼져 있다</h2>
 * 돈이 걸린 값이라 확인 전에 돌면 안 된다. 행을 넣어도 {@code active} 를 켜기 전에는
 * 아무 일도 일어나지 않는다.
 */
@Getter
@Entity
@Table(name = "discount_policy")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DiscountPolicy extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 지점. <b>{@code null} 이면 전 지점 공통</b>이다.
     *
     * <p>0821 회신에서 <b>장학 동의서가 지점별로 다를 수 있다</b>고 확인됐고 할인도 같은
     * 성질이라, 공통과 지점별이 함께 설 수 있게 둔다(약관 {@code academy_id} 규약과 같다).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "academy_id")
    private Academy academy;

    /** 기수. 가격이 연도별로 바뀌므로 할인도 연도 축을 갖는다(§7 공통컬럼). */
    @Column(nullable = false)
    private Short year;

    /** ★ enum 이 아니라 문자열이다 — 종류가 늘어나도 마이그레이션이 필요 없다. */
    @Column(nullable = false, length = 40)
    @AuditLabel("할인코드")
    private String code;

    @Column(nullable = false, length = 100)
    @AuditLabel("할인명")
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 10)
    @AuditLabel("할인방식")
    private DiscountType discountType;

    /** {@link DiscountType#RATE} 면 퍼센트, {@link DiscountType#AMOUNT} 면 원. */
    @Column(name = "discount_value", nullable = false)
    @AuditLabel("할인값")
    private int discountValue;

    /** 적용 항목. <b>비워 두면 할인 가능한 항목 전부</b>({@code isDiscountable}). */
    @Enumerated(EnumType.STRING)
    @Column(name = "target_item_type", length = 20)
    private BillingItemType targetItemType;

    /** 적용 시작 월(1~12). {@code null} 이면 연중 동일. */
    @Column(name = "apply_from_month")
    private Short applyFromMonth;

    @Column(name = "apply_to_month")
    private Short applyToMonth;

    @Column(nullable = false)
    @AuditLabel("사용")
    private boolean active = false;

    @Column(length = 500)
    private String note;

    public DiscountPolicy(Academy academy, Short year, String code, String name,
                          DiscountType discountType, int discountValue,
                          BillingItemType targetItemType,
                          Short applyFromMonth, Short applyToMonth, String note) {
        this.academy = academy;
        this.year = year;
        this.code = code;
        this.name = name;
        this.discountType = discountType;
        this.discountValue = discountValue;
        this.targetItemType = targetItemType;
        this.applyFromMonth = applyFromMonth;
        this.applyToMonth = applyToMonth;
        this.note = note;
    }

    /**
     * 이 달에 적용되는가.
     *
     * <p>월 범위가 없으면 연중 적용이다.
     */
    public boolean appliesToMonth(int month) {
        if (applyFromMonth == null) {
            return true;
        }
        return month >= applyFromMonth && month <= applyToMonth;
    }

    /**
     * 이 항목에 붙을 수 있는가.
     *
     * <p>★ <b>항목 자체가 할인 불가면 지정과 무관하게 거부한다</b> — 독서실비를
     * {@code targetItemType} 으로 넣어 두는 실수를 막는다.
     */
    public boolean appliesTo(BillingItemType itemType) {
        if (!itemType.isDiscountable()) {
            return false;
        }
        return targetItemType == null || targetItemType == itemType;
    }

    /** 정가에서 깎을 금액. */
    public int discountAmountOf(int suppliedAmount) {
        return discountType.amountOf(suppliedAmount, discountValue);
    }

    public void change(String name, DiscountType discountType, int discountValue,
                       BillingItemType targetItemType,
                       Short applyFromMonth, Short applyToMonth, String note) {
        this.name = name;
        this.discountType = discountType;
        this.discountValue = discountValue;
        this.targetItemType = targetItemType;
        this.applyFromMonth = applyFromMonth;
        this.applyToMonth = applyToMonth;
        this.note = note;
    }

    public void changeActive(boolean active) {
        this.active = active;
    }
}
