package com.dlab.api.admin.survey;

import com.dlab.domain.survey.entity.Survey;
import com.dlab.domain.survey.entity.SurveyParticipant;
import com.dlab.domain.survey.entity.SurveyQuestionType;
import com.dlab.domain.survey.entity.SurveyScope;
import com.dlab.domain.survey.entity.SurveyType;
import com.dlab.domain.survey.service.SurveyService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 관리자 설문 응답 형식. */
public final class AdminSurveyResponses {

    private AdminSurveyResponses() {
    }

    public record Summary(Long id, SurveyType surveyType, SurveyScope scope,
                          Long academyId, Long classId,
                          String title, String description, boolean anonymous,
                          Instant opensAt, Instant closesAt, int questionCount) {

        static Summary from(Survey s) {
            return new Summary(s.getId(), s.getSurveyType(), s.getScope(),
                    s.getAcademy() != null ? s.getAcademy().getId() : null,
                    s.getClassMaster() != null ? s.getClassMaster().getId() : null,
                    s.getTitle(), s.getDescription(), s.isAnonymous(),
                    s.getOpensAt(), s.getClosesAt(), s.activeQuestions().size());
        }
    }

    public record Result(Summary survey, int responseCount, List<QuestionResult> questions) {

        static Result from(SurveyService.SurveyResult r) {
            return new Result(Summary.from(r.survey()), r.responseCount(),
                    r.questions().stream().map(QuestionResult::from).toList());
        }
    }

    /** 문항별 집계. 유형에 따라 채워지는 칸이 다르다. */
    public record QuestionResult(Long questionId, String title, SurveyQuestionType type,
                                 long answerCount, List<OptionCount> options,
                                 BigDecimal average, BigDecimal min, BigDecimal max,
                                 List<String> texts) {

        static QuestionResult from(SurveyService.QuestionResult q) {
            return new QuestionResult(q.questionId(), q.title(), q.type(), q.answerCount(),
                    q.options().stream()
                            .map(o -> new OptionCount(o.optionId(), o.label(), o.count()))
                            .toList(),
                    q.average(), q.min(), q.max(), q.texts());
        }
    }

    public record OptionCount(Long optionId, String label, long count) {
    }

    /**
     * 제출자.
     *
     * <p>익명 설문에서도 <b>제출 여부</b>는 알 수 있다 — 미제출자 독려에 필요하다.
     * 알 수 없는 건 "무엇을 냈는가"다.
     */
    public record Participant(Long enrollmentId, String studentNo, String studentName,
                              Instant submittedAt) {

        static Participant from(SurveyParticipant p) {
            return new Participant(p.getEnrollment().getId(),
                    p.getEnrollment().getStudentNo(),
                    p.getEnrollment().getStudent().getName(),
                    p.getSubmittedAt());
        }
    }
}
