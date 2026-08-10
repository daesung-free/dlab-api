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
    public record Summary(Long id, SurveyType surveyType, String title, String description,
                          boolean anonymous, Instant opensAt, Instant closesAt,
                          boolean open, boolean submitted) {

        static Summary from(SurveyService.AppSurvey s) {
            Survey survey = s.survey();
            return new Summary(survey.getId(), survey.getSurveyType(), survey.getTitle(),
                    survey.getDescription(), survey.isAnonymous(),
                    survey.getOpensAt(), survey.getClosesAt(), s.open(), s.submitted());
        }
    }

    public record Detail(Summary survey, List<Question> questions) {

        static Detail from(SurveyService.AppSurvey s) {
            return new Detail(Summary.from(s),
                    s.survey().activeQuestions().stream().map(Question::from).toList());
        }
    }

    public record Question(Long id, short seq, SurveyQuestionType type, String title,
                           boolean required, BigDecimal minValue, BigDecimal maxValue,
                           List<Option> options) {

        static Question from(SurveyQuestion q) {
            return new Question(q.getId(), q.getSeq(), q.getQuestionType(), q.getTitle(),
                    q.isRequired(), q.getMinValue(), q.getMaxValue(),
                    q.activeOptions().stream()
                            .map(o -> new Option(o.getId(), o.getSeq(), o.getLabel()))
                            .toList());
        }
    }

    public record Option(Long id, short seq, String label) {
    }

    /** 제출 결과. 익명이든 아니든 "냈다"는 사실만 돌려준다. */
    public record Submitted(Long responseId, Instant submittedAt) {

        static Submitted from(com.dlab.domain.survey.entity.SurveyResponse r) {
            return new Submitted(r.getId(), r.getSubmittedAt());
        }
    }

    public record MyResponse(Long responseId, Instant submittedAt, List<Answer> answers) {

        static MyResponse from(com.dlab.domain.survey.entity.SurveyResponse r) {
            return new MyResponse(r.getId(), r.getSubmittedAt(),
                    r.activeAnswers().stream()
                            .map(a -> new Answer(
                                    a.getQuestion().getId(),
                                    a.getOption() != null ? a.getOption().getId() : null,
                                    a.getOption() != null ? a.getOption().getLabel() : null,
                                    a.getTextValue(), a.getNumberValue()))
                            .toList());
        }
    }

    public record Answer(Long questionId, Long optionId, String optionLabel,
                         String textValue, BigDecimal numberValue) {
    }
}
