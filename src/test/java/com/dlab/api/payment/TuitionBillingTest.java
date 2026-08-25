package com.dlab.api.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.payment.entity.*;
import com.dlab.domain.payment.service.RefundCalculator;
import com.dlab.domain.payment.service.TuitionBillingService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 교습비 청구 발행 (F-4.8-1 · 0820 규정).
 *
 * <p>가격 마스터 → 청구 → (퇴원 시) 환불이 한 줄로 이어지는지 확인한다.
 * 지키려는 것은 셋 — <b>월 전체는 정액</b>, <b>입학이 1개월 미만이면 다음달까지</b>,
 * <b>항목이 쪼개져 환불 계산이 바로 붙는다</b>.
 */
@SpringBootTest
@Transactional
class TuitionBillingTest {

    @Autowired TuitionBillingService billingService;
    @Autowired EntityManager em;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    static final short YEAR = 2098;

    Academy academy;
    StudentEnrollment enrollment;
    AuthPrincipal admin;

    @BeforeEach
    void setUp() {
        academy = new Academy("B1", "청구테스트", LocalTime.of(9, 0));
        em.persist(academy);

        Student student = new Student("BL-0001", "김청구", "010-0000-0000");
        em.persist(student);
        enrollment = new StudentEnrollment(student, academy, YEAR, "2098-0001", null,
                GradeType.N_SU);
        em.persist(enrollment);

        // N수 일반좌석 750,000 = 교습비 660,000 + 독서실비 90,000
        em.persist(TuitionPrice.common(YEAR, GradeType.N_SU, SeatType.GENERAL, 660_000, 90_000));
        em.persist(TuitionMonth.common(YEAR, 3, 31));
        em.persist(TuitionMonth.common(YEAR, 4, 30));
        em.flush();

        admin = AuthPrincipal.of(1L, "EMPLOYEE", null, List.of(Role.SUPER_ADMIN), true);
    }

    // ─────────────────────────────────────────── 월 정액

    @Test
    @DisplayName("★ 월 전체는 정액이다 — 1일 단가 × 일수로 하면 절사분만큼 모자란다")
    void fullMonthIsFlatAmount() {
        Billing billing = billingService.issueMonthly(admin, enrollment.getId(),
                YearMonth.of(YEAR, 3), SeatType.GENERAL, 0, null, null);

        // 21,290 × 31 = 659,990 이 아니라 750,000 이어야 한다
        assertThat(billing.getBilledAmount()).isEqualTo(750_000);
        assertThat(billing.getSuppliedAmount()).isEqualTo(750_000);
        assertThat(billing.getDiscountAmount()).isZero();
    }

    @Test
    @DisplayName("★ 교습비·독서실비가 항목으로 쪼개진다 — 청구 금액은 한 줄 그대로")
    void itemsAreSplitButBillingStaysOneLine() {
        Billing billing = billingService.issueMonthly(admin, enrollment.getId(),
                YearMonth.of(YEAR, 3), SeatType.GENERAL, 0, null, null);

        assertThat(billing.getBilledAmount()).isEqualTo(750_000);   // 키오스크가 보는 값
        assertThat(billing.activeItems())
                .extracting(BillingItem::getItemType, BillingItem::getBilledAmount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(BillingItemType.TUITION, 660_000),
                        org.assertj.core.groups.Tuple.tuple(BillingItemType.STUDY_ROOM, 90_000));
        assertThat(billing.itemsMatchBilledAmount()).isTrue();
    }

    @Test
    @DisplayName("★ 할인은 교습비에만 붙는다 — 독서실비는 정가 그대로")
    void discountAppliesToTuitionOnly() {
        Billing billing = billingService.issueMonthly(admin, enrollment.getId(),
                YearMonth.of(YEAR, 3), SeatType.GENERAL, 50, null, null);

        assertThat(billing.getBilledAmount()).isEqualTo(420_000);   // 330,000 + 90,000
        assertThat(billing.getDiscountAmount()).isEqualTo(330_000);
        assertThat(billing.activeItems().get(0).getBilledAmount()).isEqualTo(330_000);
        assertThat(billing.activeItems().get(1).getBilledAmount()).isEqualTo(90_000);
    }

    @Test
    @DisplayName("중도 입학은 남은 교습일수만큼만 청구된다")
    void proratedByRemainingDays() {
        Billing billing = billingService.issueMonthly(admin, enrollment.getId(),
                YearMonth.of(YEAR, 3), SeatType.GENERAL, 0, 10, null);

        // 교습비 21,290 × 10 = 212,900 / 독서실비 2,903 × 10 = 29,030
        assertThat(billing.activeItems().get(0).getBilledAmount()).isEqualTo(212_900);
        assertThat(billing.activeItems().get(1).getBilledAmount()).isEqualTo(29_030);
        assertThat(billing.getBilledAmount()).isEqualTo(241_930);
    }

    // ─────────────────────────────────────────── 입학

