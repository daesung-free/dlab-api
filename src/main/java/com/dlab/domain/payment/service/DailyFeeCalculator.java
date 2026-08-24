package com.dlab.domain.payment.service;

import com.dlab.domain.payment.entity.TuitionPrice;

import java.util.List;

/**
 * 1일 교습비·독서실비 계산 (0820 요청 — <i>"교습비를 입력하면 그달의 일수로 나누어
 * 1일 교습비가 자동계산 되도록"</i>).
 *
 * <h2>지금까지 손으로 하던 일이다</h2>
 * 클라이언트가 표에 660,000÷27 같은 값을 직접 계산해 넣고 있었다.
 * <b>할인 6단계 × 상품 5종 × 월 구분 4가지 = 수백 칸</b>이다.
 * 월 금액과 교습일수만 있으면 전부 파생되므로 입력이 두 칸으로 줄어든다.
 *
 * <h2>★ 할인가를 따로 입력받지 않는다</h2>
 * 표의 할인가가 <b>정가 × (1−할인율)로 정확히 맞는다</b>(660,000×0.9 = 594,000 등).
 * 그래서 정가 하나만 저장하고 할인가는 계산한다 — 따로 저장하면 정가를 고쳤을 때
 * 할인가만 낡은 값으로 남는다.
 *
 * <h2>★ 절사는 정책값이다</h2>
 * 받은 표는 대부분 <b>버림</b>이다(660,000÷29 = 22,758.6 → 22,758).
 * 다만 대구 두 칸만 올림이라 규칙이 확정되지 않았다 — 확인 회신이 오면
 * {@link Rounding} 선택만 바꾸면 되게 분리해 뒀다. <b>계산식에 박지 말 것.</b>
 */
public final class DailyFeeCalculator {

    /**
     * 원 단위 절사 방식.
     *
     * <p>기본은 {@link #FLOOR}다 — 받은 표가 그렇고, <b>학생에게 유리한 쪽</b>이다.
     * 중도 입학 결제에서 하루당 몇 원이 학생 부담으로 붙는 걸 막는다.
     */
    public enum Rounding {
        /** 버림. 받은 표의 대다수가 이쪽이다. */
        FLOOR,
        /** 반올림. 대구 두 칸이 이 값과 맞는다 — 회신 오면 전환 가능. */
        HALF_UP;

        int apply(long numerator, int denominator) {
            return this == FLOOR
                    ? (int) (numerator / denominator)
                    : (int) ((numerator + denominator / 2L) / denominator);
        }
    }

    /** 표에 등장하는 할인율. 화면 드롭다운이 이 목록을 쓴다. */
    public static final List<Integer> DISCOUNT_RATES = List.of(0, 10, 20, 30, 50, 70);

    private static final Rounding DEFAULT_ROUNDING = Rounding.FLOOR;

    private DailyFeeCalculator() {
    }

    /**
     * 월 금액을 그 달 교습일수로 나눈 1일 단가.
     *
     * @param teachingDays <b>달력 일수가 아니다</b> — {@code tuition_month}에 등록된 교습일수다
     *                     (2026년 기준 2월 27일 · 9월 29일)
     */
    public static int perDay(int monthlyAmount, int teachingDays) {
        return perDay(monthlyAmount, teachingDays, DEFAULT_ROUNDING);
    }

    public static int perDay(int monthlyAmount, int teachingDays, Rounding rounding) {
        if (teachingDays <= 0) {
            throw new IllegalArgumentException("교습일수는 1 이상이어야 합니다: " + teachingDays);
        }
        if (monthlyAmount < 0) {
            throw new IllegalArgumentException("금액은 0 이상이어야 합니다: " + monthlyAmount);
        }
        return rounding.apply(monthlyAmount, teachingDays);
    }

    /**
     * 할인 적용 월 교습비.
     *
     * <p><b>월 금액에 먼저 할인을 적용하고 그 다음에 나눈다.</b> 순서를 뒤집으면
     * (1일 단가에 할인) 표와 값이 어긋난다 — 594,000÷31 = 19,161이지만
     * 21,290×0.9 = 19,161.0…을 버리면 19,161로 우연히 같아 보여도,
     * 29일·27일 달에서는 갈린다(20,482 vs 20,482.2→20,482, 22,000 vs 22,000).
     * 표가 "할인된 월 금액을 나눈" 값이므로 그쪽에 맞춘다.
     */
    public static int discounted(int monthlyAmount, int discountRate) {
        if (discountRate < 0 || discountRate > 100) {
            throw new IllegalArgumentException("할인율은 0~100 사이여야 합니다: " + discountRate);
        }
        // 정가 × (1−할인율). 표의 할인가와 정확히 맞는다(660,000×0.9 = 594,000)
        return (int) ((long) monthlyAmount * (100 - discountRate) / 100);
    }

    /**
     * 한 상품의 단가표 한 줄 — 화면이 그대로 그린다.
     *
     * <p>독서실비는 {@code discountRate}를 무시한다. <b>할인이 없기 때문</b>이고,
     * 여기서 안 막으면 화면이 할인 열에 독서실비까지 깎아 넣는다.
     */
    public static Row rowOf(TuitionPrice price, int teachingDays, int discountRate) {
        return rowOf(price, teachingDays, discountRate, DEFAULT_ROUNDING);
    }

    public static Row rowOf(TuitionPrice price, int teachingDays, int discountRate,
                            Rounding rounding) {
        int tuition = discounted(price.getTuitionFee(), discountRate);
        // ★ 독서실비는 할인 대상이 아니다 — 정가 그대로 간다
        int studyRoom = price.getStudyRoomFee();

        return new Row(discountRate, teachingDays,
                tuition, perDay(tuition, teachingDays, rounding),
                studyRoom, perDay(studyRoom, teachingDays, rounding));
    }

    /**
     * 단가표 한 줄.
     *
     * @param monthlyTuition   할인 적용된 월 교습비
     * @param dailyTuition     1일 교습비
     * @param monthlyStudyRoom 월 독서실비 — <b>할인이 적용되지 않은 정가</b>
     * @param dailyStudyRoom   1일 독서실비
     */
    public record Row(int discountRate, int teachingDays,
                      int monthlyTuition, int dailyTuition,
                      int monthlyStudyRoom, int dailyStudyRoom) {

        /** 그 달 결제 총액(할인 반영). 표시용이고 저장하지 않는다. */
        public int monthlyTotal() {
            return monthlyTuition + monthlyStudyRoom;
        }
    }
}
