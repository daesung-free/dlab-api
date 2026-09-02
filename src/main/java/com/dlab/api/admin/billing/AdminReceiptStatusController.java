package com.dlab.api.admin.billing;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.payment.entity.Billing;
import com.dlab.domain.payment.entity.BillingType;
import com.dlab.domain.payment.service.ReceiptStatusService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 수납현황·미납자 (F-4.8-1).
 *
 * <h2>DSA "수납현황 &gt; 통합 매출장"을 대체한다</h2>
 * 청구를 만드는 경로가 생겼으니 <b>"누가 안 냈나"</b>가 다음이다.
 *
 * <h2>★ 목록과 합계가 같은 조건을 쓴다</h2>
 * 화면에 뜬 목록과 합계가 다르면 데스크가 어느 쪽을 믿어야 할지 알 수 없다.
 *
 * <h2>★ 엑셀은 연락처가 마스킹된다</h2>
 * 실행가이드 3.2 기본 ON. <b>내려받아 고쳐 다시 올리는 흐름</b>이 흔한데,
 * 마스킹된 값을 그대로 저장하면 <b>전 학생 연락처가 마스킹 문자열로 덮어써진다</b> —
 * Import 쪽이 같은 판정으로 걸러낸다.
 */
@Tag(name = "관리자 · 수납현황 (F-4.8-1)")
@RestController
@RequestMapping("/api/v1/admin/receipt-status")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
public class AdminReceiptStatusController {

    private final ReceiptStatusService receiptStatusService;

    /**
     * 수납현황 목록.
     *
     * @param academyId  비우면 내 지점. <b>전 지점 권한자는 지점을 골라야 한다</b> —
     *                   한 번에 뿌리면 어느 지점 미납인지 구분 없이 독촉이 나간다
     * @param from       납부기한 시작. <b>기한이 없는 청구는 기간 필터에 안 걸린다</b> —
     *                   걸면 기한 미지정 건이 통째로 사라진다
     * @param unpaidOnly 미납 건만. 미납자 추출·독촉이 쓴다
     */
    @GetMapping
    public ApiResponse<List<RowView>> list(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam short year,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) BillingType type,
            @RequestParam(defaultValue = "false") boolean unpaidOnly) {

        return ApiResponse.success(
                receiptStatusService.find(me, academyId, year, from, to, type, unpaidOnly)
                        .stream().map(RowView::from).toList());
    }

    /** 합계. 목록과 같은 조건으로 계산된다. */
    @GetMapping("/summary")
    public ApiResponse<SummaryView> summary(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam short year,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) BillingType type) {

        return ApiResponse.success(SummaryView.from(
                receiptStatusService.summarize(me, academyId, year, from, to, type)));
    }

    /** 엑셀 내려받기. ⚠️ <b>연락처가 마스킹된다</b>(기본 ON). */
    @GetMapping("/export")
    public ResponseEntity<ByteArrayResource> export(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam short year,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) BillingType type,
            @RequestParam(defaultValue = "false") boolean unpaidOnly) {

        byte[] bytes = receiptStatusService.export(me, academyId, year, from, to, type, unpaidOnly);
        String filename = URLEncoder.encode("수납현황_%d.xlsx".formatted(year),
                StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new ByteArrayResource(bytes));
    }

    /**
     * @param unpaid 과납이어도 0에서 멈춘다 — 음수가 섞이면 미납 합계가 줄어든다
     */
    public record RowView(Long billingId, String studentNo, String studentName,
                          String name, String billingType, String serviceMonth,
                          int billedAmount, int receivedAmount, int unpaid,
                          LocalDate dueDate, String status) {

        static RowView from(ReceiptStatusService.Row row) {
            Billing b = row.billing();
            return new RowView(b.getId(),
                    b.getEnrollment().getStudentNo(),
                    b.getEnrollment().getStudent().getName(),
                    b.getName(), b.getBillingType().name(),
                    b.getServiceMonth() == null ? null
                            : "%d-%02d".formatted(b.getServiceYear(), b.getServiceMonth()),
                    b.getBilledAmount(), row.received(), row.unpaid(),
                    b.getDueDate(), b.getStatus().name());
        }
    }

    /**
     * @param unpaidCount  미납 <b>건수</b>다 — 학생 수가 아니다. 한 학생이 여러 달 밀릴 수 있다
     * @param unpaidByType 교습비 미납과 급식비 미납은 독촉 방식이 다르다
     */
    public record SummaryView(int count, int billedAmount, int receivedAmount,
                              int unpaidAmount, int unpaidCount,
                              Map<String, Integer> unpaidByType) {

        static SummaryView from(ReceiptStatusService.Summary s) {
            return new SummaryView(s.count(), s.billedAmount(), s.receivedAmount(),
                    s.unpaidAmount(), s.unpaidCount(),
                    s.unpaidByType().entrySet().stream()
                            .collect(java.util.stream.Collectors.toMap(
                                    e -> e.getKey().name(), Map.Entry::getValue)));
        }
    }
}