    @Test
    @DisplayName("1일 입학이면 청구가 한 건이다 — 다음달까지 받을 이유가 없다")
    void admissionOnFirstDayIssuesOneBilling() {
        List<Billing> issued = billingService.issueOnAdmission(admin, enrollment.getId(),
                LocalDate.of(YEAR, 3, 1), SeatType.GENERAL, 0, null, null);

        assertThat(issued).hasSize(1);
        assertThat(issued.get(0).getBilledAmount()).isEqualTo(750_000);
    }

    @Test
    @DisplayName("★★ 1일 이후 입학이면 당월 일할 + 다음달 정액 두 건이 나간다 (규정)")
    void midMonthAdmissionIssuesTwoBillings() {
        List<Billing> issued = billingService.issueOnAdmission(admin, enrollment.getId(),
                LocalDate.of(YEAR, 3, 22), SeatType.GENERAL, 0, 10, null);

        assertThat(issued).hasSize(2);
        // 당월 — 10일분
        assertThat(issued.get(0).getServiceMonth()).isEqualTo((short) 3);
        assertThat(issued.get(0).getBilledAmount()).isEqualTo(241_930);
        // 다음달 — 정액
        assertThat(issued.get(1).getServiceMonth()).isEqualTo((short) 4);
        assertThat(issued.get(1).getBilledAmount()).isEqualTo(750_000);
    }

    @Test
    @DisplayName("남은 일수를 안 주면 제안값으로 채운다 — 확정값이 아니라 제안이다")
    void suggestsRemainingDaysWhenOmitted() {
        // 3월 22일 입학 → 남은 달력일 10일, 교습일수 31일 → min(10, 31) = 10
        assertThat(billingService.suggestRemainingDays(academy.getId(),
                LocalDate.of(YEAR, 3, 22))).isEqualTo(10);

        List<Billing> issued = billingService.issueOnAdmission(admin, enrollment.getId(),
                LocalDate.of(YEAR, 3, 22), SeatType.GENERAL, 0, null, null);

        assertThat(issued.get(0).getBilledAmount()).isEqualTo(241_930);
    }

    // ─────────────────────────────────────────── 중복·경계

    @Test
    @DisplayName("★ 같은 달을 두 번 발행할 수 없다 — 미납액이 두 배로 잡힌다")
    void rejectsDuplicateIssue() {
        billingService.issueMonthly(admin, enrollment.getId(), YearMonth.of(YEAR, 3),
                SeatType.GENERAL, 0, null, null);
        em.flush();

        assertThatThrownBy(() -> billingService.issueMonthly(admin, enrollment.getId(),
                YearMonth.of(YEAR, 3), SeatType.GENERAL, 0, null, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.BILLING_ALREADY_ISSUED);
    }

    @Test
    @DisplayName("교습일수가 등록되지 않은 달은 청구할 수 없다 — 달력으로 때우지 않는다")
    void missingTeachingDaysBlocksIssue() {
        assertThatThrownBy(() -> billingService.issueMonthly(admin, enrollment.getId(),
                YearMonth.of(YEAR, 9), SeatType.GENERAL, 0, null, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TEACHING_DAYS_NOT_REGISTERED);
    }

    @Test
    @DisplayName("가격이 없는 좌석유형은 청구할 수 없다")
    void missingPriceBlocksIssue() {
        assertThatThrownBy(() -> billingService.issueMonthly(admin, enrollment.getId(),
                YearMonth.of(YEAR, 3), SeatType.SINGLE, 0, null, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TUITION_PRICE_NOT_FOUND);
    }

    // ─────────────────────────────────────────── 환불까지 이어지는지

    @Test
    @DisplayName("★★ 발행한 청구가 그대로 환불 계산으로 넘어간다")
    void issuedBillingFlowsIntoRefund() {
        Billing billing = billingService.issueMonthly(admin, enrollment.getId(),
                YearMonth.of(YEAR, 3), SeatType.GENERAL, 0, null, null);

        // 10일 쓰고 퇴원 — 1/3 구간
        var result = RefundCalculator.calculate(billing.activeItems(), 31, 10);

        assertThat(result.items().get(0).refund()).isEqualTo(440_000);   // 교습비 2/3
        assertThat(result.items().get(1).refund()).isEqualTo(60_970);    // 독서실비 일할
        assertThat(result.requiresAdditionalPayment()).isFalse();
    }

    @Test
    @DisplayName("★★ 할인 청구는 퇴원 시점에 따라 추가 징수가 된다")
    void discountedBillingCanRequireAdditionalPayment() {
        Billing billing = billingService.issueMonthly(admin, enrollment.getId(),
                YearMonth.of(YEAR, 3), SeatType.GENERAL, 50, null, null);

        // 1/2을 넘겨 퇴원 → 정상가 전액 차감
        var result = RefundCalculator.calculate(billing.activeItems(), 31, 20);

        // 교습비 330,000 납부 − 660,000 차감 = −330,000
        assertThat(result.items().get(0).refund()).isEqualTo(-330_000);
        assertThat(result.requiresAdditionalPayment()).isTrue();
    }
}
