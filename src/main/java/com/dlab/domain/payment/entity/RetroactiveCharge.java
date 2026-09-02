package com.dlab.domain.payment.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 할인 소급 재결제 내역 한 줄 — <b>"어느 달 할인을 얼마나 되받는가"</b>.
 *
 * <h2>환불이 아니라 징수다</h2>
 * 퇴원할 때 그동안 받은 할인을 <b>정상가로 다시 받는다.</b>
 * 판정은 <b>퇴원하는 달이 할인 기간 안이냐 밖이냐</b>로 갈린다(0820 규정, 2026-08-26 확인).
 *
 * <h2>★ 퇴원한 달은 여기 들어오지 않는다</h2>
 * 그 달은 {@code RefundCalculator}가 이미 처리한다 — <b>"납부액 − 정가 × 차감비율"</b>로
 * 계산되고, 할인받았으면 그 결과가 음수(=추가 징수)다.
 * 여기에 또 넣으면 <b>같은 달을 두 번 받는다.</b>
 *
 * <p>헤더(총액·날짜)는 새로 만든 {@link Billing}이 들고 있어서 여기 다시 두지 않는다.
 */
@Getter
@Entity
@Table(name = "retroactive_charge")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RetroactiveCharge extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(name = "year", nullable = false)
    private short year;

    /** 새로 만든 소급 청구. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "charge_billing_id", nullable = false)
    private Billing chargeBilling;

    /** 소급 대상이 된 원 청구. <b>한 번만 소급된다</b> — 두 번이면 학생이 두 번 낸다. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_billing_id", nullable = false)
    private Billing sourceBilling;

    @Column(name = "service_year", nullable = false)
    private short serviceYear;

    @Column(name = "service_month", nullable = false)
    private short serviceMonth;

    /** 그 달 교습비 정가. */
    @Column(name = "supplied_amount", nullable = false)
    private int suppliedAmount;

    /** 실제 납부한 금액(할인 적용분). */
    @Column(name = "paid_amount", nullable = false)
    private int paidAmount;

    /** 정가 − 납부액. <b>독서실비는 할인이 없어 교습비 항목만 대상이다.</b> */
    @Column(name = "charge_amount", nullable = false)
    private int chargeAmount;

    public RetroactiveCharge(Billing chargeBilling, Billing sourceBilling,
                             int suppliedAmount, int paidAmount) {
        this.academy = sourceBilling.getAcademy();
        this.year = sourceBilling.getYear();
        this.chargeBilling = chargeBilling;
        this.sourceBilling = sourceBilling;
        this.serviceYear = sourceBilling.getServiceYear();
        this.serviceMonth = sourceBilling.getServiceMonth();
        this.suppliedAmount = suppliedAmount;
        this.paidAmount = paidAmount;
        this.chargeAmount = Math.max(0, suppliedAmount - paidAmount);
    }

    /** 사람이 읽을 한 줄. 상세 내역 출력이 쓴다. */
    public String describe() {
        return "%d년 %d월 — 정가 %,d원, 납부 %,d원 → 추가 징수 %,d원"
                .formatted(serviceYear, serviceMonth, suppliedAmount, paidAmount, chargeAmount);
    }
}
