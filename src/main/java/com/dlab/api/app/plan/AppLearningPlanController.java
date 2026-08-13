package com.dlab.api.app.plan;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.plan.entity.LearningPlan;
import com.dlab.domain.plan.service.LearningPlanService;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.service.AppScopeResolver;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

/**
 * 주·일 학습계획 (앱 A-12) — 순번 기반.
 *
 * <p><b>입력은 학생 본인만 한다.</b> 과목별 시간 배분의 주도권이 학생이라는 것이
 * 0803 답변서의 핵심이다 — 학부모가 대신 짜면 그 전제가 무너진다. 조회·통계는 학부모도 본다.
 *
 * <p><b>교시 개념이 없다.</b> 서버가 교시·시간대를 내려주고 앱이 그리던 옛 그리드는
 * 폐기됐다 — 앱은 학생이 입력한 항목을 순번 순서로만 렌더한다.
 */
@RestController
@RequestMapping("/api/v1/app/learning-plans")
@RequiredArgsConstructor
public class AppLearningPlanController {

    private final AppScopeResolver scopeResolver;
    private final LearningPlanService planService;
    private final Clock clock;

    /** 드롭다운 마스터(과목·학습형태). 입력 화면을 열기 전에 한 번 받는다. */
    @GetMapping("/options")
    public ApiResponse<List<PlanResponse.Option>> options(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long studentId) {

        StudentEnrollment enrollment = scopeResolver.resolve(me.accountId(), studentId);
        return ApiResponse.success(planService
                .options(enrollment.getAcademy().getId(), enrollment.getYear())
                .stream().map(PlanResponse.Option::from).toList());
    }

    /** 일간 뷰. @param date 비우면 오늘 */
    @GetMapping("/days")
    public ApiResponse<PlanResponse.Day> day(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Long studentId) {

        StudentEnrollment enrollment = scopeResolver.resolve(me.accountId(), studentId);
        LocalDate target = date == null ? LocalDate.now(clock) : date;

        LearningPlan plan = planService.findDay(enrollment.getId(), target);
        return ApiResponse.success(
                plan == null ? PlanResponse.Day.empty(target) : PlanResponse.Day.from(plan));
    }

    /** 주간 뷰. @param date 그 주 아무 날짜나. 비우면 이번 주 */
    @GetMapping("/weeks")
    public ApiResponse<PlanResponse.Week> week(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Long studentId) {

        StudentEnrollment enrollment = scopeResolver.resolve(me.accountId(), studentId);
        LocalDate target = date == null ? LocalDate.now(clock) : date;

        return ApiResponse.success(PlanResponse.Week.of(
                LearningPlanService.weekStart(target),
                planService.findWeek(enrollment.getId(), target)));
    }

    /**
     * 하루치 통째 저장.
     *
     * <p><b>PUT인 이유</b> — 줄 단위 추가/수정/삭제를 따로 열면 순번 재부여를 클라이언트가
     * 맞춰야 한다. 하루치를 통째로 보내면 서버가 시작시각 순으로 매기고 끝난다.
     */
    @PutMapping("/days/{date}")
    public ApiResponse<PlanResponse.Day> saveDay(
            @CurrentAccount AuthPrincipal me,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody PlanRequests.SaveDay request) {

        StudentEnrollment enrollment =
                scopeResolver.requireStudent(me.accountId(), "학습계획 작성");
        return ApiResponse.success(PlanResponse.Day.from(
                planService.saveDay(enrollment, date, request.toCommands())));
    }

    /** 이행 O/X (I-19 확정 — 부분이행 없음). */
    @PatchMapping("/days/{date}/items/{itemId}")
    public ApiResponse<PlanResponse.Item> mark(
            @CurrentAccount AuthPrincipal me,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @PathVariable Long itemId,
            @Valid @RequestBody PlanRequests.Mark request) {

        StudentEnrollment enrollment =
                scopeResolver.requireStudent(me.accountId(), "이행 체크");
        return ApiResponse.success(PlanResponse.Item.from(
                planService.mark(enrollment, date, itemId, request.done())));
    }

    /**
     * "지난 주 계획 그대로 불러오기".
     *
     * <p>이미 짜 둔 날은 건너뛰므로, 응답의 날짜 목록이 실제로 채워진 날이다.
     *
     * @param date 채울 주의 아무 날짜나. 비우면 이번 주
     */
    @PostMapping("/weeks/copy")
    public ApiResponse<List<LocalDate>> copyPreviousWeek(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        StudentEnrollment enrollment =
                scopeResolver.requireStudent(me.accountId(), "지난 주 계획 불러오기");
        LocalDate target = date == null ? LocalDate.now(clock) : date;

        return ApiResponse.success(planService.copyPreviousWeek(enrollment, target));
    }

    /**
     * 과목·형태별 누적 시간.
     *
     * <p><b>순공시간과 다른 값이다.</b> 여기 "이행 시간"은 O 체크된 계획의 소요시간 합이고,
     * 순공시간은 출결 태깅에서 나온다 — 같은 화면에 나란히 놓으면 학생이 두 값이 다른 것을
     * 오류로 본다.
     */
    @GetMapping("/statistics")
    public ApiResponse<PlanResponse.Statistics> statistics(
            @CurrentAccount AuthPrincipal me,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long studentId) {

        StudentEnrollment enrollment = scopeResolver.resolve(me.accountId(), studentId);
        return ApiResponse.success(PlanResponse.Statistics.from(from, to,
                planService.statistics(enrollment.getId(), from, to)));
    }
}
