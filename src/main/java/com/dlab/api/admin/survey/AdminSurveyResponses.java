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

    @io.swagger.v3.oas.annotations.media.Schema(name = "SurveyResultResponse")
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
     * 문항 <b>정의</b> 한 줄 — 교체 요청({@code AdminSurveyQuestion})과 칸이 같다.
     *
     * <p>★ <b>번호는 「순서」(1부터)다</b> — DB 의 {@code seq} 가 아니다. 교체가
     * soft delete 라 {@code seq} 는 이어지는데 요청의 번호는 배열 순서라, {@code seq} 를
     * 그대로 쓰면 되돌려 보낼 때 엉뚱한 문항을 가리킨다.
     *
     * <p>⚠️ {@code minValue}·{@code maxValue} 는 <b>설정</b>이다. 집계
     * ({@code SurveyResultResponse})의 {@code min}·{@code max}(응답 통계)와 다르다.
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "SurveyQuestionSettingResponse")
    public record QuestionSetting(Long questionId, SurveyQuestionType type, String title,
                                  boolean required, BigDecimal minValue, BigDecimal maxValue,
                                  List<String> options,
                                  Integer showIfQuestionIndex, Integer showIfOptionIndex,
                                  List<Integer> sumOfIndexes) {

        static QuestionSetting from(SurveyService.QuestionSetting q) {
            return new QuestionSetting(q.questionId(), q.type(), q.title(), q.required(),
                    q.minValue(), q.maxValue(), q.options(),
                    q.showIfQuestionIndex(), q.showIfOptionIndex(), q.sumOfIndexes());
        }
    }

    /**
     * 제출자.
     *
     * <p>익명 설문에서도 <b>제출 여부</b>는 알 수 있다 — 미제출자 독려에 필요하다.
     * 알 수 없는 건 "무엇을 냈는가"다.
     */
    /**
     * 대상자 한 줄.
     *
     * <p>★ <b>이제 미제출 행도 나온다</b>(장학 동의서에서만). 범위로 돌리는 일반 설문은
     * 대상자를 미리 만들지 않으므로 <b>지금까지와 같이 제출자만</b> 나온다.
     *
     * <p>⚠️ <b>제출자 수를 세려면 {@code submittedAt != null} 로 세야 한다</b> —
     * 행 수로 세면 미제출 대상자까지 포함된다.
     *
     * @param state 「발송 / 열람 / 동의 / 제출」. 화면이 이 값으로 네 칸을 그린다
     */
    public record Participant(Long enrollmentId, String studentNo, String studentName,
                              SurveyParticipant.ConsentState state,
                              Instant viewedAt, Instant agreedAt, Instant submittedAt) {

        static Participant from(SurveyParticipant p) {
            return new Participant(p.getEnrollment().getId(),
                    p.getEnrollment().getStudentNo(),
                    p.getEnrollment().getStudent().getName(),
                    p.state(),
                    p.getViewedAt(), p.getAgreedAt(), p.getSubmittedAt());
        }
    }
}
