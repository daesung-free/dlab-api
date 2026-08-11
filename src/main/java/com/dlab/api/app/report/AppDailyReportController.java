package com.dlab.api.app.report;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.report.entity.RankingPeriod;
import com.dlab.domain.report.service.DailyReportService;
import com.dlab.domain.report.service.StudyTimeRankingService;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.service.AppScopeResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

/**
 * Daily Report (F-4.11-6, 앱 A-3).
 *
 * <p><b>학부모도 본다.</b> 자녀가 여럿이라 서버가 대상을 고를 수 없어 {@code studentId}를
 * 받는다 — {@link AppScopeResolver}가 내 자녀가 맞는지 확인한다.
 *
 * <p><b>셀프 피드백은 학생만 쓴다.</b> 학부모가 자녀 회고를 대신 쓰면 회고가 아니다.
 */
@RestController
@RequestMapping("/api/v1/app/daily-reports")
@RequiredArgsConstructor
public class AppDailyReportController {

    private final AppScopeResolver scopeResolver;
    private final DailyReportService dailyReportService;
    private final StudyTimeRankingService rankingService;
    private final Clock clock;

    /** 하루 상세. 달력에서 일자를 탭하면 열린다. */
    @GetMapping("/{date}")
    public ApiResponse<DailyReportResponse.Day> day(
            @CurrentAccount AuthPrincipal me,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Long studentId) {

        StudentEnrollment enrollment = scopeResolver.resolve(me.accountId(), studentId);
        return ApiResponse.success(
                DailyReportResponse.Day.from(dailyReportService.day(enrollment, date)));
    }

    /**
     * 달력(월간).
     *
     * @param month {@code yyyy-MM}. 비우면 이번 달
     */
    @GetMapping("/monthly")
    public ApiResponse<DailyReportResponse.Month> monthly(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) Long studentId) {

        StudentEnrollment enrollment = scopeResolver.resolve(me.accountId(), studentId);
        LocalDate base = month == null || month.isBlank()
                ? LocalDate.now(clock)
                : LocalDate.parse(month + "-01");

        return ApiResponse.success(DailyReportResponse.Month.from(
                dailyReportService.month(enrollment, base.getYear(), base.getMonthValue())));
    }

    /**
     * 순공 랭킹 — 전체 1등 · 지점 1등 · 내 등수 (A-3).
     *
     * <p><b>어제 기준이다.</b> 오늘 순공은 새벽 확정 전까지 값이 없다 — 오늘을 기준으로
     * 잡으면 매일 아침 랭킹이 통째로 비어 보인다.
     */
    @GetMapping("/rankings")
    public ApiResponse<StudyTimeRankingService.RankingView> rankings(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(defaultValue = "WEEKLY") RankingPeriod period,
            @RequestParam(required = false) Long studentId) {

        StudentEnrollment enrollment = scopeResolver.resolve(me.accountId(), studentId);
        return ApiResponse.success(
                rankingService.view(enrollment, period, LocalDate.now(clock).minusDays(1)));
    }

    /** 셀프 피드백 저장. 하루 1행이라 다시 쓰면 덮어쓴다. */
    @PatchMapping("/{date}/self-feedback")
    public ApiResponse<Void> writeFeedback(
            @CurrentAccount AuthPrincipal me,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestBody @Valid FeedbackRequest request) {

        StudentEnrollment enrollment =
                scopeResolver.requireStudent(me.accountId(), "셀프 피드백");
        dailyReportService.writeFeedback(enrollment, date, request.content());
        return ApiResponse.empty();
    }

    public record FeedbackRequest(@NotBlank @Size(max = 500) String content) {
    }
}
