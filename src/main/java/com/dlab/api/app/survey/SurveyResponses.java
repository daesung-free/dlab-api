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
    public record SurveySummary(Long id, SurveyType surveyType, String title, String description,
                          boolean anonymous, Instant opensAt, Instant closesAt,
                          boolean open, boolean submitted) {

        static SurveySummary from(SurveyService.AppSurvey s) {
            Survey survey = s.survey();
            return new SurveySummary(survey.getId(), survey.getSurveyType(), survey.getTitle(),
                    survey.getDescription(), survey.isAnonymous(),
                    survey.getOpensAt(), survey.getClosesAt(), s.open(), s.submitted());
        }
    }

    public record SurveyDetail(SurveySummary survey, List<SurveyQuestionView> questions) {

        static SurveyDetail from(SurveyService.AppSurvey s) {
            return new SurveyDetail(SurveySummary.from(s),
                    s.survey().activeQuestions().stream().map(SurveyQuestionView::from).toList());
        }
    }

    public record SurveyQuestionView(Long id, short seq, SurveyQuestionType type, String title,
                           boolean required, BigDecimal minValue, BigDecimal maxValue,
                           List<SurveyOptionView> options) {

        static SurveyQuestionView from(SurveyQuestion q) {
            return new SurveyQuestionView(q.getId(), q.getSeq(), q.getQuestionType(), q.getTitle(),
                    q.isRequired(), q.getMinValue(), q.getMaxValue(),
                    q.activeOptions().stream()
                            .map(o -> new SurveyOptionView(o.getId(), o.getSeq(), o.getLabel()))
                            .toList());
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
}
