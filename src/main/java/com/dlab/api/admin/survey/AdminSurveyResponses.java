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

    /**
     * @param status 서버가 판정한 진행 상태 — {@code SCHEDULED}(아직 안 열림) ·
     *               {@code OPEN} · {@code CLOSED}. <b>화면이 시각을 비교하지 않는다</b>:
     *               클라이언트 시계가 어긋나면 같은 설문이 사람마다 다르게 보인다.
     *
     *               <p>조기 마감({@code PATCH /surveys/&#123;id&#125;/close})은
     *               {@code closesAt}을 지금으로 당기는 방식이라, 원래 마감 시각이
     *               <b>덮어써진다</b> — "예정 마감이 언제였나"는 남지 않는다.
     */
    public record AdminSurveySummary(Long id, SurveyType surveyType, SurveyScope scope,
                          Long academyId, Long classId,
                          String title, String description, boolean anonymous,
                          Instant opensAt, Instant closesAt, int questionCount,
                          String status, boolean allowEdit) {

        static AdminSurveySummary from(Survey s) {
            return from(s, Instant.now());
        }

        static AdminSurveySummary from(Survey s, Instant now) {
            return new AdminSurveySummary(s.getId(), s.getSurveyType(), s.getScope(),
                    s.getAcademy() != null ? s.getAcademy().getId() : null,
                    s.getClassMaster() != null ? s.getClassMaster().getId() : null,
                    s.getTitle(), s.getDescription(), s.isAnonymous(),
                    s.getOpensAt(), s.getClosesAt(), s.activeQuestions().size(),
                    statusOf(s, now), s.isAllowEdit());
        }

        private static String statusOf(Survey s, Instant now) {
            if (s.getOpensAt() != null && now.isBefore(s.getOpensAt())) {
                return "SCHEDULED";
            }
            return s.getClosesAt() != null && !now.isBefore(s.getClosesAt()) ? "CLOSED" : "OPEN";
        }
    }

    public record Result(AdminSurveySummary survey, int responseCount, List<QuestionResult> questions) {

        static Result from(SurveyService.SurveyResult r) {
            return new Result(AdminSurveySummary.from(r.survey()), r.responseCount(),
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
