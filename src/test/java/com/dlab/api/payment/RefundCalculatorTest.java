package com.dlab.api.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.domain.payment.entity.Billing;
import com.dlab.domain.payment.entity.BillingItem;
import com.dlab.domain.payment.entity.BillingItemType;
import com.dlab.domain.payment.entity.BillingType;
import com.dlab.domain.payment.service.RefundCalculator;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 퇴원 환불 계산 (0820 규정 · I-26).
 *
 * <p>규정이 세 겹으로 얽혀 있다 — <b>구간(교습비)</b>, <b>일할(독서실비)</b>,
 * 그리고 <b>"정상가 기준 차감"</b>이다. 마지막 것이 핵심이라, 할인받은 학생은
 * 환불이 아니라 <b>추가 징수</b>가 나올 수 있다.
 */
class RefundCalculatorTest {

    /** 2026년 3월. 교습일수 31일이라 1/3 = 10.33일, 1/2 = 15.5일이다. */
    static final int MARCH_DAYS = 31;

    private Billing billing(int tuitionSupplied, int tuitionDiscount, int studyRoom) {
        Academy academy = new Academy("T9", "환불테스트", LocalTime.of(9, 0));
        Student student = new Student("RF-0001", "김환불", "010-0000-0000");
        StudentEnrollment enrollment = new StudentEnrollment(
                student, academy, (short) 2026, "2026-0001", null, GradeType.N_SU);

        Billing billing = new Billing(enrollment, "2026년 3월 교습비", BillingType.TUITION,
                tuitionSupplied + studyRoom, tuitionDiscount, null);
        billing.assignServicePeriod((short) 2026, 3);
        billing.addItem(BillingItemType.TUITION, tuitionSupplied, tuitionDiscount);
        billing.addItem(BillingItemType.STUDY_ROOM, studyRoom, 0);
        return billing;
    }

    private List<BillingItem> itemsOf(Billing billing) {
        return billing.activeItems();
    }

    @Nested
    @DisplayName("정가 결제자 — 규정 그대로")
    class FullPrice {

        @Test
        @DisplayName("이용기간 1/3 이내면 교습비 2/3가 환불된다")
        void withinOneThird() {
            Billing b = billing(660_000, 0, 90_000);

            // 10일 사용 — 10×3 = 30 ≤ 31 이므로 1/3 구간
            var result = RefundCalculator.calculate(itemsOf(b), MARCH_DAYS, 10);
            var tuition = result.items().get(0);

            assertThat(tuition.deduction()).isEqualTo(220_000);       // 660,000 × 1/3
            assertThat(tuition.refund()).isEqualTo(440_000);          // 2/3
        }

        @Test
        @DisplayName("1/3을 넘고 1/2 이내면 1/2이 환불된다")
        void withinOneHalf() {
            Billing b = billing(660_000, 0, 90_000);

            // 15일 — 15×3 = 45 > 31 이지만 15×2 = 30 ≤ 31
            var result = RefundCalculator.calculate(itemsOf(b), MARCH_DAYS, 15);

            assertThat(result.items().get(0).deduction()).isEqualTo(330_000);
            assertThat(result.items().get(0).refund()).isEqualTo(330_000);
        }

        @Test
        @DisplayName("★ 1/2을 넘으면 교습비 환불이 없다")
        void beyondHalfNoRefund() {
            Billing b = billing(660_000, 0, 90_000);

            var result = RefundCalculator.calculate(itemsOf(b), MARCH_DAYS, 20);

            assertThat(result.items().get(0).deduction()).isEqualTo(660_000);
            assertThat(result.items().get(0).refund()).isZero();
        }

        @Test
        @DisplayName("독서실비는 구간이 아니라 일할이다 — 같은 날에도 값이 다르다")
        void studyRoomIsDaily() {
            Billing b = billing(660_000, 0, 90_000);

            var result = RefundCalculator.calculate(itemsOf(b), MARCH_DAYS, 10);
            var studyRoom = result.items().get(1);

            // 90,000÷31 = 2,903 (버림) × 10일 = 29,030
            assertThat(studyRoom.deduction()).isEqualTo(29_030);
            assertThat(studyRoom.refund()).isEqualTo(60_970);
        }

        @Test
        @DisplayName("★ 전 기간 사용하면 독서실비 환불이 0이다 — 절사분이 남으면 안 된다")
        void studyRoomFullUsageIsZero() {
            Billing b = billing(660_000, 0, 90_000);

            var result = RefundCalculator.calculate(itemsOf(b), MARCH_DAYS, MARCH_DAYS);

            // 2,903 × 31 = 89,993 이라 그대로면 7원이 환불된다
            assertThat(result.items().get(1).refund()).isZero();
            assertThat(result.refundAmount()).isZero();
        }
    }

    @Nested
    @DisplayName("★ 할인받은 학생 — 차감이 정상가 기준이다")
    class Discounted {

        @Test
        @DisplayName("1/3 이내면 할인받아도 환불이 남는다")
        void refundStillPositive() {
            // 50% 할인 — 330,000원 납부
            Billing b = billing(660_000, 330_000, 90_000);

            var result = RefundCalculator.calculate(itemsOf(b), MARCH_DAYS, 10);
            var tuition = result.items().get(0);

            // ★ 차감은 납부액이 아니라 정가 기준이다
            assertThat(tuition.paidAmount()).isEqualTo(330_000);
            assertThat(tuition.deduction()).isEqualTo(220_000);   // 660,000 × 1/3
            assertThat(tuition.refund()).isEqualTo(110_000);      // 330,000 − 220,000
        }

