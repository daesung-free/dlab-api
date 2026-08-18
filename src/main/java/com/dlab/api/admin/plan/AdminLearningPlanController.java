package com.dlab.api.admin.plan;

import com.dlab.api.app.plan.PlanRequests;
import com.dlab.api.app.plan.PlanResponse;
import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.plan.service.LearningPlanService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.AcademyRepository;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 학습계획 관리 (F-4.11).
 *
 * <p><b>계획을 고치는 API가 없다.</b> 0803 답변서가 <i>"과목별 시간 배분은 학생, 직원(담임)은
 * 이행 여부·통계만 확인"</i>으로 주도권을 학생에게 넘겼다 — 옛 그리드의 "파란색 = 교사
 * 편집분" 개념도 함께 사라졌다. 담임이 대신 짜는 경로를 다시 열면 그 전제가 무너진다.
 *
 * <p>관리자가 손대는 것은 <b>드롭다운 마스터</b>뿐이다(과목·학습형태). 탐구1/2 분리와
 * 과목 커스터마이즈가 요구사항이라 지점·연도마다 달라진다.
 */
@RestController
@RequestMapping("/api/v1/admin/learning-plans")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER','STAFF')")
public class AdminLearningPlanController {

    private final LearningPlanService planService;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final AcademyRepository academyRepository;

    // ─────────────────────────────────────────────────────────────
    // 학생별 조회 — 이행 여부·통계만
    // ─────────────────────────────────────────────────────────────

    /** @param date 그 주 아무 날짜나 */
    @GetMapping("/students/{enrollmentId}/weeks")
    public ApiResponse<PlanResponse.Week> week(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long enrollmentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        StudentEnrollment enrollment = requireEnrollment(me, enrollmentId);
        return ApiResponse.success(PlanResponse.Week.of(
                LearningPlanService.weekStart(date),
                planService.findWeek(enrollment.getId(), date)));
    }

    @GetMapping("/students/{enrollmentId}/statistics")
    public ApiResponse<PlanResponse.Statistics> statistics(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long enrollmentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        StudentEnrollment enrollment = requireEnrollment(me, enrollmentId);
        return ApiResponse.success(PlanResponse.Statistics.from(from, to,
                planService.statistics(enrollment.getId(), from, to)));
    }

    // ─────────────────────────────────────────────────────────────
    // 드롭다운 마스터
    // ─────────────────────────────────────────────────────────────

    @GetMapping("/options")
    public ApiResponse<List<PlanResponse.Option>> options(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam short year) {

        return ApiResponse.success(planService.options(resolveScope(me, academyId), year)
                .stream().map(PlanResponse.Option::from).toList());
    }

    @PostMapping("/options")
    public ApiResponse<PlanResponse.Option> createOption(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam short year,
            @Valid @RequestBody PlanRequests.SaveOption request) {

        Academy academy = academyRepository.findById(resolveScope(me, academyId))
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));

        return ApiResponse.success(PlanResponse.Option.from(planService.createOption(
                academy, year, request.optionType(), request.label(), request.sortOrder())));
    }

    @PutMapping("/options/{optionId}")
    public ApiResponse<PlanResponse.Option> updateOption(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @PathVariable Long optionId,
            @Valid @RequestBody PlanRequests.SaveOption request) {

        return ApiResponse.success(PlanResponse.Option.from(planService.updateOption(
                resolveScope(me, academyId), optionId, request.label(), request.sortOrder())));
    }

    /** soft delete — 이미 이 과목으로 쌓인 계획의 통계가 사라지면 안 된다. */
    @DeleteMapping("/options/{optionId}")
    public ApiResponse<Void> deleteOption(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @PathVariable Long optionId) {

        planService.deleteOption(resolveScope(me, academyId), optionId);
        return ApiResponse.empty();
    }

    // ─────────────────────────────────────────────────────────────

    private StudentEnrollment requireEnrollment(AuthPrincipal me, Long enrollmentId) {
        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));

        if (!me.canAccessAcademy(enrollment.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return enrollment;
    }

    /** 지점 스코프는 서버가 강제한다 — 요청 값을 그대로 믿으면 다른 지점이 새어나간다. */
    private Long resolveScope(AuthPrincipal me, Long requested) {
        if (me.allAcademy()) {
            if (requested == null) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "지점을 지정해 주세요.");
            }
            return requested;
        }
        if (requested != null && !me.canAccessAcademy(requested)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return me.academyScopeFilter();
    }
}
