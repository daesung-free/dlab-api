package com.dlab.domain.payment.service;

import com.dlab.domain.payment.entity.BillingItem;
import com.dlab.domain.payment.entity.BillingItemType;

import java.util.List;

/**
 * 퇴원 환불 계산 (0820 규정 · I-26).
 *
 * <h2>산식이 항목마다 다르다</h2>
 * <table>
 *   <tr><th>항목</th><th>산식</th></tr>
 *   <tr><td>교습비</td><td><b>구간</b> — 교습 시작 전 전액, 1/3까지 2/3 환불, 1/2까지 1/2,
 *       1/2 이후 없음</td></tr>
 *   <tr><td>독서실비</td><td><b>일할</b> — 사용한 일수만큼 차감</td></tr>
 * </table>
 * 교습비 쪽은 학원법 반환기준 그대로다.
 *
 * <p>⚠️ <b>첫 행(교습 시작 전 전액)을 빼먹기 쉽다.</b> 빼면 {@code usedDays = 0}이
 * "1/3 이내" 구간으로 떨어져 하루도 안 다닌 학생에게서 1/3을 떼게 된다.
 *
 * <h2>★ 차감은 "정상가 기준"이다 — 이게 규정의 핵심이다</h2>
 * 규정에 <i>"할인적용을 받았을 경우 정상가 기준으로 차감 후 환불"</i>이라고 명시돼 있다.
 * 그래서 계산이 <b>"납부액 × 환불비율"이 아니라 "납부액 − 정가 × 차감비율"</b>이다.
 *
 * <p>둘은 할인이 없을 때만 같은 값이 나온다. 할인을 받았으면 차감이 납부액보다 커질 수
 * 있고, 그때 <b>결과가 음수</b>가 된다 — 그게 곧 <b>추가 징수</b>다. 규정의
 * <i>"차감금액이 부족할 경우 원칙상 추가금 재결제를 해야하나"</i>가 이 경우이고,
 * <i>"3~5월 할인 → 5월 퇴원 시 할인적용 받은 교습비 모두 정상가 재결제"</i> 예시가
 * 정확히 이 산식으로 재현된다.
 *
 * <p><b>음수를 0에서 자르지 않는다.</b> 자르면 추가로 받아야 할 금액이 사라지고,
 * 데스크는 그걸 화면에서 알 방법이 없다.
 *
 * <h2>이용기간은 교습일수 기준이다</h2>
 * 달력이 아니다. 2026년 2월은 27일이라 <b>1/3 = 9일, 1/2 = 13.5일</b>이다
 * ({@code tuition_month} 참고).
 */
public final class RefundCalculator {

    private RefundCalculator() {
    }

    /**
     * 청구 한 건의 환불액.
     *
     * @param usedDays 사용한 교습일수. {@code teachingDays}를 넘으면 그 값으로 맞춘다 —
     *                 넘는 건 이미 다 쓴 것이고, 넘긴 만큼 더 차감할 근거가 없다
     */
    public static Result calculate(List<BillingItem> items, int teachingDays, int usedDays) {
        if (teachingDays <= 0) {
            throw new IllegalArgumentException("교습일수는 1 이상이어야 합니다: " + teachingDays);
        }
        int used = Math.max(0, Math.min(usedDays, teachingDays));

        List<ItemResult> results = items.stream()
                .filter(item -> !item.isDeleted())
                .filter(item -> item.getItemType().isWithdrawalRefundable())
                .map(item -> calculateItem(item, teachingDays, used))
                .toList();

        return new Result(teachingDays, used, results);
    }

    private static ItemResult calculateItem(BillingItem item, int teachingDays, int usedDays) {
        return switch (item.getItemType().refundMethod()) {
            case BRACKET -> bracket(item, teachingDays, usedDays);
            case DAILY -> daily(item, teachingDays, usedDays);
            case NONE -> new ItemResult(item.getItemType(), item.getBilledAmount(), 0, 0,
                    "환불 대상 아님");
        };
    }