        @Test
        @DisplayName("★ 1/3을 넘으면 환불이 아니라 추가 징수가 된다")
        void becomesAdditionalPayment() {
            Billing b = billing(660_000, 330_000, 90_000);

            var result = RefundCalculator.calculate(itemsOf(b), MARCH_DAYS, 15);
            var tuition = result.items().get(0);

            // 차감 330,000인데 납부도 330,000 → 교습비 환불 0
            assertThat(tuition.refund()).isZero();
        }

        @Test
        @DisplayName("★★ 규정 예시 재현 — 할인 기간 안에서 퇴원하면 정상가 전액 재결제")
        void matchesRegulationExample() {
            // 규정: "3~5월 할인 → 5월 퇴원 시 할인적용 받은 교습비 모두 정상가 재결제"
            Billing b = billing(660_000, 330_000, 90_000);

            // 1/2을 넘겨 퇴원 → 차감이 정가 전액
            var result = RefundCalculator.calculate(itemsOf(b), MARCH_DAYS, 20);
            var tuition = result.items().get(0);

            assertThat(tuition.deduction()).isEqualTo(660_000);
            // 330,000 − 660,000 = −330,000 → 할인받은 만큼 그대로 더 내야 한다
            assertThat(tuition.refund()).isEqualTo(-330_000);
        }

        @Test
        @DisplayName("★ 음수를 0에서 자르지 않는다 — 자르면 받을 돈이 사라진다")
        void negativeIsNotClamped() {
            Billing b = billing(660_000, 462_000, 90_000);   // 70% 할인

            var result = RefundCalculator.calculate(itemsOf(b), MARCH_DAYS, 20);

            assertThat(result.requiresAdditionalPayment()).isTrue();
            // 교습비 198,000 − 660,000 = −462,000, 독서실비는 전 기간 미만이라 일부 환불
            assertThat(result.refundAmount()).isNegative();
        }

        @Test
        @DisplayName("독서실비에는 할인이 붙지 않는다 — 넣어도 무시된다")
        void studyRoomDiscountIgnored() {
            Academy academy = new Academy("T9", "환불테스트", LocalTime.of(9, 0));
            Student student = new Student("RF-0002", "박환불", "010-0000-0000");
            StudentEnrollment enrollment = new StudentEnrollment(
                    student, academy, (short) 2026, "2026-0002", null, GradeType.N_SU);
            Billing b = new Billing(enrollment, "3월", BillingType.TUITION, 90_000, 0, null);

            BillingItem item = b.addItem(BillingItemType.STUDY_ROOM, 90_000, 45_000);

            assertThat(item.getDiscountAmount()).isZero();
            assertThat(item.getBilledAmount()).isEqualTo(90_000);
        }
    }

    @Nested
    @DisplayName("경계")
    class Guards {

        @Test
        @DisplayName("★ 2월은 27일이라 구간 경계가 달라진다 — 달력(28)으로 재면 틀린다")
        void februaryUsesTeachingDays() {
            Billing b = billing(660_000, 0, 90_000);

            // 9일: 9×3 = 27 ≤ 27 → 1/3 구간 (달력 28일이면 27 ≤ 28 로 같지만)
            assertThat(RefundCalculator.calculate(itemsOf(b), 27, 9).items().get(0).refund())
                    .isEqualTo(440_000);
            // 10일: 10×3 = 30 > 27 → 1/2 구간. 달력 28일이었다면 30 > 28 로 같은 결과지만
            // 14일에서 갈린다 — 14×2 = 28 > 27(1/2 초과) vs ≤ 28(1/2 이내)
            assertThat(RefundCalculator.calculate(itemsOf(b), 27, 14).items().get(0).refund())
                    .isZero();
            assertThat(RefundCalculator.calculate(itemsOf(b), 28, 14).items().get(0).refund())
                    .isEqualTo(330_000);
        }

        @Test
        @DisplayName("사용일수가 교습일수를 넘으면 교습일수로 맞춘다")
        void usedDaysCapped() {
            Billing b = billing(660_000, 0, 90_000);

            var over = RefundCalculator.calculate(itemsOf(b), MARCH_DAYS, 40);
            var exact = RefundCalculator.calculate(itemsOf(b), MARCH_DAYS, MARCH_DAYS);

            assertThat(over.refundAmount()).isEqualTo(exact.refundAmount());
        }

        @Test
        @DisplayName("첫날 퇴원이면 전액에 가깝게 돌아온다")
        void dayZero() {
            Billing b = billing(660_000, 0, 90_000);

            var result = RefundCalculator.calculate(itemsOf(b), MARCH_DAYS, 0);

            assertThat(result.items().get(0).refund()).isEqualTo(440_000);   // 교습비는 2/3가 상한
            assertThat(result.items().get(1).refund()).isEqualTo(90_000);    // 독서실비는 전액
        }

        @Test
        @DisplayName("상세 내역이 항목별로 남는다 — 추가결제 내역 출력이 이걸 쓴다")
        void detailIsRecorded() {
            Billing b = billing(660_000, 330_000, 90_000);

            var result = RefundCalculator.calculate(itemsOf(b), MARCH_DAYS, 20);

            assertThat(result.detail())
                    .contains("이용기간 1/2 초과")
                    .contains("추가 징수")
                    .contains("일할");
        }
    }
}
