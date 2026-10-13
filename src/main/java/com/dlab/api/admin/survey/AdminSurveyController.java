package com.dlab.api.admin.survey;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.survey.service.SurveyService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
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
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 관리자 — 설문 개설·마감·집계 (F-4.11-3 · F-4.6).
 *
 * <p><b>★ 문항 수정은 「제출 0건」일 때만 열린다</b>(2026-10-13 디랩 회신 5-1 — 장학 동의서가
 * 「구글 설문처럼 양식을 만들고 고치는 기능」을 요구했다). 전에는 아예 두지 않았는데,
 * 막아야 하는 것은 <b>응답이 들어온 뒤의 수정</b>이고 그것만 거절하면 된다.
 *
 * <p>제출이 있으면 고칠 수 없다 — 앞사람과 뒷사람이 다른 질문에 답한 결과가 한 집계에 섞이고,
 * 동의서라면 <b>이미 동의한 학생의 동의 내용이 소급해서 바뀐다.</b> 그때는
 * <b>복제({@code POST /{id}/duplicate})해서 새로 만든다</b> — 오류 문구가 그걸 안내한다.
 */
@Tag(name = "관리자 · 설문 (F-4.11-3)")
@RestController
@RequestMapping("/api/v1/admin/surveys")
@RequiredArgsConstructor
public class AdminSurveyController {

    private final SurveyService surveyService;

    /** 목록. 전 지점 설문도 함께 나온다 — 조회는 공유이고 제한되는 건 작성뿐이다. */
    @GetMapping
    public ApiResponse<List<AdminSurveyResponses.AdminSurveySummary>> list(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Short year) {

        return ApiResponse.success(surveyService.findForAdmin(me, year).stream()
                .map(AdminSurveyResponses.AdminSurveySummary::from).toList());
    }

    /** 개설. 문항까지 한 번에 받는다 — 문항 없는 설문이 앱에 노출되는 순간이 없어야 한다. */
    @PostMapping
    public ApiResponse<AdminSurveyResponses.AdminSurveySummary> create(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody AdminSurveyRequests.AdminSurveyCreate request) {

        return ApiResponse.success(AdminSurveyResponses.AdminSurveySummary.from(
                surveyService.create(me, request.toCommand())));
    }

    /** 기간·안내문 수정. */
    @PutMapping("/{surveyId}")
    public ApiResponse<AdminSurveyResponses.AdminSurveySummary> update(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long surveyId,
            @Valid @RequestBody AdminSurveyRequests.AdminSurveyUpdate request) {

        return ApiResponse.success(AdminSurveyResponses.AdminSurveySummary.from(surveyService.update(
                me, surveyId, request.title(), request.description(),
                request.opensAt(), request.closesAt())));
    }

    /** 즉시 마감. */
    @PatchMapping("/{surveyId}/close")
    public ApiResponse<AdminSurveyResponses.AdminSurveySummary> close(
            @CurrentAccount AuthPrincipal me, @PathVariable Long surveyId) {

        return ApiResponse.success(
                AdminSurveyResponses.AdminSurveySummary.from(surveyService.closeNow(me, surveyId)));
    }

    /**
     * 설문 삭제(soft).
     *
     * <p>응답이 들어온 뒤에는 <b>문항을 고치지 말고</b> 마감하고 새로 낸다 —
     * 앞사람과 뒷사람이 다른 질문에 답한 결과가 한 집계에 섞인다.
     */
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
    /**
     * 원시 응답 내려받기 (엑셀).
     *
     * <p>집계만으로는 부족한 설문이 있다 — 가채점처럼 개별 응답을 봐야 하는 경우다.
     *
     * <p>⚠️ <b>익명 설문은 학번·이름 칸이 빈다.</b> 익명은 응답 행에 응답자를 저장하지
     * 않는 구조라 되돌릴 값 자체가 없다.
     */
    @GetMapping("/{surveyId}/responses/export")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.ByteArrayResource>
            exportResponses(@CurrentAccount AuthPrincipal me, @PathVariable Long surveyId) {

        byte[] bytes = surveyService.exportResponses(me, surveyId);
        String filename = java.net.URLEncoder.encode("설문응답_%d.xlsx".formatted(surveyId),
                java.nio.charset.StandardCharsets.UTF_8);

        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + filename)
                .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                .body(new org.springframework.core.io.ByteArrayResource(bytes));
    }

    /**
     * 양식 복제 (장학 동의서 — 2026-10-13 회신 5-1).
     *
     * <p>문항을 그대로 옮기고 <b>제목만 새로 받는다.</b>
     * ⚠️ <b>대상자·응답은 복사하지 않는다</b> — 복사하면 남이 동의한 기록이 새 양식에 붙는다.
     */
    @PostMapping("/{surveyId}/duplicate")
    public ApiResponse<AdminSurveyResponses.AdminSurveySummary> duplicate(
            @CurrentAccount AuthPrincipal me, @PathVariable Long surveyId,
            @Valid @RequestBody SurveyTitle request) {
        return ApiResponse.success(AdminSurveyResponses.AdminSurveySummary.from(
                surveyService.duplicate(me, surveyId, request.title())));
    }

    /**
     * 문항 교체.
     *
     * <p>★★ <b>제출이 하나라도 있으면 400 이다.</b> 고치면 이미 동의한 학생의 동의 내용이
     * 소급해서 바뀐다 — 그때는 복제해서 새로 만든다(오류 문구가 안내한다).
     */
    @PutMapping("/{surveyId}/questions")
    public ApiResponse<AdminSurveyResponses.AdminSurveySummary> replaceQuestions(
            @CurrentAccount AuthPrincipal me, @PathVariable Long surveyId,
            @Valid @RequestBody AdminSurveyRequests.Questions request) {
        return ApiResponse.success(AdminSurveyResponses.AdminSurveySummary.from(
                surveyService.replaceQuestions(me, surveyId, request.toCommands())));
    }

    /**
     * 대상자 발송.
     *
     * <p>장학 동의서는 대상이 <b>범위가 아니라 사람</b>이다 — 직원이 장학을 부여한 학생에게만
     * 간다. <b>이미 대상자면 건너뛴다</b>(두 번 눌러도 행이 두 개가 되지 않는다).
     *
     * @return 이번에 새로 발송된 수
     */
    @PostMapping("/{surveyId}/targets")
    public ApiResponse<SentCount> assignTargets(
            @CurrentAccount AuthPrincipal me, @PathVariable Long surveyId,
            @Valid @RequestBody TargetRequest request) {
        return ApiResponse.success(
                new SentCount(surveyService.assignTargets(me, surveyId, request.enrollmentIds())));
    }

    /** @param title 복제본 제목. 같은 제목이 둘이면 담당자가 어느 것을 보냈는지 모른다 */
    public record SurveyTitle(@NotBlank(message = "새 제목을 적어 주세요.")
                              @Size(max = 200) String title) {
    }

    public record TargetRequest(@NotEmpty(message = "대상 학생을 골라 주세요.")
                                List<Long> enrollmentIds) {
    }

    /** @param sent 이번에 새로 발송된 수. 이미 대상자였던 학생은 세지 않는다 */
    public record SentCount(int sent) {
    }

    @GetMapping("/{surveyId}/participants")
    public ApiResponse<List<AdminSurveyResponses.Participant>> participants(
            @CurrentAccount AuthPrincipal me, @PathVariable Long surveyId) {

        return ApiResponse.success(surveyService.participants(me, surveyId).stream()
                .map(AdminSurveyResponses.Participant::from).toList());
    }
}
