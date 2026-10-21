package com.dlab.api.admin.vocab;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.vocab.service.VocabScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 통합관리 — D:VOCA 지점 시험 일정 (v1 7장).
 *
 * <h2>★★ 일정을 미리 만들어 두지 않는다 (v1 5.6)</h2>
 * {@code /preview} 는 <b>계산</b>이라 아무것도 저장하지 않는다. 저장은 예정일이 되어
 * 공개될 때만 일어난다 — 보기만 해도 회차가 공개되면 안 된다.
 *
 * <h2>★ 지점을 비우면 내 지점이다</h2>
 * {@code academyId} 를 그대로 믿지 않고 {@code requireAcademyScope} 로 검사한다 —
 * 믿으면 <b>지점 관리자가 남의 지점 일정을 고칠 수 있다.</b>
 */
@Tag(name = "통합관리 · D:VOCA 시험 일정")
@RestController
@RequestMapping("/api/v1/admin/vocab/schedule")
@RequiredArgsConstructor
public class AdminVocabScheduleController {

    private final VocabScheduleService scheduleService;
    private final Clock clock;

    /** 지점 시작일·출제 설정 조회. */
    @Operation(summary = "일정 설정 조회")
    @GetMapping("/config")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER','STAFF','READONLY')")
    public ApiResponse<AdminVocabScheduleResponse.Config> config(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam(required = false) Short year) {
        Long scope = me.requireAcademyScope(academyId);
        return ApiResponse.success(AdminVocabScheduleResponse.Config.from(
                scheduleService.config(scope, yearOf(year))));
    }

    /**
     * 지점 시작일·출제 설정 저장.
     *
     * <p>⚠️ <b>시작일을 바꿔도 이미 공개된 회차는 그대로다.</b> 저장된 회차를 덮으면
     * 학생이 이미 응시한 회차의 범위가 바뀐다(v1 5.6).
     */
    @Operation(summary = "일정 설정 저장", description = "비워 보낸 항목은 바꾸지 않는다")
    @PutMapping("/config")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
    public ApiResponse<AdminVocabScheduleResponse.Config> saveConfig(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Short year,
            @Valid @RequestBody AdminVocabScheduleRequest.SaveConfig request) {
        Long scope = me.requireAcademyScope(request.academyId());
        return ApiResponse.success(AdminVocabScheduleResponse.Config.from(
                scheduleService.saveConfig(scope, yearOf(year), request.startDate(),
                        request.questionCount(), request.weeklyQuestionCap(),
                        request.choiceCount(), request.multipleChoiceRate())));
    }

    /** 시험 없는 날 목록. */
    @Operation(summary = "시험 없는 날 목록")
    @GetMapping("/no-exam-days")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER','STAFF','READONLY')")
    public ApiResponse<List<AdminVocabScheduleResponse.NoExamDay>> noExamDays(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam(required = false) Short year) {
        Long scope = me.requireAcademyScope(academyId);
        return ApiResponse.success(scheduleService.noExamDays(scope, yearOf(year)).stream()
                .map(AdminVocabScheduleResponse.NoExamDay::from).toList());
    }

    /** 시험 없는 날 등록 — ★ 그날은 회차가 공개되지 않고 진도가 <b>밀린다</b>(건너뛰지 않는다). */
    @Operation(summary = "시험 없는 날 등록", description = "공휴일·모의고사 등")
    @PostMapping("/no-exam-days")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
    public ApiResponse<AdminVocabScheduleResponse.NoExamDay> addNoExamDay(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Short year,
            @Valid @RequestBody AdminVocabScheduleRequest.NoExamDay request) {
        Long scope = me.requireAcademyScope(request.academyId());
        return ApiResponse.success(AdminVocabScheduleResponse.NoExamDay.from(
                scheduleService.addNoExamDay(scope, yearOf(year),
                        request.date(), request.reason())));
    }

    /** 시험 없는 날 삭제 — ⚠️ <b>이미 공개된 회차는 되돌아가지 않는다.</b> */
    @Operation(summary = "시험 없는 날 삭제")
    @DeleteMapping("/no-exam-days")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
    public ApiResponse<Void> removeNoExamDay(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam(required = false) Short year,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Long scope = me.requireAcademyScope(academyId);
        scheduleService.removeNoExamDay(scope, yearOf(year), date);
        return ApiResponse.success(null);
    }

    /**
     * 회차 달력 미리보기 (v1 7장).
     *
     * <p>★ <b>저장하지 않는다.</b> {@code published} 가 <b>이미 공개돼 바뀌지 않는 회차</b>를
     * 표시한다 — 관리자가 시험 없는 날을 넣기 전에 「무엇이 영향을 받는가」를 알아야 한다.
     */
    @Operation(summary = "회차 달력 미리보기", description = "★ 계산 결과다. 저장하지 않는다")
    @GetMapping("/preview")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER','STAFF','READONLY')")
    public ApiResponse<List<AdminVocabScheduleResponse.Preview>> preview(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam(required = false) Short year,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate until) {
        Long scope = me.requireAcademyScope(academyId);
        Short target = yearOf(year);
        // 끝날짜를 안 주면 그 해 말까지 본다
        LocalDate end = until != null ? until : LocalDate.of(target, 12, 31);

        Set<String> published = scheduleService.openRounds(scope, target).stream()
                .map(r -> r.getOpenDate() + ":" + r.isWeekly())
                .collect(Collectors.toSet());

        return ApiResponse.success(scheduleService.preview(scope, target, end).stream()
                .map(r -> AdminVocabScheduleResponse.Preview.of(
                        r, published.contains(r.date() + ":" + r.weekly())))
                .toList());
    }

    /** 공개된 회차 목록 — 저장된 것만이다. */
    @Operation(summary = "공개된 회차 목록")
    @GetMapping("/rounds")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER','STAFF','READONLY')")
    public ApiResponse<List<AdminVocabScheduleResponse.Round>> rounds(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam(required = false) Short year) {
        Long scope = me.requireAcademyScope(academyId);
        return ApiResponse.success(scheduleService.openRounds(scope, yearOf(year)).stream()
                .map(AdminVocabScheduleResponse.Round::from).toList());
    }

    /**
     * 오늘까지의 회차를 공개한다.
     *
     * <p>★ <b>멱등하다</b> — 두 번 눌러도 같은 회차가 두 줄로 생기지 않는다. 배치가 도는
     * 것이 기본이고, 이건 <b>누락을 손으로 메우는 경로</b>다.
     */
    @Operation(summary = "회차 공개", description = "오늘까지의 회차를 저장한다. ★ 멱등하다")
    @PostMapping("/publish")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
    public ApiResponse<Map<String, Integer>> publish(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam(required = false) Short year) {
        Long scope = me.requireAcademyScope(academyId);
        int created = scheduleService.publishDueRounds(scope, yearOf(year));
        return ApiResponse.success(Map.of("created", created));
    }

    private Short yearOf(Short year) {
        return year != null ? year : (short) LocalDate.now(clock).getYear();
    }
}
