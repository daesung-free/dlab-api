package com.dlab.api.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.payment.entity.Billing;
import com.dlab.domain.payment.entity.BillingType;
import com.dlab.domain.payment.entity.PaymentMethod;
import com.dlab.domain.payment.entity.Refund;
import com.dlab.domain.payment.entity.RefundPayout;
import com.dlab.domain.payment.entity.RefundStatus;
import com.dlab.domain.payment.entity.SeatType;
import com.dlab.domain.payment.entity.TuitionMonth;
import com.dlab.domain.payment.entity.TuitionPrice;
import com.dlab.domain.payment.service.BillingService;
import com.dlab.domain.payment.service.RefundService;
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
 * 환불 집행 (0820 규정 · 디랩 10/12 회신).
 *
 * <h2>★★ 지키려는 것 셋</h2>
 * <ul>
 *   <li><b>추가 징수를 0 으로 자르지 않을 것.</b> 정상가 기준 차감이라 할인받은 학생은
 *       차감이 납부액을 넘는다 — 자르면 받아야 할 돈이 사라지고 데스크는 알 방법이 없다</li>
 *   <li><b>급식비를 거절할 것.</b> 업체 명의 TID 라 학원 경로로 취소하면
 *       남의 사업자 매출을 우리가 취소하는 꼴이 된다</li>
 *   <li><b>수단에 따라 갈릴 것.</b> 카드는 PG 취소, 가상계좌는 이체 대상 적재</li>
 * </ul>
 *
 * <p>⚠️ KCP 실호출은 자격증명이 없어 검증할 수 없다. 카드 건은 <b>PG 거래번호가 없어
 * 실패로 떨어지는 경로</b>까지만 고정한다 — 그 실패가 「환불했다」로 오인되지 않는 것이
 * 지금 확인할 수 있는 가장 중요한 부분이다.
 */
@SpringBootTest
@Transactional
class RefundExecutionTest {

    @Autowired TuitionBillingService tuitionBillingService;
    @Autowired BillingService billingService;
    @Autowired RefundService refundService;
    @Autowired EntityManager em;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    static final short YEAR = 2093;
    static final YearMonth MONTH = YearMonth.of(YEAR, 3);

    Academy academy;
    StudentEnrollment enrollment;
    AuthPrincipal admin;

    @BeforeEach
    void setUp() {
        academy = new Academy("RF1", "환불테스트", LocalTime.of(9, 0));
        em.persist(academy);
        Student s = new Student("RF-0001", "환불학생", "010-0000-0000");
        em.persist(s);
        enrollment = new StudentEnrollment(s, academy, YEAR, "2093-0001", null, GradeType.N_SU);
        em.persist(enrollment);

        // N수 일반좌석 750,000 = 교습비 660,000 + 독서실비 90,000
        em.persist(TuitionPrice.common(YEAR, GradeType.N_SU, SeatType.GENERAL, 660_000, 90_000));
        em.persist(TuitionMonth.common(YEAR, 3, 30));
        em.flush();

        admin = AuthPrincipal.of(1L, "EMPLOYEE", null, List.of(Role.SUPER_ADMIN), true);
    }

    private Billing issue(int discountRate) {
        Billing b = tuitionBillingService.issueMonthly(admin, enrollment.getId(), MONTH,
                SeatType.GENERAL, discountRate, null, null);
        em.flush();
        return b;
    }

