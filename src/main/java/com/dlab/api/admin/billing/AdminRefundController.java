package com.dlab.api.admin.billing;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.payment.entity.Refund;
import com.dlab.domain.payment.service.RefundCalculator;
import com.dlab.domain.payment.service.RefundService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 환불 (0820 규정 · 디랩 10/12 회신).
 *
 * <p>⚠️ <b>급식비는 이 경로가 아니다</b> — 업체 명의로 매출이 잡혀 처리 주체가
 * 정해져야 한다(확인 14 미회신). 급식 청구를 보내면 400 이다.
 */
@Tag(name = "관리자 · 환불")
@RestController
@RequestMapping("/api/v1/admin/refunds")
@RequiredArgsConstructor
public class AdminRefundController {

    private final RefundService refundService;

    /**
     * 환불 <b>미리보기</b> — 저장하지 않는다.
     *
     * <p>★ <b>누르기 전에 봐야 한다.</b> 추가 징수가 나오는 경우는 「돌려주는」 화면이
     * 「더 받아야 한다」로 뒤집히므로, 모르고 누르면 안 된다.
     *
     * @param usedDays 이용한 <b>교습일수</b>. 달력 일수가 아니다(설·추석 당일이 빠진다)
     */
    @GetMapping("/preview")
    public ApiResponse<RefundPreview> preview(@CurrentAccount AuthPrincipal me,
                                              @RequestParam Long billingId,
                                              @RequestParam int usedDays) {
        return ApiResponse.success(
                RefundPreview.from(refundService.preview(me, billingId, usedDays)));
    }

    /**
     * 환불 집행.
     *
     * <p>수단에 따라 갈린다 — <b>카드는 PG 부분취소, 가상계좌는 계좌 이체 대상으로 적재</b>.
     *
     * <p>⚠️ 응답의 {@code status} 를 반드시 확인할 것. {@code FAILED} 면 <b>돈이 돌아가지
     * 않았다</b>(사유는 {@code failReason}). {@code ADDITIONAL_PAYMENT} 면 환불이 아니라
     * <b>더 받아야 하는 상태</b>다.
     */
    @PostMapping
    public ApiResponse<RefundView> execute(@CurrentAccount AuthPrincipal me,
                                           @RequestParam Long billingId,
                                           @RequestParam int usedDays) {
        return ApiResponse.success(RefundView.from(refundService.execute(me, billingId, usedDays)));
    }

    /**
     * 계좌 이체 대상 목록 — <b>데스크가 매일 보는 자리</b>다.
     *
     * <p>우리가 이체를 실행하지 않으므로(사람이 은행에서 한다) 이 목록이 비지 않으면
     * <b>누군가 받을 돈을 아직 못 받은 상태</b>다.
     */
    @GetMapping("/pending-transfers")
    public ApiResponse<List<RefundView>> pendingTransfers(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam short year) {
        return ApiResponse.success(refundService.pendingTransfers(me, academyId, year).stream()
                .map(RefundView::from).toList());
    }

    @GetMapping
    public ApiResponse<List<RefundView>> list(@CurrentAccount AuthPrincipal me,
                                              @RequestParam(required = false) Long academyId,
                                              @RequestParam short year) {
        return ApiResponse.success(refundService.findAll(me, academyId, year).stream()
                .map(RefundView::from).toList());
    }

    /** 이체 완료 표시 — 사람이 이체한 뒤 누른다. */
    @PostMapping("/{refundId}/transferred")
    public ApiResponse<RefundView> markTransferred(@CurrentAccount AuthPrincipal me,
                                                   @PathVariable Long refundId) {
        return ApiResponse.success(RefundView.from(refundService.markTransferred(me, refundId)));
    }

    /**
     * @param refundAmount              ★ <b>음수면 추가 징수</b>다 — 0 으로 읽지 말 것
     * @param requiresAdditionalPayment 화면이 「돌려드립니다」와 「더 받아야 합니다」를 가른다
     * @param detail                    항목별 계산 근거. 「추가결제 상세 내역 출력」이 이걸 쓴다
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "RefundPreviewResponse")
    public record RefundPreview(int teachingDays, int usedDays, int refundAmount,
                                boolean requiresAdditionalPayment, String detail,
                                List<RefundItemView> items) {

        static RefundPreview from(RefundCalculator.Result r) {
            return new RefundPreview(r.teachingDays(), r.usedDays(), r.refundAmount(),
                    r.requiresAdditionalPayment(), r.detail(),
                    r.items().stream().map(RefundItemView::from).toList());
        }
    }

    /** @param refund 납부액 − 차감액. <b>음수 가능</b> */
    public record RefundItemView(String itemType, int paidAmount, int deduction, int refund,
                                 String detail) {

        static RefundItemView from(RefundCalculator.ItemResult i) {
            return new RefundItemView(i.itemType().name(), i.paidAmount(), i.deduction(),
                    i.refund(), i.detail());
        }
    }

    /**
     * @param status     {@code PENDING} 이체 대기 · {@code DONE} 완료 ·
     *                   {@code FAILED} <b>돈이 돌아가지 않음</b> ·
     *                   {@code ADDITIONAL_PAYMENT} <b>더 받아야 함</b>
     * @param failReason {@code FAILED} 일 때의 사유. 화면에 그대로 띄울 것
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "RefundResponse")
    public record RefundView(Long id, Long billingId, String studentNo, String studentName,
                             int teachingDays, int usedDays, int refundAmount,
                             String method, String status, String failReason,
                             String detail, Instant processedAt) {

        static RefundView from(Refund r) {
            var enrollment = r.getBilling().getEnrollment();
            return new RefundView(r.getId(), r.getBilling().getId(),
                    enrollment.getStudentNo(), enrollment.getStudent().getName(),
                    r.getTeachingDays(), r.getUsedDays(), r.getRefundAmount(),
                    r.getMethod().name(), r.getStatus().name(), r.getFailReason(),
                    r.getDetail(), r.getProcessedAt());
        }
    }
}
