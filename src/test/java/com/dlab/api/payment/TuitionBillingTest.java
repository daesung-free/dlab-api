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

    // ── 할인 정책 적용 (디랩 「지점별·월별 입력·수정·적용」) ──────────

    /**
     * ★★ 정책 코드를 주면 <b>서버가 할인율을 찾는다</b> — 데스크가 고르지 않는다.
     *
     * <p>빠져 있던 것은 <b>「적용」 하나</b>였다. 정책 테이블과 산출 로직은 이미 있었는데
     * <b>청구가 그것을 조회하지 않아</b>, 데스크가 <b>할인율을 눈으로 보고 넣고 있었다.</b>
     */
    @Test
    @DisplayName("★★ 정책 코드로 발행하면 서버가 할인율을 찾는다 — 데스크가 고르지 않는다")
    void issuesByPolicy() {
        activePolicy("SIBLING", DiscountType.RATE, 30, null, null);

        Billing billing = billingService.issueMonthlyByPolicy(admin, enrollment.getId(),
                YearMonth.of(YEAR, 3), SeatType.GENERAL, List.of("SIBLING"), null, null, null);
        em.flush();

        // 교습비 660,000 의 30% = 198,000 · 독서실비 90,000 은 할인 없음
        assertThat(billing.getDiscountAmount()).isEqualTo(198_000);
        assertThat(billing.getSuppliedAmount()).isEqualTo(750_000);
        assertThat(billing.getBilledAmount()).isEqualTo(552_000);
    }

    /**
     * ★★ <b>독서실비는 분모가 아니다</b> — 규정상 할인이 없다(§2).
     *
     * <p>월 합계(750,000)로 율을 구하면 <b>교습비 할인이 덜 깎인다.</b> 교습비(660,000)로
     * 구해야 30% 가 198,000 이 된다.
     */
    @Test
    @DisplayName("★★ 독서실비는 할인 분모에서 빠진다 — 월 합계로 나누면 덜 깎인다")
    void studyRoomFeeIsNotDiscounted() {
        activePolicy("HALF", DiscountType.RATE, 50, null, null);

        Billing billing = billingService.issueMonthlyByPolicy(admin, enrollment.getId(),
                YearMonth.of(YEAR, 3), SeatType.GENERAL, List.of("HALF"), null, null, null);
        em.flush();

        assertThat(billing.getDiscountAmount())
                .as("★ 교습비 660,000 의 50% 다 — 750,000 의 50%(375,000)가 아니다")
                .isEqualTo(330_000);
    }

    /**
     * ★★ 월 범위가 있는 정책은 <b>청구 대상 월</b>로 찾는다 — 발행한 달이 아니다.
     *
     * <p>⚠️ 발행한 달로 찾으면 <b>3월분을 2월에 미리 발행할 때 2월 할인이 붙는다.</b>
     * 1021 의 「1월 30% · 2~3월 20%」가 그 모양이다.
     */
    @Test
    @DisplayName("★★ 월 범위는 청구 대상 월로 찾는다 — 발행한 달이 아니다")
    void monthRangeUsesServiceMonth() {
        // 4월에만 20%
        activePolicy("SPRING", DiscountType.RATE, 20, (short) 4, (short) 4);

        Billing march = billingService.issueMonthlyByPolicy(admin, enrollment.getId(),
                YearMonth.of(YEAR, 3), SeatType.GENERAL, List.of("SPRING"), null, null, null);
        Billing april = billingService.issueMonthlyByPolicy(admin, enrollment.getId(),
                YearMonth.of(YEAR, 4), SeatType.GENERAL, List.of("SPRING"), null, null, null);
        em.flush();

        assertThat(march.getDiscountAmount()).as("★ 3월은 범위 밖이다").isZero();
        assertThat(april.getDiscountAmount()).isEqualTo(132_000);
    }

    /**
     * ★ 할인이 붙지 않았으면 <b>근거를 비운다</b>.
     *
     * <p>코드를 적어 두면 <b>「정책이 붙었는데 0원」</b>으로 읽혀, 담당자가 정책을
     * 의심하게 된다.
     */
    @Test
    @DisplayName("★ 할인 0이면 근거를 비운다 — 「정책이 붙었는데 0원」으로 읽힌다")
    void sourceIsBlankWhenNoDiscount() {
        Billing billing = billingService.issueMonthlyByPolicy(admin, enrollment.getId(),
                YearMonth.of(YEAR, 3), SeatType.GENERAL, List.of("없는코드"), null, null, null);
        em.flush();

        assertThat(billing.getDiscountAmount()).isZero();
        assertThat(billing.getDiscountSource()).isNull();
    }

    /**
     * ★★ 꺼진 정책은 안 붙는다 — {@code active} 가 <b>기본 꺼짐</b>이다.
     *
     * <p>⚠️ 넣자마자 도는 구조면 <b>검증 전 정책이 실제 청구에 붙는다.</b>
     */
    @Test
    @DisplayName("★★ 꺼진 정책은 안 붙는다 — 검증 전 정책이 청구에 붙으면 안 된다")
    void inactivePolicyIsIgnored() {
        DiscountPolicy policy = new DiscountPolicy(academy, YEAR, "OFF", "꺼진 할인",
                DiscountType.RATE, 50, BillingItemType.TUITION, null, null, null);
        em.persist(policy);   // ★ active 기본값 false
        em.flush();

        Billing billing = billingService.issueMonthlyByPolicy(admin, enrollment.getId(),
                YearMonth.of(YEAR, 3), SeatType.GENERAL, List.of("OFF"), null, null, null);
        em.flush();

        assertThat(billing.getDiscountAmount()).isZero();
    }

    /**
     * ★★ 여러 정책은 <b>합연산</b>이다 (디랩 1021 회신).
     *
     * <p>회신 원문: <b>「다중 할인 혜택 적용의 경우 합연산으로 진행됩니다 … 장학 10% +
     * 친구추천 10% + 경쟁사 이전 10% 가정했을 때 모든 혜택을 더한 비율(30%)을 교습비에
     * 적용」</b>
     *
     * <h2>★ 곱연산이면 값이 어긋난다</h2>
     * 차례로 곱하면 {@code 0.9 × 0.9 × 0.9 = 0.729} 로 <b>27.1%</b> 가 되어 30% 와
     * 다르다. 그리고 <b>적용 순서에 따라 값이 달라진다</b> — 더하는 데는 순서가 없다.
     */
    @Test
    @DisplayName("★★ 여러 할인은 합연산이다 — 곱하면 27.1%가 되어 30%와 어긋난다")
    void multipleDiscountsAreSummed() {
        activePolicy("SCHOLAR", DiscountType.RATE, 10, null, null);
        activePolicy("REFERRAL", DiscountType.RATE, 10, null, null);
        activePolicy("SWITCH", DiscountType.RATE, 10, null, null);

        Billing billing = billingService.issueMonthlyByPolicy(admin, enrollment.getId(),
                YearMonth.of(YEAR, 3), SeatType.GENERAL,
                List.of("SCHOLAR", "REFERRAL", "SWITCH"), null, null, null);
        em.flush();

        // 교습비 660,000 의 30% = 198,000 (★ 곱연산이면 178,860 이다)
        assertThat(billing.getDiscountAmount()).isEqualTo(198_000);
        assertThat(billing.getBilledAmount()).isEqualTo(552_000);
    }

    /**
     * ★★ 근거에 <b>내역</b>을 남긴다.
     *
     * <p>⚠️ 비율만 남기면 <b>어느 혜택이 얼마였는지 재현되지 않는다.</b> 1021 문서가
     * <b>「장학 10% + 친구추천 10% + 경쟁사이전 10% = 30%」처럼 남긴다</b>고 적는다.
     */
    @Test
    @DisplayName("★★ 근거에 내역을 남긴다 — 비율만 남기면 어느 혜택이었는지 모른다")
    void sourceCarriesBreakdown() {
        activePolicy("SCHOLAR", DiscountType.RATE, 10, null, null);
        activePolicy("REFERRAL", DiscountType.RATE, 20, null, null);

        Billing billing = billingService.issueMonthlyByPolicy(admin, enrollment.getId(),
                YearMonth.of(YEAR, 3), SeatType.GENERAL,
                List.of("SCHOLAR", "REFERRAL"), null, null, null);
        em.flush();

        assertThat(billing.getDiscountSource())
                .contains("10%").contains("20%").contains("= 30%");
    }

    /**
     * ★★ 합이 100% 를 넘으면 <b>잘린다 — 음수 청구가 나오면 안 된다.</b>
     *
     * <p>⚠️ 조용히 자르면 <b>데스크가 할인을 과하게 걸었다는 것을 모른다</b> —
     * {@code overCapped} 가 그 사실을 드러낸다.
     */
    @Test
    @DisplayName("★★ 합이 100%를 넘으면 잘린다 — 음수 청구가 나오면 안 된다")
    void sumIsCappedAtHundred() {
        activePolicy("BIG1", DiscountType.RATE, 70, null, null);
        activePolicy("BIG2", DiscountType.RATE, 50, null, null);

        Billing billing = billingService.issueMonthlyByPolicy(admin, enrollment.getId(),
                YearMonth.of(YEAR, 3), SeatType.GENERAL,
                List.of("BIG1", "BIG2"), null, null, null);
        em.flush();

        assertThat(billing.getDiscountAmount())
                .as("★ 교습비 전액까지만 — 독서실비는 할인 대상이 아니다")
                .isEqualTo(660_000);
        assertThat(billing.getBilledAmount()).isEqualTo(90_000);
    }

    /**
     * ★★ 금액 할인(정액) 정책은 <b>거부한다 — 조용히 사라지기 때문이다.</b>
     *
     * <h2>⚠️ 교습비는 율로만 계산된다</h2>
     * 1일 단가·일할 계산이 전부 율 기반이라 <b>금액 할인을 실을 자리가 없다.</b>
     * 통과시키면 <b>할인을 걸었는데 안 깎인다</b> — 데스크는 화면에서 알 방법이 없다.
     *
     * <p>★ 1021 문서도 금액 항목은 <b>「Q3 답에 있으면 규칙을 확인받을 것」</b>으로
     * 남겼다 — 지금 디랩 할인은 전부 비율(10~70%)이다.
     */
    @Test
    @DisplayName("★★ 금액 할인 정책은 거부한다 — 통과시키면 조용히 사라진다")
    void amountPolicyIsRejected() {
        activePolicy("CASH", DiscountType.AMOUNT, 50_000, null, null);

        assertThatThrownBy(() -> billingService.issueMonthlyByPolicy(admin, enrollment.getId(),
                YearMonth.of(YEAR, 3), SeatType.GENERAL, List.of("CASH"), null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("비율로 계산");
    }

    /** 켜진 정책을 만든다. ★ active 가 기본 꺼짐이라 따로 켠다. */
    private void activePolicy(String code, DiscountType type, int value,
                              Short fromMonth, Short toMonth) {
        DiscountPolicy policy = new DiscountPolicy(academy, YEAR, code, code + " 할인",
                type, value, BillingItemType.TUITION, fromMonth, toMonth, null);
        em.persist(policy);
        em.flush();
        em.createNativeQuery("UPDATE discount_policy SET active = true WHERE id = :id")
                .setParameter("id", policy.getId())
                .executeUpdate();
        em.flush();
        em.clear();
    }
}
