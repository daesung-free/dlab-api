package com.dlab.api.app.vocab;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.user.service.AppScopeResolver;
import com.dlab.domain.vocab.service.VocabExamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 앱 — D:VOCA 단어 시험 (v1 6장).
 *
 * <h2>★★ 정답은 서버 밖으로 나가지 않는다 (v1 9장)</h2>
 * 응시 시작 응답에 <b>정답도 정답 위치도 없다.</b> 정오·정답은 <b>제출한 뒤에만</b> 내려간다 —
 * 열어 두면 시작만 하고 답을 보는 경로가 된다.
 *
 * <h2>★ 학생은 자기 지점에 공개된 회차만 본다</h2>
 * 지점 검증은 서버가 한다 — 회차 id 를 받는 구조라 빼면 <b>id 만 바꿔 남의 지점 회차를
 * 응시</b>할 수 있다.
 */
@Tag(name = "앱 · D:VOCA 단어 시험")
@RestController
@RequestMapping("/api/v1/app/vocab")
@RequiredArgsConstructor
public class AppVocabController {

    private final VocabExamService examService;
    private final AppScopeResolver scopeResolver;

    /** 회차 목록 — ★ 입학 전 회차는 구분해서 표시한다(v1 5.5). */
    @Operation(summary = "회차 목록", description = "공개된 회차와 응시 여부·점수")
    @GetMapping("/rounds")
    public ApiResponse<List<AppVocabResponse.Round>> rounds(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long studentId) {
        Long enrollmentId = scopeResolver.resolve(me.accountId(), studentId).getId();
        return ApiResponse.success(examService.rounds(enrollmentId).stream()
                .map(v -> AppVocabResponse.Round.of(
                        v.round(), v.representative(), v.beforeEnrollment()))
                .toList());
    }

    /**
     * 응시 시작.
     *
     * <p>★ 진행 중인 응시가 있으면 <b>그것을 돌려준다</b> — 새로 만들면 앱을 닫았다 열 때마다
     * 응시가 쌓이고, 어느 것이 그 회차 점수인지 알 수 없다.
     */
    @Operation(summary = "응시 시작", description = "문항을 만들어 내린다. ★ 정답은 내리지 않는다")
    @PostMapping("/rounds/{roundId}/attempts")
    public ApiResponse<AppVocabResponse.Start> start(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long roundId,
            @RequestParam(required = false) Long studentId) {
        Long enrollmentId = scopeResolver.resolve(me.accountId(), studentId).getId();
        return ApiResponse.success(
                AppVocabResponse.Start.from(examService.start(enrollmentId, roundId)));
    }

    /** 제출 — ★ 채점은 서버가 한다(v1 4.2). */
    @Operation(summary = "제출·채점", description = "서버가 채점하고 결과를 돌려준다")
    @PostMapping("/attempts/{attemptId}/submit")
    public ApiResponse<AppVocabResponse.Result> submit(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long attemptId,
            @RequestParam(required = false) Long studentId,
            @Valid @RequestBody AppVocabRequest.Submit request) {
        Long enrollmentId = scopeResolver.resolve(me.accountId(), studentId).getId();
        var attempt = examService.submit(enrollmentId, attemptId, request.asMap());
        return ApiResponse.success(
                AppVocabResponse.Result.of(attempt, examService.answers(attemptId)));
    }

    /** 결과 — ★ 제출 전에는 문항별 정오·정답이 비어 있다. */
    @Operation(summary = "결과", description = "점수·문항별 정오·정답")
    @GetMapping("/attempts/{attemptId}")
    public ApiResponse<AppVocabResponse.Result> result(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long attemptId,
            @RequestParam(required = false) Long studentId) {
        Long enrollmentId = scopeResolver.resolve(me.accountId(), studentId).getId();
        var attempt = examService.result(enrollmentId, attemptId);
        return ApiResponse.success(
                AppVocabResponse.Result.of(attempt, examService.answers(attemptId)));
    }

    /** 내 기록 — ★ 재응시 건까지 전부 남는다(v1 6장). */
    @Operation(summary = "내 기록", description = "응시 이력")
    @GetMapping("/attempts")
    public ApiResponse<List<AppVocabResponse.History>> history(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long studentId) {
        Long enrollmentId = scopeResolver.resolve(me.accountId(), studentId).getId();
        return ApiResponse.success(examService.history(enrollmentId).stream()
                .map(AppVocabResponse.History::from).toList());
    }
}