    /**
     * 구간 환불 (교습비).
     *
     * <p>비교를 <b>정수 곱으로</b> 한다 — {@code usedDays / teachingDays <= 1/3}을
     * 실수로 계산하면 27일 × 1/3 = 8.999…처럼 경계에서 한 칸씩 어긋난다.
     */
    private static ItemResult bracket(BillingItem item, int teachingDays, int usedDays) {
        // 사용일수 0        →  차감 없음 (전액 환불) ★ 아래 주석 참고
        // 사용일수 ≤ 교습일수/3  →  차감 1/3 (2/3 환불)
        // 사용일수 ≤ 교습일수/2  →  차감 1/2 (1/2 환불)
        // 그 이후               →  차감 전액 (환불 없음)
        final int deductionNumerator;
        final int deductionDenominator;
        final String bracketName;

        // ★ 교습 시작 전은 전액 반환이다 — 학원법 반환기준의 첫 행.
        //   이 분기가 없으면 usedDays=0 이 아래 첫 구간(0*3 <= teachingDays)으로 떨어져
        //   **하루도 안 다닌 학생에게서 1/3을 떼게 된다.** 방향이 과소 환불이라
        //   그냥 계산 오류가 아니라 법정 기준 미달이다.
        if (usedDays == 0) {
            deductionNumerator = 0;
            deductionDenominator = 1;
            bracketName = "교습 시작 전 — 전액 환불";
        } else if (usedDays * 3 <= teachingDays) {
            deductionNumerator = 1;
            deductionDenominator = 3;
            bracketName = "이용기간 1/3 이내 — 2/3 환불";
        } else if (usedDays * 2 <= teachingDays) {
            deductionNumerator = 1;
            deductionDenominator = 2;
            bracketName = "이용기간 1/2 이내 — 1/2 환불";
        } else {
            deductionNumerator = 1;
            deductionDenominator = 1;
            bracketName = "이용기간 1/2 초과 — 환불 없음";
        }

        // ★ 정상가 기준 차감. 납부액이 아니라 정가에 비율을 건다
        int deduction = (int) ((long) item.getSuppliedAmount() * deductionNumerator
                / deductionDenominator);
        int refund = item.getBilledAmount() - deduction;

        String detail = "%s / 정가 %,d원 × %d/%d = 차감 %,d원, 납부 %,d원 → %s %,d원"
                .formatted(bracketName, item.getSuppliedAmount(),
                        deductionNumerator, deductionDenominator, deduction,
                        item.getBilledAmount(),
                        refund < 0 ? "추가 징수" : "환불", Math.abs(refund));

        return new ItemResult(item.getItemType(), item.getBilledAmount(), deduction, refund, detail);
    }

    /**
     * 일할 환불 (독서실비).
     *
     * <p><b>전 기간을 다 썼으면 환불 0이다.</b> 1일 단가 × 일수로 계산하면 절사분이
     * 남아 다 쓰고도 몇 원이 환불된다(90,000 − 3,333×27 = 9원).
     */
    private static ItemResult daily(BillingItem item, int teachingDays, int usedDays) {
        if (usedDays >= teachingDays) {
            return new ItemResult(item.getItemType(), item.getBilledAmount(),
                    item.getBilledAmount(), 0,
                    "전 기간 사용 — 환불 없음");
        }
        int perDay = DailyFeeCalculator.perDay(item.getSuppliedAmount(), teachingDays);
        int deduction = perDay * usedDays;
        int refund = item.getBilledAmount() - deduction;

        String detail = "일할 / 1일 %,d원 × %d일 = 차감 %,d원, 납부 %,d원 → 환불 %,d원"
                .formatted(perDay, usedDays, deduction, item.getBilledAmount(), refund);

        return new ItemResult(item.getItemType(), item.getBilledAmount(), deduction, refund, detail);
    }

    /**
     * 청구 한 건의 계산 결과.
     *
     * @param items 항목별 결과. 합계만 보면 "왜 이 금액인가"에 답할 수 없어 함께 든다
     */
    public record Result(int teachingDays, int usedDays, List<ItemResult> items) {

        /** 총 환불액. <b>음수면 추가 징수다</b> — 0에서 자르지 않는다. */
        public int refundAmount() {
            return items.stream().mapToInt(ItemResult::refund).sum();
        }

        /** 더 받아야 하는 상황인가. 데스크 화면이 이걸로 안내를 가른다. */
        public boolean requiresAdditionalPayment() {
            return refundAmount() < 0;
        }

        /** 상세 내역 — 클라이언트가 요청한 "추가결제 시 상세 내역 출력"이 이걸 쓴다. */
        public String detail() {
            return items.stream().map(ItemResult::detail)
                    .reduce((a, b) -> a + "\n" + b).orElse("");
        }
    }

    /**
     * @param refund 납부액 − 차감액. <b>음수 가능</b>
     */
    public record ItemResult(BillingItemType itemType, int paidAmount, int deduction,
                             int refund, String detail) {
    }
}
