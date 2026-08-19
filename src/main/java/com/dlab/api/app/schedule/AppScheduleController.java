package com.dlab.api.app.schedule;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.schedule.service.RegularScheduleService;
import com.dlab.domain.schedule.service.ScheduleComplianceService;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.service.AppScopeResolver;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 정기일정 (앱 A-7).
 *
 * <p><b>등록·수정은 학생 본인만 한다.</b> 학부모가 자녀 일정을 대신 내면 학부모가 승인자인
 * 구조에서 자기가 내고 자기가 승인하게 된다 — 조회는 학부모도 한다.
 *
 * <p>담임이 대신 넣는 트랙은 관리자 웹이다(자동 승인).
 */
@Tag(name = "앱 · 정기일정 (A-7)")
@RestController
@RequestMapping("/api/v1/app/schedules")
@RequiredArgsConstructor
public class AppScheduleController {

    private final AppScopeResolver scopeResolver;
    private final RegularScheduleService scheduleService;
    private final ScheduleComplianceService complianceService;
    private final Clock clock;

    /**
     * 이번 달 정기일정 (A-7).
     *
     * <p>현강·과외처럼 <b>매주 같은 요일에 나갔다 오는 일정</b>을 월 단위로 등록해 둔 것이다.
     * 승인되면 그 시간의 외출이 무단이 아니게 되어 벌점을 받지 않는다.
     *
     * @param month 비우면 이번 달
     */
    @GetMapping
    public ApiResponse<ScheduleResponse.Month> month(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Short month,
            @RequestParam(required = false) Long studentId) {

        StudentEnrollment enrollment = scopeResolver.resolve(me.accountId(), studentId);
        short target = month == null ? (short) LocalDate.now(clock).getMonthValue() : month;

        return ApiResponse.success(scheduleService
                .findMonth(enrollment.getId(), enrollment.getYear(), target)
                .map(ScheduleResponse.Month::from)
                .orElse(null));
    }

    /**
     * 한 달치 제출.
     *
     * <p><b>월 단위로 한 번 승인받는다</b> — 요일이 여러 개여도 승인은 한 번이다. 줄마다
     * 승인을 만들면 학부모 대기 목록에 같은 학생이 여러 번 뜬다.
     *
     * <p>같은 달에 이미 제출한 게 있으면 거절된다 — 수정은 기존 제출을 고친다.
     */
    @PostMapping
    public ApiResponse<ScheduleResponse.Month> submit(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody ScheduleRequests.Submit request) {

        StudentEnrollment enrollment =
                scopeResolver.requireStudent(me.accountId(), "정기일정 등록");
        return ApiResponse.success(ScheduleResponse.Month.from(scheduleService
                .submitByStudent(enrollment, request.month(), request.toInputs())));
    }

    /** 줄을 통째로 갈아끼운다. <b>학생이 고치면 승인을 다시 받는다.</b> */
    @PutMapping("/{scheduleId}/items")
    public ApiResponse<ScheduleResponse.Month> replaceItems(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long scheduleId,
            @Valid @RequestBody ScheduleRequests.Replace request) {

        scopeResolver.requireStudent(me.accountId(), "정기일정 수정");
        return ApiResponse.success(ScheduleResponse.Month.from(
                scheduleService.replaceItems(scheduleId, request.toInputs())));
    }

    /** 제출 취소. 승인 대기 중인 요청도 함께 취소된다. */
    @DeleteMapping("/{scheduleId}")
    public ApiResponse<Void> delete(@CurrentAccount AuthPrincipal me,
                                    @PathVariable Long scheduleId) {
        scopeResolver.requireStudent(me.accountId(), "정기일정 삭제");
        scheduleService.delete(scheduleId);
        return ApiResponse.empty();
    }

    /**
     * 그날 인정 여부 (서버 판정, 앱은 결과만 표시).
     *
     * <p>여기서는 판정만 하고 <b>벌점을 부여하지 않는다</b> — 학생이 화면을 여는 것만으로
     * 벌점이 생기면 안 된다. 부여는 관리자 웹·배치 경로에서 한다.
     */
    @GetMapping("/compliance")
    public ApiResponse<List<ScheduleResponse.Compliance>> compliance(
            @CurrentAccount AuthPrincipal me,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Long studentId) {

        StudentEnrollment enrollment = scopeResolver.resolve(me.accountId(), studentId);
        return ApiResponse.success(complianceService.judge(enrollment, date).stream()
                .map(ScheduleResponse.Compliance::from).toList());
    }
}
