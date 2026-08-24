package com.dlab.api.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.domain.payment.entity.SeatType;
import com.dlab.domain.payment.entity.TuitionPrice;
import com.dlab.domain.payment.service.DailyFeeCalculator;
import com.dlab.domain.user.entity.GradeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 1일 교습비 자동계산 (0820 요청).
 *
 * <p><b>클라이언트가 손으로 채운 표를 그대로 재현하는지</b>가 이 테스트의 목적이다.
 * 값이 하나라도 어긋나면 중도 입학 결제·퇴원 환불·장학 재결제가 전부 어긋난다.
 */
class DailyFeeCalculatorTest {

    @Nested
    @DisplayName("받은 표 재현 — 일반좌석 교습비 660,000")
    class GeneralTuition {

        /**
         * 표의 정상가 행 그대로다. <b>2월이 27일, 9월이 29일</b>인 것에 주의 —
         * 달력이 아니라 교습일수다.
         */
        @ParameterizedTest(name = "{0}일 → {1}원")
        @CsvSource({
                "31, 21290",   // 12·1·3·5·7·8·10·11월
                "30, 22000",   // 4·6월
                "29, 22758",   // 9월
                "27, 24444"    // 2월
        })
        void matchesTable(int days, int expected) {
            assertThat(DailyFeeCalculator.perDay(660_000, days)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "31일 {0}% 할인 → {1}원")
        @CsvSource({
                "0, 21290", "10, 19161", "20, 17032", "30, 14903", "50, 10645", "70, 6387"
        })
        void matchesDiscountRow31(int rate, int expected) {
            int monthly = DailyFeeCalculator.discounted(660_000, rate);
            assertThat(DailyFeeCalculator.perDay(monthly, 31)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "27일(2월) {0}% 할인 → {1}원")
        @CsvSource({
                "0, 24444", "10, 22000", "20, 19555", "30, 17111", "50, 12222", "70, 7333"
        })
        void matchesDiscountRow27(int rate, int expected) {
            int monthly = DailyFeeCalculator.discounted(660_000, rate);
            assertThat(DailyFeeCalculator.perDay(monthly, 27)).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("받은 표 재현 — 그 외 상품")
    class OtherProducts {

        @ParameterizedTest(name = "재학생 400,000 / {0}일 → {1}원")
        @CsvSource({"31, 12903", "30, 13333", "29, 13793"})
        void studentTuition(int days, int expected) {
            assertThat(DailyFeeCalculator.perDay(400_000, days)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "독서실비 90,000 / {0}일 → {1}원")
        @CsvSource({"31, 2903", "30, 3000", "29, 3103", "27, 3333"})
        void studyRoom(int days, int expected) {
            assertThat(DailyFeeCalculator.perDay(90_000, days)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "1인실 독서실비 190,000 / {0}일 → {1}원")
        @CsvSource({"31, 6129", "30, 6333", "29, 6551"})
        void singleRoom(int days, int expected) {
            assertThat(DailyFeeCalculator.perDay(190_000, days)).isEqualTo(expected);
        }

        @Test
        @DisplayName("★ 1인실 2월 독서실비는 표가 틀렸다 — 6,129가 아니라 7,037이다")
        void singleRoomFebruaryTableIsWrong() {
            // 표의 H14가 6,129인데 그건 바로 위 31일 칸 값이다.
            // 190,000÷27 = 7,037 — 확인 요청해둔 항목이라 우리 계산이 맞는 쪽을 고정해둔다
            assertThat(DailyFeeCalculator.perDay(190_000, 27)).isEqualTo(7037);
        }

        @ParameterizedTest(name = "목동 1인실 820,000 / {0}일 → {1}원")
        @CsvSource({"31, 26451", "30, 27333", "29, 28275", "27, 30370"})
        void mokdongSingle(int days, int expected) {
            assertThat(DailyFeeCalculator.perDay(820_000, days)).isEqualTo(expected);
        }

        @Test
        @DisplayName("★ 대구 9월은 표가 올림이다 — 버림 기준으로는 28,344다")
        void daeguSeptemberRoundsUpInTable() {
            // 822,000÷29 = 28,344.8. 표는 28,345(올림)인데 다른 지점은 전부 버림이다.
            // 절사 규칙 확인 요청해둔 항목 — 회신에 따라 Rounding만 바꾸면 된다
            assertThat(DailyFeeCalculator.perDay(822_000, 29)).isEqualTo(28_344);
            assertThat(DailyFeeCalculator.perDay(822_000, 29, DailyFeeCalculator.Rounding.HALF_UP))
                    .isEqualTo(28_345);
        }
    }

    @Nested
    @DisplayName("할인")
    class Discount {

        @ParameterizedTest(name = "{0}% → {1}원")
        @CsvSource({
                "0, 660000", "10, 594000", "20, 528000", "30, 462000", "50, 330000", "70, 198000"
        })
        void matchesTableMonthly(int rate, int expected) {
            assertThat(DailyFeeCalculator.discounted(660_000, rate)).isEqualTo(expected);
        }

        @Test
        @DisplayName("★ 독서실비에는 할인이 붙지 않는다 — 붙이면 청구가 모자란다")
        void studyRoomIsNeverDiscounted() {
            TuitionPrice price = TuitionPrice.common(
                    (short) 2026, GradeType.N_SU, SeatType.GENERAL, 660_000, 90_000);

            DailyFeeCalculator.Row row = DailyFeeCalculator.rowOf(price, 31, 50);

            assertThat(row.monthlyTuition()).isEqualTo(330_000);
            assertThat(row.monthlyStudyRoom()).isEqualTo(90_000);   // 45,000이 아니다
            assertThat(row.monthlyTotal()).isEqualTo(420_000);
        }

        @Test
        @DisplayName("★ 할인을 월 금액에 적용한 뒤 나눈다 — 순서를 뒤집으면 표와 어긋난다")
        void discountAppliesBeforeDivision() {
            // 표: 594,000÷27 = 22,000
            assertThat(DailyFeeCalculator.perDay(DailyFeeCalculator.discounted(660_000, 10), 27))
                    .isEqualTo(22_000);
            // 뒤집으면: 24,444×0.9 = 21,999.6 → 21,999 (1원 어긋난다)
            assertThat(DailyFeeCalculator.discounted(DailyFeeCalculator.perDay(660_000, 27), 10))
                    .isEqualTo(21_999);
        }
    }

    @Nested
    @DisplayName("경계")
    class Guards {

        @Test
        @DisplayName("교습일수가 0이면 계산할 수 없다")
        void zeroDaysRejected() {
            assertThatThrownBy(() -> DailyFeeCalculator.perDay(660_000, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("할인율은 0~100 밖일 수 없다")
        void discountRateBounded() {
            assertThatThrownBy(() -> DailyFeeCalculator.discounted(660_000, 101))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> DailyFeeCalculator.discounted(660_000, -1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("금액 0은 허용한다 — 전액 장학이면 실제로 0이다")
        void zeroAmountAllowed() {
            assertThat(DailyFeeCalculator.perDay(0, 31)).isZero();
            assertThat(DailyFeeCalculator.discounted(660_000, 100)).isZero();
        }
    }
}
