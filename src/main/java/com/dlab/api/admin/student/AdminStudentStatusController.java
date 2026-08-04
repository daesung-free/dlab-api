package com.dlab.api.admin.student;

import com.dlab.api.admin.student.dto.StatusChangeRequest;
import com.dlab.api.admin.student.dto.StatusHistoryResponse;
import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.user.entity.EnrollmentStatus;
import com.dlab.domain.user.entity.EnrollmentStatusTransition;
import com.dlab.domain.user.service.StudentQueryService;
import com.dlab.domain.user.service.StudentStatusService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 학생 상태 관리 (P1-04).
 *
 * <p>상태 변경은 환불·재등록으로 이어지므로 <b>관리자 권한이 있어야 한다.</b>
 * 담당선생님이 학생을 제적시킬 수 있으면 안 된다.
 */
@RestController
@RequestMapping("/api/v1/admin/students/{enrollmentId}")
@RequiredArgsConstructor
public class AdminStudentStatusController {

    private final StudentStatusService studentStatusService;
    private final StudentQueryService studentQueryService;

    @PutMapping("/status")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'BRANCH_ADMIN')")
    public ApiResponse<StatusHistoryResponse> changeStatus(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long enrollmentId,
            @Valid @RequestBody StatusChangeRequest request) {

        studentStatusService.changeStatus(me, enrollmentId,
                request.status(), request.effectiveDate(), request.reason());

        // 방금 남은 이력을 그대로 돌려준다 — 화면이 상태 변경 직후 이력 목록을
        // 다시 부르지 않아도 되고, 서버가 실제로 무엇을 기록했는지 확인된다.
        return ApiResponse.success(
                studentStatusService.history(me, enrollmentId).stream()
                        .map(StatusHistoryResponse::from)
                        .findFirst()
                        .orElseThrow());
    }

    @GetMapping("/status-history")
    public ApiResponse<List<StatusHistoryResponse>> history(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long enrollmentId) {
        return ApiResponse.success(studentStatusService.history(me, enrollmentId).stream()
                .map(StatusHistoryResponse::from)
                .toList());
    }

    /**
     * 현재 상태에서 갈 수 있는 상태 목록.
     *
     * <p>화면이 전이 규칙을 자체 구현하면 서버 규칙이 바뀔 때 조용히 어긋난다 —
     * 눌러야만 실패하는 버튼이 남는다. 서버가 내려준다.
     */
    @GetMapping("/status-options")
    public ApiResponse<List<EnrollmentStatus>> options(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long enrollmentId) {
        var current = studentQueryService.getEnrollment(me, enrollmentId).getEnrollmentStatus();
        return ApiResponse.success(EnrollmentStatusTransition.allowedFrom(current).stream()
                .sorted()
                .toList());
    }
}