    @Test
    @DisplayName("★ 가상계좌 입금분은 계좌 이체 대상으로 적재된다 — 우리가 이체하지 않는다")
    void vbankBecomesTransferTarget() {
        Billing b = issue(0);
        billingService.pay(admin, b.getId(), 750_000, PaymentMethod.VBANK);
        em.flush();

        // 30일 중 10일만 썼다 — 1/3 이내라 2/3 환불 구간
        Refund refund = refundService.execute(admin, b.getId(), 10);

        assertThat(refund.getMethod()).isEqualTo(RefundPayout.BANK_TRANSFER);
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING);
        assertThat(refund.getRefundAmount()).isPositive();
        // 근거가 남아야 「왜 이 금액인가」에 답할 수 있다
        assertThat(refund.getDetail()).isNotBlank();
    }

    @Test
    @DisplayName("★★ 할인 건의 추가 징수가 음수로 남는다 — 0 으로 자르면 받을 돈이 사라진다")
    void additionalPaymentStaysNegative() {
        // 50% 할인 → 420,000 납부. 정상가 차감이라 많이 쓰면 차감이 납부액을 넘는다
        Billing b = issue(50);
        billingService.pay(admin, b.getId(), b.getBilledAmount(), PaymentMethod.VBANK);
        em.flush();

        // 30일 중 25일 — 1/2 초과라 교습비 차감이 전액(정상가 660,000)이다
        Refund refund = refundService.execute(admin, b.getId(), 25);

        assertThat(refund.getRefundAmount()).isNegative();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.ADDITIONAL_PAYMENT);
        assertThat(refund.requiresAdditionalPayment()).isTrue();
        // 돌려줄 것이 없으므로 PG 에 걸지 않는다
        assertThat(refund.getMethod()).isEqualTo(RefundPayout.NONE);
    }

    @Test
    @DisplayName("★★ 급식비는 거절한다 — 업체 명의 TID 라 학원 경로로 취소할 수 없다")
    void mealBillingIsRejected() {
        Billing meal = em.merge(new Billing(enrollment, "3월 급식비", BillingType.MEAL,
                100_000, 0, null));
        meal.assignServicePeriod(YEAR, 3);
        em.flush();

        assertThatThrownBy(() -> refundService.execute(admin, meal.getId(), 10))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("급식");
    }

    @Test
    @DisplayName("★ 카드 수납인데 PG 거래번호가 없으면 실패로 남는다 — 「환불했다」로 오인하면 안 된다")
    void cardWithoutPgTidFailsVisibly() {
        Billing b = issue(0);
        billingService.pay(admin, b.getId(), 750_000, PaymentMethod.CARD);
        em.flush();

        Refund refund = refundService.execute(admin, b.getId(), 10);

        assertThat(refund.getMethod()).isEqualTo(RefundPayout.CARD_CANCEL);
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(refund.getFailReason()).contains("PG 거래번호");
    }

    @Test
    @DisplayName("★ 같은 청구에 두 번 환불할 수 없다 — 실패분만 다시 걸 수 있다")
    void cannotRefundTwiceButFailedIsRetryable() {
        Billing b = issue(0);
        billingService.pay(admin, b.getId(), 750_000, PaymentMethod.VBANK);
        em.flush();

        refundService.execute(admin, b.getId(), 10);
        em.flush();

        assertThatThrownBy(() -> refundService.execute(admin, b.getId(), 10))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 환불");
    }

    @Test
    @DisplayName("이체 완료 표시가 되고, 카드 건에는 쓸 수 없다")
    void markTransferredOnlyForBankTransfer() {
        Billing b = issue(0);
        billingService.pay(admin, b.getId(), 750_000, PaymentMethod.VBANK);
        em.flush();
        Refund refund = refundService.execute(admin, b.getId(), 10);
        em.flush();

        refundService.markTransferred(admin, refund.getId());
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.DONE);
        assertThat(refund.getProcessedAt()).isNotNull();
    }

    @Test
    @DisplayName("미리보기는 저장하지 않는다 — 누르기 전에 금액을 봐야 한다")
    void previewDoesNotPersist() {
        Billing b = issue(0);
        billingService.pay(admin, b.getId(), 750_000, PaymentMethod.VBANK);
        em.flush();

        var result = refundService.preview(admin, b.getId(), 10);
        em.flush();

        assertThat(result.refundAmount()).isPositive();
        Long count = em.createQuery("SELECT COUNT(r) FROM Refund r", Long.class)
                .getSingleResult();
        assertThat(count).isZero();
    }
}
