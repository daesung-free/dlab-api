package com.dlab.api.admin.approval;

import com.dlab.common.response.ApiResponse;
import com.dlab.domain.attendance.entity.AbsenceSubmitterType;
import com.dlab.domain.attendance.repository.AbsenceReasonRepository;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.approval.entity.ApprovalRequest;
import com.dlab.domain.approval.entity.ApprovalStatus;
import com.dlab.domain.approval.entity.ApproverType;
import com.dlab.domain.approval.entity.RequestType;
import com.dlab.domain.approval.service.ApprovalQueryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 승인 라우팅 현황 (F-4.11-5).
 *
 * <p><b>담임 대기열과 다른 화면이다.</b> {@code /admin/approvals}는 "내가 처리할 것"이라
 * 담당선생님 전용이고, 관리자가 그걸 부르면 승인자가 아니어서 거절된다. 여기는
 * <b>"지금 승인이 몇 건 밀려 있나"</b>를 지점 단위로 보는 곳이다.
 *
 * <p><b>처리된 건도 함께 본다.</b> 대기 건만 주면 "오늘 몇 건이 들어왔고 몇 건이
 * 처리됐나"를 셀 수 없다 — 상태는 필터로 고른다.
 */
@Tag(name = "관리자 · 승인 현황 (F-4.11-5)")
@RestController
@RequestMapping("/api/v1/admin/approvals/board")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
public class AdminApprovalBoardController {

    private final ApprovalQueryService approvalQueryService;
    private final AbsenceReasonRepository absenceReasonRepository;
    private final Clock clock;

    /**
     * 승인 요청 현황.
     *
     * @param academyId 비우면 내 지점. 전 지점 권한자가 비우면 <b>전 지점 합계</b>다
     * @param from      기본은 이번 달 1일
     */
    @GetMapping
    public ApiResponse<BoardResponse> board(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) ApprovalStatus status,
            @RequestParam(required = false) RequestType requestType) {

        LocalDate today = LocalDate.now(clock);
        LocalDate start = from == null ? today.withDayOfMonth(1) : from;
        LocalDate end = to == null ? today : to;

        List<ApprovalRequest> rows = approvalQueryService.board(
                me, academyId, status, requestType, start, end, clock.getZone());

        Instant now = Instant.now(clock);
        // ★ 신청자 구분을 ★ 한 번에 읽는다 — 행마다 조회하면 쿼리가 행 수만큼 나간다
        Map<Long, AbsenceSubmitterType> submitters = submitterTypes(rows);
        return ApiResponse.success(new BoardResponse(
                rows.stream().map(r -> Row.of(r, now, submitters.get(r.getId()))).toList(),
                summarize(rows, now)));
    }

    /**
     * 상단 통계.
     *
     * <p><b>{@code overdue}는 별도 축이다</b> — 대기 중이면서 에스컬레이션 시각이 지난 건이라
     * {@code PENDING}에 포함된다. 합계를 낼 때 더하면 안 된다.
     */
    private Map<String, Long> summarize(List<ApprovalRequest> rows, Instant now) {
        return Map.of(
                "total", (long) rows.size(),
                "pending", rows.stream().filter(r -> r.getStatus() == ApprovalStatus.PENDING).count(),
                "approved", rows.stream().filter(r -> r.getStatus() == ApprovalStatus.APPROVED).count(),
                "rejected", rows.stream().filter(r -> r.getStatus() == ApprovalStatus.REJECTED).count(),
                "overdue", rows.stream().filter(r -> isOverdue(r, now)).count());
    }

    /**
     * ★ 승인 요청 → 신청자 구분. <b>사유 신청이 아닌 건은 빠진다</b>(방화벽·정기일정).
     *
     * <p>⚠️ 비어 있으면 <b>쿼리를 보내지 않는다</b> — {@code in ()} 은 DB 에 따라
     * 문법 오류다.
     */
    private Map<Long, AbsenceSubmitterType> submitterTypes(List<ApprovalRequest> rows) {
        List<Long> ids = rows.stream().map(ApprovalRequest::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, AbsenceSubmitterType> out = new java.util.HashMap<>();
        for (Object[] row : absenceReasonRepository.findSubmitterTypes(ids)) {
            if (row[1] != null) {
                out.put((Long) row[0], (AbsenceSubmitterType) row[1]);
            }
        }
        return out;
    }

    private static boolean isOverdue(ApprovalRequest r, Instant now) {
        return r.getStatus() == ApprovalStatus.PENDING
                && r.getEscalationAt() != null
                && r.getEscalationAt().isBefore(now);
    }

    public record BoardResponse(List<Row> rows, Map<String, Long> summary) {
    }

    /**
     * @param currentApprover 지금 공을 쥔 쪽. 이양 전이면 우선 승인자, 이양 뒤면 직원이다 —
     *                        <b>우선 승인자만 보여주면 "왜 학부모가 안 하지"로 읽힌다</b>
     * @param overdue         대기 중인데 에스컬레이션 시각이 지났는가. 화면이 이 값으로 강조한다
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "ApprovalBoardRow")
    public record Row(Long id, RequestType requestType, ApprovalStatus status,
                      Long enrollmentId, String studentNo, String studentName,
                      Instant requestedAt, Instant escalationAt, short timeoutMinutes,
                      ApproverType primaryApprover, ApproverType currentApprover,
                      Instant reminderSentAt, Instant handedOverAt,
                      Instant resolvedAt, String rejectReason, boolean overdue,
                      /**
                       * ★★ 누가 낸 신청인가 — <b>사유 신청에만</b> 있다
                       * (디랩 1031 ③, 프런트 요청).
                       *
                       * <p>★ 학부모도 낼 수 있게 되면서 <b>「학부모 신청」을 구분해
                       * 보여줘야 한다.</b> ⚠️ 이 값이 없으면 담당자는 즉시 승인된 건을
                       * 보고도 <b>누가 확인한 것인지 알 수 없다.</b>
                       *
                       * <p>★ {@code null} 인 경우 둘 — <b>사유 신청이 아니거나</b>
                       * (방화벽·정기일정), 이 값이 생기기 전의 신청이다.
                       */
                      AbsenceSubmitterType submitterType) {

        static Row of(ApprovalRequest r, Instant now, AbsenceSubmitterType submitterType) {
            return new Row(r.getId(),
                    r.getApprovalItem().getRequestType(),
                    r.getStatus(),
                    r.getEnrollment().getId(),
                    r.getEnrollment().getStudentNo(),
                    r.getEnrollment().getStudentName(),
                    r.getRequestedAt(),
                    r.getEscalationAt(),
                    r.getTimeoutMinutes(),
                    r.getPrimaryApprover(),
                    r.getHandedOverAt() != null ? ApproverType.TEACHER : r.getPrimaryApprover(),
                    r.getReminderSentAt(),
                    r.getHandedOverAt(),
                    r.getResolvedAt(),
                    r.getRejectReason(),
                    isOverdue(r, now),
                    submitterType);
        }
    }
}
