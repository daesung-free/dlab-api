package com.dlab.api.admin.absence;

import com.dlab.common.privacy.Masking;
import com.dlab.common.privacy.PersonalDataPolicy;
import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.approval.entity.ApprovalStatus;
import com.dlab.domain.approval.entity.ApproverType;
import com.dlab.domain.attendance.entity.AbsenceReasonType;
import com.dlab.domain.attendance.service.AbsenceReasonService;
import com.dlab.domain.attendance.service.AbsenceReasonService.AbsenceRequestRow;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

/**
 * 사유 신청 관리 (F-4.1-6).
 *
 * <p><b>승인·반려는 여기 없다</b> — {@code /api/v1/admin/approvals/{id}/approve|reject}가
 * 이미 한다. 목록이 {@code approvalRequestId}를 실어 보내므로 화면이 그걸 부르면 된다.
 * 승인 로직을 두 벌 만들면 타임아웃·에스컬레이션 처리가 갈린다.
 */
@RestController
@RequestMapping("/api/v1/admin/absence-requests")
@RequiredArgsConstructor
public class AdminAbsenceRequestController {

    private final AbsenceReasonService absenceReasonService;

    /** 목록. 화면 탭(대기/승인/반려)이 {@code status}로 걸린다. */
    @GetMapping
    public ApiResponse<ListResponse> list(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) ApprovalStatus status) {

        LocalDate start = from == null ? LocalDate.now().withDayOfMonth(1) : from;
        LocalDate end = to == null ? LocalDate.now() : to;

        boolean raw = PersonalDataPolicy.canViewRaw(me);
        return ApiResponse.success(new ListResponse(
                absenceReasonService.list(me, start, end, status).stream()
                        .map(r -> RowResponse.of(r, raw))
                        .toList(),
                absenceReasonService.summary(me, start, end),
                !raw));
    }

    /**
     * 관리자 직접 등록.
     *
     * <p><b>자동 승인이 아니다.</b> 승인 라우팅을 그대로 탄다 — 관리자가 넣었다고
     * 건너뛰면 학부모 승인이 필요한 유형에서 학부모가 모르는 사이에 처리된다.
     */
    @PostMapping
    public ApiResponse<Long> register(@CurrentAccount AuthPrincipal me,
                                      @Valid @RequestBody RegisterRequest request) {
        return ApiResponse.success(absenceReasonService.register(
                me, request.enrollmentId(), request.date(), request.type(),
                request.reason(), request.startTime(), request.endTime()).getId());
    }

    /**
     * @param startTime 외출·조퇴 시작. 결석·지각은 종일이라 비운다
     * @param endTime   외출 종료. 조퇴는 복귀가 없어 비운다
     */
    public record RegisterRequest(
            @NotNull Long enrollmentId,
            @NotNull LocalDate date,
            @NotNull AbsenceReasonType type,
            @Size(max = 500) String reason,
            LocalTime startTime,
            LocalTime endTime
    ) {
    }

    public record ListResponse(List<RowResponse> rows, Map<String, Long> summary, boolean masked) {
    }

    /**
     * @param approvalRequestId 승인·반려 시 이 id로 {@code /admin/approvals}를 부른다
     */
    public record RowResponse(
            Long id,
            Long approvalRequestId,
            Instant submittedAt,
            String studentNo,
            String name,
            String className,
            AbsenceReasonType type,
            String period,
            String reason,
            ApproverType approverType,
            ApprovalStatus status,
            boolean escalationCandidate
    ) {
        static RowResponse of(AbsenceRequestRow r, boolean raw) {
            return new RowResponse(
                    r.id(), r.approvalRequestId(), r.submittedAt(), r.studentNo(),
                    raw ? r.name() : Masking.name(r.name()),
                    r.className(), r.type(), r.period(), r.reason(),
                    r.approverType(), r.status(), r.escalationCandidate());
        }
    }
}
