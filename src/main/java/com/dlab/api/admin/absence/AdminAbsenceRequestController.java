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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 사유 신청 관리 (F-4.1-6).
 *
 * <p><b>승인·반려는 여기 없다</b> — {@code /api/v1/admin/approvals/{id}/approve|reject}가
 * 이미 한다. 목록이 {@code approvalRequestId}를 실어 보내므로 화면이 그걸 부르면 된다.
 * 승인 로직을 두 벌 만들면 타임아웃·에스컬레이션 처리가 갈린다.
 */
@Tag(name = "관리자 · 사유 신청 (F-4.1-6)")
@RestController
@RequestMapping("/api/v1/admin/absence-requests")
@RequiredArgsConstructor
public class AdminAbsenceRequestController {

    private final AbsenceReasonService absenceReasonService;

    /** 목록. 화면 탭(대기/승인/반려)이 {@code status}로 걸린다. */
    @GetMapping
    public ApiResponse<ListResponse> list(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) ApprovalStatus status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {

        LocalDate start = from == null ? LocalDate.now().withDayOfMonth(1) : from;
        LocalDate end = to == null ? LocalDate.now() : to;

        boolean raw = PersonalDataPolicy.canViewRaw(me);
        List<AbsenceRowResponse> all = absenceReasonService.list(me, academyId, start, end, status)
                .stream().map(r -> AbsenceRowResponse.of(r, raw)).toList();

        // summary 는 페이지 합계가 아니라 필터 전체 기준이다 — 탭 건수가 페이지마다
        // 바뀌면 "대기 3건"이 무슨 뜻인지 알 수 없다
        var summary = absenceReasonService.summary(me, academyId, start, end);

        if (size == null) {
            return ApiResponse.success(new ListResponse(all, summary, !raw));
        }
        var sliced = com.dlab.common.search.PageSlicer.of(all, page, size);
        return ApiResponse.success(new ListResponse(sliced.getContent(), summary, !raw),
                ApiResponse.PageMeta.of(sliced));
    }

    /**
     * 관리자 직접 등록.
     *
     * <p><b>자동 승인이 아니다.</b> 승인 라우팅을 그대로 탄다 — 관리자가 넣었다고
     * 건너뛰면 학부모 승인이 필요한 유형에서 학부모가 모르는 사이에 처리된다.
     */
    /**
     * ★★ <b>증빙 확인</b> — 체크하면 <b>그 신청으로 붙은 자동 벌점을 거둔다</b>
     * (디랩 1029 4-3 #36).
     *
     * <p>시트 원문: <b>「사유외출 및 조퇴 시 증빙영수증 제출할 경우 직원 확인 체크 시
     * 기존 부여된 벌점 자동 삭제」</b>
     *
     * <p>★ <b>버튼 하나</b>로 끝난다 — ⚠️ 항목을 손으로 찾아 넣게 하면
     * <b>빠진다</b>(1029 4-3).
     *
     * <p>⚠️ 무엇을 거두고 무엇을 기록할지는 <b>전부 {@code penalty_rule} 행</b>이다 —
     * 신청 종류로 화면이 막지 않는다.
     */
    @PostMapping("/{reasonId}/evidence-confirm")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER','STAFF')")
    public ApiResponse<Void> confirmEvidence(@CurrentAccount AuthPrincipal me,
                                             @PathVariable Long reasonId) {
        absenceReasonService.confirmEvidence(me, reasonId);
        return ApiResponse.success(null);
    }

    /**
     * ★★ <b>미진행</b> — 승인됐는데 실제로 하지 않은 경우 (1029 4-3 #10).
     *
     * <p>⚠️ 10/21 에 <b>「외출 신청 후 안 나가면 취소할 벌점이 없다 — 개발할 것 없음」</b>
     * 으로 회신했는데, <b>승인 시 부여로 바뀌면서 그 결론이 뒤집혔다.</b>
     *
     * <p>★★ <b>9-7 회신을 기다리지 않는다</b> — 미확정인 「-1 을 취소할지 둘지」는
     * <b>{@code penalty_rule.supersedes_rule_id}</b> 로 흡수된다. 「미진행」 규칙에
     * <b>대체 대상을 지정하면 취소되고, 비우면 남는다.</b>
     */
    @PostMapping("/{reasonId}/not-executed")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER','STAFF')")
    public ApiResponse<Void> markNotExecuted(@CurrentAccount AuthPrincipal me,
                                             @PathVariable Long reasonId) {
        absenceReasonService.markNotExecuted(me, reasonId);
        return ApiResponse.success(null);
    }

    @PostMapping
    public ApiResponse<Long> register(@CurrentAccount AuthPrincipal me,
                                      @Valid @RequestBody AbsenceRegisterRequest request) {
        return ApiResponse.success(absenceReasonService.register(
                me, request.enrollmentId(), request.date(), request.type(),
                request.reason(), request.startTime(), request.endTime(),
                request.categoryId()).getId());
    }

    /**
     * @param startTime 외출·조퇴 시작. 결석·지각은 종일이라 비운다
     * @param endTime   외출 종료. 조퇴는 복귀가 없어 비운다
     */
    public record AbsenceRegisterRequest(
            @NotNull Long enrollmentId,
            @NotNull LocalDate date,
            @NotNull AbsenceReasonType type,
            @Size(max = 500) String reason,
            LocalTime startTime,
            LocalTime endTime,
            /** 사유 카테고리(병결·가정사 등). 비워도 된다 — 등록된 게 없을 수 있다 */
            Long categoryId
    ) {
    }

    public record ListResponse(List<AbsenceRowResponse> rows, Map<String, Long> summary, boolean masked) {
    }

    /**
     * @param approvalRequestId 승인·반려 시 이 id로 {@code /admin/approvals}를 부른다
     */
    public record AbsenceRowResponse(
            Long id,
            Long approvalRequestId,
            Instant submittedAt,
            String studentNo,
            String name,
            String className,
            AbsenceReasonType type,
            String period,
            String reason,
            String categoryName,
            ApproverType approverType,
            ApprovalStatus status,
            /** 반려 사유. 반려된 건에만 값이 있다 — 없으면 화면이 "왜 반려됐나"를 못 보여준다 */
            String rejectReason,
            boolean escalationCandidate
    ) {
        static AbsenceRowResponse of(AbsenceRequestRow r, boolean raw) {
            return new AbsenceRowResponse(
                    r.id(), r.approvalRequestId(), r.submittedAt(), r.studentNo(),
                    raw ? r.name() : Masking.name(r.name()),
                    r.className(), r.type(), r.period(), r.reason(), r.categoryName(),
                    r.approverType(), r.status(), r.rejectReason(), r.escalationCandidate());
        }
    }
}
