package com.dlab.api.admin.survey;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.survey.service.SurveyService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 — 설문 개설·마감·집계 (F-4.11-3 · F-4.6).
 *
 * <p><b>문항 수정 API는 두지 않는다.</b> 응답이 들어온 뒤 문항이 바뀌면 앞사람과 뒷사람이
 * 다른 질문에 답한 결과가 한 집계에 섞인다 — 고칠 일이 생기면 마감하고 새로 낸다.
 * 바꿀 수 있는 건 기간·안내문뿐이다.
 */
@RestController
@RequestMapping("/api/v1/admin/surveys")
@RequiredArgsConstructor
public class AdminSurveyController {

    private final SurveyService surveyService;

    /** 목록. 전 지점 설문도 함께 나온다 — 조회는 공유이고 제한되는 건 작성뿐이다. */
    @GetMapping
    public ApiResponse<List<AdminSurveyResponses.Summary>> list(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Short year) {

        return ApiResponse.success(surveyService.findForAdmin(me, year).stream()
                .map(AdminSurveyResponses.Summary::from).toList());
    }

    /** 개설. 문항까지 한 번에 받는다 — 문항 없는 설문이 앱에 노출되는 순간이 없어야 한다. */
    @PostMapping
    public ApiResponse<AdminSurveyResponses.Summary> create(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody AdminSurveyRequests.Create request) {

        return ApiResponse.success(AdminSurveyResponses.Summary.from(
                surveyService.create(me, request.toCommand())));
    }

    /** 기간·안내문 수정. */
    @PutMapping("/{surveyId}")
    public ApiResponse<AdminSurveyResponses.Summary> update(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long surveyId,
            @Valid @RequestBody AdminSurveyRequests.Update request) {

        return ApiResponse.success(AdminSurveyResponses.Summary.from(surveyService.update(
                me, surveyId, request.title(), request.description(),
                request.opensAt(), request.closesAt())));
    }

    /** 즉시 마감. */
    @PatchMapping("/{surveyId}/close")
    public ApiResponse<AdminSurveyResponses.Summary> close(
            @CurrentAccount AuthPrincipal me, @PathVariable Long surveyId) {

        return ApiResponse.success(
                AdminSurveyResponses.Summary.from(surveyService.closeNow(me, surveyId)));
    }

    @DeleteMapping("/{surveyId}")
    public ApiResponse<Void> delete(@CurrentAccount AuthPrincipal me,
                                    @PathVariable Long surveyId) {
        surveyService.delete(me, surveyId);
        return ApiResponse.empty();
    }

    /**
     * 결과 집계.
     *
     * <p><b>응답자는 내리지 않는다</b> — 익명 설문에는 애초에 없고, 실명이라도 집계 화면에
     * 개인을 실을 이유가 없다.
     */
    @GetMapping("/{surveyId}/results")
    public ApiResponse<AdminSurveyResponses.Result> results(
            @CurrentAccount AuthPrincipal me, @PathVariable Long surveyId) {

        return ApiResponse.success(
                AdminSurveyResponses.Result.from(surveyService.results(me, surveyId)));
    }

    /** 제출자 목록. 익명 설문이어도 <b>누가 냈는지</b>는 알 수 있다 — 미제출자 독려용이다. */
    @GetMapping("/{surveyId}/participants")
    public ApiResponse<List<AdminSurveyResponses.Participant>> participants(
            @CurrentAccount AuthPrincipal me, @PathVariable Long surveyId) {

        return ApiResponse.success(surveyService.participants(me, surveyId).stream()
                .map(AdminSurveyResponses.Participant::from).toList());
    }
}
