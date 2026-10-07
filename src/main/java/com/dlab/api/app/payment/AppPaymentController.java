package com.dlab.api.app.payment;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.payment.entity.Billing;
import com.dlab.domain.payment.repository.BillingRepository;
import com.dlab.domain.user.service.AppScopeResolver;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 앱 — 청구·결제 내역 조회 (A-13).
 *
 * <h2>조회만 한다</h2>
 * 결제 수단 등록과 결제 실행은 아직 없다 — <b>환불 흐름이 정해지지 않았고</b>(입금 전 말소 ·
 * 환불 수단 · 환불계좌 입력 주체), 받을 수만 있고 돌려줄 수 없는 상태로 앱에 결제 버튼을
 * 열면 그 자리에서 민원이 된다. <b>조회는 그 답과 무관하므로 먼저 만든다.</b>
 *
 * <h2>★ 학부모가 주 사용자다</h2>
 * 돈을 내는 쪽이 학부모라 <b>자녀를 지정해</b> 본다. 학생 본인도 볼 수 있게 두는 이유는
 * 급식·독서실 신청 화면에서 "내 미납이 얼마인가"를 확인하기 때문이다.
 *
 * <p>디랩 확정사항(2026-08-26) — <i>"결제항목·금액·결제기간을 결제일자순으로 모두 조회하고
 * 한꺼번에 출력"</i>. <b>결제 내역을 묻는 학부모가 많다</b>는 것이 그 근거다.
 */
@Tag(name = "앱 · 청구·결제 내역 (A-13)")
@RestController
@RequestMapping("/api/v1/app/payments")
@RequiredArgsConstructor
public class AppPaymentController {

    private final AppScopeResolver scopeResolver;
    private final BillingRepository billingRepository;

    /**
     * 청구 목록 — 납부기한 순.
     *
     * <p>★ <b>완납 건도 함께 내린다.</b> 미납만 주면 "낸 것"이 사라져 학부모가
     * 납부 사실을 확인할 수 없다 — 그게 가장 많이 들어오는 문의다.
     *
     * @param studentId <b>학부모만</b> 쓴다. 자녀가 여럿이라 서버가 고를 수 없다
     * @param unpaidOnly 미납만 보고 싶을 때. 비우면 전부
     */
    @GetMapping("/billings")
    public ApiResponse<List<PaymentResponse.BillingRow>> billings(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long studentId,
            @RequestParam(required = false, defaultValue = "false") boolean unpaidOnly) {

        var enrollment = scopeResolver.resolve(me.accountId(), studentId);
        return ApiResponse.success(billingRepository.findByEnrollment(enrollment.getId()).stream()
                .filter(b -> !unpaidOnly || b.unpaidAmount() > 0)
                .map(PaymentResponse.BillingRow::from)
                .toList());
    }

    /**
     * 청구 상세 — <b>항목별</b>로 나눠 보여준다.
     *
     * <p>★ 교습비와 독서실비가 <b>한 청구 안의 다른 항목</b>이다(750,000 = 660,000 + 90,000).
     * 환불 계산 방식이 둘이라 나눠 둔 것인데, 학부모가 "무엇에 얼마인가"를 묻는 것도 같은 축이다.
     */
    @GetMapping("/billings/{billingId}")
    public ApiResponse<PaymentResponse.BillingDetail> detail(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long studentId,
            @PathVariable Long billingId) {

        var enrollment = scopeResolver.resolve(me.accountId(), studentId);
        Billing billing = billingRepository.findById(billingId)
                .filter(b -> !b.isDeleted())
                // ★ 남의 청구를 ID 로 찍어 볼 수 없게 한다. 403 이면 "그 청구가 있다"는
                //   사실이 새어나가므로 없는 것으로 답한다
                .filter(b -> b.getEnrollment().getId().equals(enrollment.getId()))
                .orElseThrow(() -> new com.dlab.common.exception.BusinessException(
                        com.dlab.common.exception.ErrorCode.NOT_FOUND, "청구를 찾을 수 없습니다."));

        return ApiResponse.success(PaymentResponse.BillingDetail.from(billing));
    }

    /**
     * 결제 내역 — <b>결제일자순</b>으로 전부.
     *
     * <p>청구를 거치지 않고 "언제 얼마를 냈는지"만 보는 화면이다. 취소된 거래도
     * <b>취소 표시와 함께</b> 내린다 — 빼면 카드사 명세와 대조가 안 된다.
     */
    @GetMapping
    public ApiResponse<List<PaymentResponse.PaymentRow>> payments(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long studentId) {

        var enrollment = scopeResolver.resolve(me.accountId(), studentId);
        return ApiResponse.success(billingRepository.findByEnrollment(enrollment.getId()).stream()
                .flatMap(b -> b.getTransactions().stream()
                        .filter(t -> !t.isDeleted())
                        .map(t -> PaymentResponse.PaymentRow.from(b, t)))
                .sorted(java.util.Comparator.comparing(
                        PaymentResponse.PaymentRow::paidAt).reversed())
                .toList());
    }
}
