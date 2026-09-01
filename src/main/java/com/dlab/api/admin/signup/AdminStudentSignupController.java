package com.dlab.api.admin.signup;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.user.entity.OnboardingStatus;
import com.dlab.domain.user.service.StudentSignupApprovalService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 학생 가입 승인 (F-4.1 · A-2).
 *
 * <p><b>승인 주체는 행정({@code Employee})이다</b> — 담당선생님이 아니다(CLAUDE.md §2).
 */
@Tag(name = "관리자 · 학생 가입 승인 (F-4.12-1)")
@RestController
@RequestMapping("/api/v1/admin/student-signups")
@RequiredArgsConstructor
public class AdminStudentSignupController {

    private final StudentSignupApprovalService approvalService;

    /**
     * 승인 대기 목록. 지점 스코프가 걸린다.
     *
     * @param academyId 조회할 지점. <b>비우면 내 지점</b>이다.
     *                  전 지점 권한자(본사)는 지정해야 한다
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'BRANCH_ADMIN', 'STAFF')")
    public ApiResponse<List<PendingSignupResponse>> pending(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId) {
        return ApiResponse.success(approvalService.pending(me, academyId).stream()
                .map(PendingSignupResponse::from)
                .toList());
    }

    /** 가입 승인. 이미 승인된 건은 멱등하게 통과한다. */
    @PostMapping("/{enrollmentId}/approve")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'BRANCH_ADMIN', 'STAFF')")
    public ApiResponse<Void> approve(@CurrentAccount AuthPrincipal me,
                                     @PathVariable Long enrollmentId) {
        approvalService.approve(me, enrollmentId);
        return ApiResponse.success(null);
    }

    /**
     * 대면 OT 완료 처리.
     *
     * <p>OT는 오프라인이라 관리자가 눌러줘야 앱이 다음 단계로 넘어간다 —
     * 앱이 스스로 못 넘기는 유일한 단계다.
     */
    @PostMapping("/{enrollmentId}/ot-complete")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'BRANCH_ADMIN', 'STAFF')")
    public ApiResponse<OnboardingStatus> completeOt(@CurrentAccount AuthPrincipal me,
                                                    @PathVariable Long enrollmentId) {
        return ApiResponse.success(
                approvalService.completeOt(me, enrollmentId).getOnboardingStatus());
    }

    /**
     * @param onboardingStatus 승인과 별개 축이다. 승인 직후에도 {@code REGISTERED}(OT 전)다
     */
    public record PendingSignupResponse(
            Long enrollmentId,
            Long accountId,
            String studentNo,
            String name,
            String phone,
            OnboardingStatus onboardingStatus
    ) {
        static PendingSignupResponse from(StudentSignupApprovalService.PendingSignup p) {
            return new PendingSignupResponse(
                    p.enrollment().getId(),
                    p.account().getId(),
                    p.enrollment().getStudentNo(),
                    p.enrollment().getStudent().getName(),
                    p.enrollment().getStudent().getPhone(),
                    p.enrollment().getStudent().getOnboardingStatus());
        }
    }
}
