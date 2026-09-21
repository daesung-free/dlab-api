package com.dlab.api.app.survey;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.survey.service.SurveyService;
import com.dlab.domain.user.service.AppScopeResolver;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 앱 — 설문 (A-14).
 *
 * <p><b>기간 판정은 전부 서버가 한다.</b> 목록에 {@code open}·{@code submitted}를 실어
 * 내리므로 앱은 버튼 상태만 그린다 — 앱이 시각을 비교하면 기기 시계가 틀어진 사용자에게
 * 마감된 설문이 열려 보이고, 제출했다가 거절당한다.
 *
 * <p><b>익명 설문은 응답 내용을 다시 볼 수 없다.</b> 응답 행에 응답자를 남기지 않기
 * 때문이고, 그게 익명의 정의다. 앱은 제출 완료 표시까지만 한다.
 */
@Tag(name = "앱 · 설문 (A-14)")
@RestController
@RequestMapping("/api/v1/app/surveys")
@RequiredArgsConstructor
public class AppSurveyController {

    private final SurveyService surveyService;
    private final AppScopeResolver scopeResolver;

    /**
     * 내게 배포된 설문 목록.
     *
     * @param studentId <b>학부모만</b> 쓴다. 계정 하나에 자녀가 여럿이라 서버가 고를 수 없다
     */
    @GetMapping
    public ApiResponse<List<SurveyResponses.SurveySummary>> list(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long studentId) {

        Long enrollmentId = scopeResolver.resolve(me.accountId(), studentId).getId();
        return ApiResponse.success(surveyService.feed(enrollmentId).stream()
                .map(SurveyResponses.SurveySummary::from).toList());
    }

    /** 상세 — 문항·선택지. 내게 배포된 설문인지 확인하고 내린다. */
    @GetMapping("/{surveyId}")
    public ApiResponse<SurveyResponses.SurveyDetail> detail(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long surveyId,
            @RequestParam(required = false) Long studentId) {

        Long enrollmentId = scopeResolver.resolve(me.accountId(), studentId).getId();
        return ApiResponse.success(
                SurveyResponses.SurveyDetail.from(surveyService.detail(enrollmentId, surveyId)));
    }

    /**
     * 응답 제출.
     *
     * <p>{@code allowEdit}이 켜진 설문은 기간 안에 <b>다시 낼 수 있다</b> — 같은 요청을 다시 보내면
     * 응답이 교체된다. 꺼져 있으면 한 번만 낼 수 있다. 제출하면 임시저장은 지워진다.
     */
    @PostMapping("/{surveyId}/responses")
    public ApiResponse<SurveyResponses.Submitted> submit(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long surveyId,
            @RequestParam(required = false) Long studentId,
            @Valid @RequestBody SurveyRequests.SurveySubmit request) {

        Long enrollmentId = scopeResolver.resolve(me.accountId(), studentId).getId();
        var response = surveyService.submit(enrollmentId, surveyId, request.toCommands());
        return ApiResponse.success(SurveyResponses.Submitted.from(response));
    }

    /** 내가 낸 응답. 익명 설문은 조회할 수 없다. */
    @GetMapping("/{surveyId}/responses/me")
    public ApiResponse<SurveyResponses.MyResponse> myResponse(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long surveyId,
            @RequestParam(required = false) Long studentId) {

        Long enrollmentId = scopeResolver.resolve(me.accountId(), studentId).getId();
        return ApiResponse.success(SurveyResponses.MyResponse.from(
                surveyService.myResponse(enrollmentId, surveyId)));
    }

    /**
     * 임시저장. <b>검증하지 않는다</b> — 필수가 빠져도 저장된다. 부를 때마다 덮어쓴다.
     *
     * <p>익명 설문과 이미 낸 설문은 받지 않는다(낸 설문은 {@code responses/me}로 불러와 다시 낸다).
     */
    @PutMapping("/{surveyId}/draft")
    public ApiResponse<SurveyResponses.Draft> saveDraft(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long surveyId,
            @RequestParam(required = false) Long studentId,
            @RequestBody SurveyRequests.SurveyDraftSave request) {

        Long enrollmentId = scopeResolver.resolve(me.accountId(), studentId).getId();
        surveyService.saveDraft(enrollmentId, surveyId, request.toCommands());
        return ApiResponse.success(surveyService.draft(enrollmentId, surveyId)
                .map(SurveyResponses.Draft::from).orElseGet(SurveyResponses.Draft::empty));
    }

    /** 임시저장 불러오기. 없으면 빈 목록({@code savedAt}이 비어 있다). */
    @GetMapping("/{surveyId}/draft")
    public ApiResponse<SurveyResponses.Draft> draft(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long surveyId,
            @RequestParam(required = false) Long studentId) {

        Long enrollmentId = scopeResolver.resolve(me.accountId(), studentId).getId();
        return ApiResponse.success(surveyService.draft(enrollmentId, surveyId)
                .map(SurveyResponses.Draft::from).orElseGet(SurveyResponses.Draft::empty));
    }
}
