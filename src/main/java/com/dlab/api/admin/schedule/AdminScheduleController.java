package com.dlab.api.admin.schedule;

import com.dlab.api.app.schedule.ScheduleRequests;
import com.dlab.api.app.schedule.ScheduleResponse;
import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.schedule.service.RegularScheduleService;
import com.dlab.domain.schedule.service.ScheduleComplianceService;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 정기일정 관리 (F-4.1-7).
 *
 * <p><b>담임이 학생 대신 등록하는 트랙이다</b>(0803 추가). 여기서 넣은 일정은
 * <b>자동 승인</b>이다 — 승인자가 곧 등록자라 자기가 넣고 자기가 승인할 이유가 없다.
 *
 * <p><b>학생 등록분과의 병합 규칙은 미확정이다(I-27).</b> 그래서 같은 달에 이미 제출이
 * 있으면 거절하고 사람이 판단하게 둔다 — 규칙 없이 하나를 덮으면 담임이 넣은 일정이
 * 학생 등록으로 조용히 사라진다.
 */
@Tag(name = "관리자 · 정기일정 (F-4.1-7)")
@RestController
@RequestMapping("/api/v1/admin/schedules")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER','STAFF')")
public class AdminScheduleController {

    private final RegularScheduleService scheduleService;
    private final ScheduleComplianceService complianceService;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final Clock clock;

    /** 그 달에 정기일정을 낸 학생 전체. @param month 비우면 이번 달 */
    @GetMapping
    public ApiResponse<List<ScheduleResponse.ScheduleMonth>> list(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam short year,
            @RequestParam(required = false) Short month) {

        Long scope = resolveScope(me, academyId);
        short target = month == null ? (short) LocalDate.now(clock).getMonthValue() : month;

        return ApiResponse.success(scheduleService.findByAcademy(scope, year, target)
                .stream().map(ScheduleResponse.ScheduleMonth::from).toList());
    }

    /** 담임 대신 등록 — 자동 승인이다. */
    @PostMapping("/students/{enrollmentId}")
    public ApiResponse<ScheduleResponse.ScheduleMonth> register(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long enrollmentId,
            @Valid @RequestBody ScheduleRequests.ScheduleSubmit request) {

        return ApiResponse.success(ScheduleResponse.ScheduleMonth.from(scheduleService
                .registerByAdmin(me, enrollmentId, request.month(), request.toInputs())));
    }

    /** 정기일정 항목 교체. <b>통째로 갈아끼운다</b> — 병합하면 지운 항목을 지울 방법이 없다. */
    @PutMapping("/{scheduleId}/items")
    public ApiResponse<ScheduleResponse.ScheduleMonth> replaceItems(
            @PathVariable Long scheduleId,
            @Valid @RequestBody ScheduleRequests.Replace request) {
        return ApiResponse.success(ScheduleResponse.ScheduleMonth.from(
                scheduleService.replaceItems(scheduleId, request.toInputs())));
    }

    /** 정기일정 삭제(soft). */
    @DeleteMapping("/{scheduleId}")
    public ApiResponse<Void> delete(@PathVariable Long scheduleId) {
        scheduleService.delete(scheduleId);
        return ApiResponse.empty();
    }

    /**
     * 인정 판정 + 미인정이면 벌점.
     *
     * <p><b>규칙이 없으면 아무 일도 일어나지 않는다</b> — 트리거→점수 매핑(I-5)이 미확정이라
     * {@code penalty_rule} 행이 켜질 때까지 엔진이 통과한다. 다시 돌려도 멱등하다.
     */
    @PostMapping("/students/{enrollmentId}/compliance")
    public ApiResponse<List<ScheduleResponse.Compliance>> judge(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long enrollmentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));

        if (!me.canAccessAcademy(enrollment.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return ApiResponse.success(
                complianceService.judgeAndPenalize(enrollment, date).stream()
                        .map(ScheduleResponse.Compliance::from).toList());
    }

    /** 지점 스코프는 서버가 강제한다 — 요청 값을 그대로 믿으면 다른 지점이 새어나간다. */
    private Long resolveScope(AuthPrincipal me, Long requested) {
        return me.requireAcademyScope(requested);
    }
}
