package com.dlab.api.app.payment;

import com.dlab.domain.payment.entity.Billing;
import com.dlab.domain.payment.entity.PaymentTransaction;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class PaymentResponse {

    private PaymentResponse() {
    }

    /**
     * 청구 한 건.
     *
     * @param serviceYear  이용 연·월. <b>청구 1건 = 한 달분</b>이라 이 값이 "무엇에 대한 돈인가"다
     * @param billedAmount 할인 적용 후 청구액
     * @param paidAmount   낸 금액(취소분 제외)
     * @param unpaidAmount 남은 금액. <b>0이면 완납</b>이다
     * @param overdue      납부기한이 지났는데 남은 금액이 있는가 — 화면이 빨갛게 칠하는 근거다
     */
    public record BillingRow(Long id, String name, String billingType,
                             Short serviceYear, Short serviceMonth,
                             int suppliedAmount, int discountAmount, int billedAmount,
                             int paidAmount, int unpaidAmount,
                             LocalDate dueDate, boolean overdue, String status) {

        public static BillingRow from(Billing b) {
            int unpaid = b.unpaidAmount();
            return new BillingRow(b.getId(), b.getName(), b.getBillingType().name(),
                    b.getServiceYear(), b.getServiceMonth(),
                    b.getSuppliedAmount(), b.getDiscountAmount(), b.getBilledAmount(),
                    b.receivedAmount(), unpaid,
                    b.getDueDate(),
                    // 기한이 없으면 연체가 아니다 — 수시 청구에 기한을 안 넣는 경우가 있다
                    b.getDueDate() != null && unpaid > 0
                            && b.getDueDate().isBefore(LocalDate.now()),
                    b.getStatus().name());
        }
    }

    /**
     * 청구 상세 — 항목과 결제 내역을 함께 준다.
     *
     * <p>★ <b>항목을 나눠 보여준다.</b> 교습비와 독서실비가 한 청구 안의 다른 항목이고
     * (750,000 = 660,000 + 90,000) 환불 계산 방식도 둘이라, 합계만 보면 "무엇에 얼마인가"에
     * 답할 수 없다.
     */
    public record BillingDetail(BillingRow billing, List<ItemRow> items,
                                List<PaymentRow> payments) {

        public static BillingDetail from(Billing b) {
            return new BillingDetail(
                    BillingRow.from(b),
                    b.getItems().stream()
                            .filter(i -> !i.isDeleted())
                            .sorted(java.util.Comparator.comparing(
                                    com.dlab.domain.payment.entity.BillingItem::getSortOrder))
                            .map(ItemRow::from)
                            .toList(),
                    b.getTransactions().stream()
                            .filter(t -> !t.isDeleted())
                            .map(t -> PaymentRow.from(b, t))
                            .sorted(java.util.Comparator.comparing(PaymentRow::paidAt).reversed())
                            .toList());
        }
    }

    /** @param itemType 교습비 · 독서실비 등 */
    @io.swagger.v3.oas.annotations.media.Schema(name = "BillingItemRow")
    public record ItemRow(String itemType, int suppliedAmount, int discountAmount,
                          int billedAmount) {

        static ItemRow from(com.dlab.domain.payment.entity.BillingItem i) {
            return new ItemRow(i.getItemType().name(), i.getSuppliedAmount(),
                    i.getDiscountAmount(), i.getBilledAmount());
        }
    }

    /**
     * 결제 한 건.
     *
     * @param billingName 어느 청구에 대한 결제인지. 내역만 보는 화면에서는 이것이 유일한 단서다
     * @param canceled    ★ 취소된 거래. <b>빼지 않고 표시한다</b> — 빼면 카드사 명세와 대조가 안 된다
     */
    public record PaymentRow(Long id, Long billingId, String billingName,
                             int amount, String method, Instant paidAt,
                             boolean canceled, Instant canceledAt) {

        public static PaymentRow from(Billing b, PaymentTransaction t) {
            return new PaymentRow(t.getId(), b.getId(), b.getName(),
                    t.getAmount(), t.getMethod().name(), t.getPaidAt(),
                    t.getCanceledAt() != null, t.getCanceledAt());
        }
    }
}
