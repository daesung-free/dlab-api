package com.dlab.api.app.survey;

import com.dlab.domain.survey.entity.Survey;
import com.dlab.domain.survey.entity.SurveyQuestion;
import com.dlab.domain.survey.entity.SurveyQuestionType;
import com.dlab.domain.survey.entity.SurveyType;
import com.dlab.domain.survey.service.SurveyService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 앱 설문 응답 형식. */
public final class SurveyResponses {

    private SurveyResponses() {
    }

    /**
     * 목록 한 줄.
     *
     * <p>{@code open}·{@code submitted}를 서버가 판정해 내린다 — 앱은 버튼 상태만 그린다.
     */
    /**
     * @param allowEdit 제출 후 기간 안에 고칠 수 있는가. {@code true}면 제출한 뒤에도 같은
     *                  {@code POST .../responses}로 다시 낸다(응답이 교체된다)
     */
    public record SurveySummary(Long id, SurveyType surveyType, String title, String description,
                          boolean anonymous, Instant opensAt, Instant closesAt,
                          boolean open, boolean submitted, boolean allowEdit) {

        static SurveySummary from(SurveyService.AppSurvey s) {
            Survey survey = s.survey();
            return new SurveySummary(survey.getId(), survey.getSurveyType(), survey.getTitle(),
                    survey.getDescription(), survey.isAnonymous(),
                    survey.getOpensAt(), survey.getClosesAt(), s.open(), s.submitted(),
                    survey.isAllowEdit());
        }
    }

    public record SurveyDetail(SurveySummary survey, List<SurveyQuestionView> questions) {

        static SurveyDetail from(SurveyService.AppSurvey s) {
            var questions = s.survey().activeQuestions();
            java.util.Map<Short, Long> idBySeq = questions.stream()
                    .collect(java.util.stream.Collectors.toMap(SurveyQuestion::getSeq,
                            SurveyQuestion::getId));
            return new SurveyDetail(SurveySummary.from(s),
                    questions.stream().map(q -> SurveyQuestionView.from(q, idBySeq)).toList());
        }
    }

    /**
     * @param showIfQuestionId 조건부 문항 — 이 문항에서 {@code showIfOptionId}를 골랐을 때만 그린다.
     *                         숨은 문항은 필수여도 안 물어도 되고, 보낸 답은 서버가 버린다
     * @param computed         합산 문항. <b>입력 칸이 아니다</b> — 서버가 {@code sumOfQuestionIds}의
     *                         값을 더해 채운다. 화면은 같은 합을 미리 보여주기만 한다
     */
    public record SurveyQuestionView(Long id, short seq, SurveyQuestionType type, String title,
                           boolean required, BigDecimal minValue, BigDecimal maxValue,
                           List<SurveyOptionView> options,
                           Long showIfQuestionId, Long showIfOptionId,
                           boolean computed, List<Long> sumOfQuestionIds) {

        static SurveyQuestionView from(SurveyQuestion q, java.util.Map<Short, Long> idBySeq) {
            return new SurveyQuestionView(q.getId(), q.getSeq(), q.getQuestionType(), q.getTitle(),
                    q.isRequired(), q.getMinValue(), q.getMaxValue(),
                    q.activeOptions().stream()
                            .map(o -> new SurveyOptionView(o.getId(), o.getSeq(), o.getLabel()))
                            .toList(),
                    q.isConditional() ? q.getShowIfQuestion().getId() : null,
                    q.isConditional() ? q.getShowIfOption().getId() : null,
                    q.isComputed(),
                    q.sumOfSeqList().stream().map(idBySeq::get)
                            .filter(java.util.Objects::nonNull).toList());
        }
    }

    public record SurveyOptionView(Long id, short seq, String label) {
    }

    /** 제출 결과. 익명이든 아니든 "냈다"는 사실만 돌려준다. */
    public record Submitted(Long responseId, Instant submittedAt) {

        static Submitted from(com.dlab.domain.survey.entity.SurveyResponse r) {
            return new Submitted(r.getId(), r.getSubmittedAt());
        }
    }

    public record MyResponse(Long responseId, Instant submittedAt, List<SurveyAnswerView> answers) {

        static MyResponse from(com.dlab.domain.survey.entity.SurveyResponse r) {
            return new MyResponse(r.getId(), r.getSubmittedAt(),
                    r.activeAnswers().stream()
                            .map(a -> new SurveyAnswerView(
                                    a.getQuestion().getId(),
                                    a.getOption() != null ? a.getOption().getId() : null,
                                    a.getOption() != null ? a.getOption().getLabel() : null,
                                    a.getTextValue(), a.getNumberValue()))
                            .toList());
        }
    }

    public record SurveyAnswerView(Long questionId, Long optionId, String optionLabel,
                         String textValue, BigDecimal numberValue) {
    }

    /** 임시저장. 제출 요청과 같은 모양이라 화면이 그대로 되살린다. */
    public record Draft(List<SurveyRequests.SurveyAnswerInput> answers, Instant savedAt) {

        static Draft from(SurveyService.Draft d) {
            return new Draft(d.answers().stream()
                    .map(a -> new SurveyRequests.SurveyAnswerInput(a.questionId(), a.optionIds(),
                            a.textValue(), a.numberValue()))
                    .toList(), d.savedAt());
        }

        static Draft empty() {
            return new Draft(List.of(), null);
        }
    }
}
