package com.dlab.api.survey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.survey.entity.Survey;
import com.dlab.domain.survey.entity.SurveyAnswer;
import com.dlab.domain.survey.entity.SurveyQuestion;
import com.dlab.domain.survey.entity.SurveyQuestionType;
import com.dlab.domain.survey.entity.SurveyScope;
import com.dlab.domain.survey.entity.SurveyType;
import com.dlab.domain.survey.repository.SurveyResponseRepository;
import com.dlab.domain.survey.service.SurveyService;
import com.dlab.domain.survey.service.SurveyService.AnswerCommand;
import com.dlab.domain.survey.service.SurveyService.QuestionCommand;
import com.dlab.domain.survey.service.SurveyService.SurveyCommand;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가채점 설문 확장 (0921 성적 문서 11장).
 *
 * <p>지키려는 것 — <b>미응시면 그 과목을 묻지도 저장하지도 않을 것</b>, <b>총점은 서버가 더할 것</b>,
 * <b>임시저장은 검증 없이 되살아날 것</b>, <b>다시 내면 응답이 한 벌로 교체될 것</b>.
 */
@SpringBootTest
@Transactional
class GradeInputSurveyTest {

    @Autowired SurveyService surveyService;
    @Autowired SurveyResponseRepository responseRepository;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    StudentEnrollment student;
    AuthPrincipal branchAdmin;

    @BeforeEach
    void setUp() {
        short year = (short) java.time.LocalDate.now(clock).getYear();
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);
        Student s = new Student("DL-G1", "가채점학생", "010-0000-0000");
        em.persist(s);
        student = new StudentEnrollment(s, bundang, year, "2026-0001", null, GradeType.N_SU);
        em.persist(student);
        em.flush();
        branchAdmin = new AuthPrincipal(1L, "branch", bundang.getId(), Set.of(Role.BRANCH_ADMIN), false, false);
    }

    private Instant hours(int h) {
        return Instant.now(clock).plus(h, ChronoUnit.HOURS);
    }

    /**
     * 국어 한 과목짜리 가채점.
     * 1 응시 여부(응시/미응시) · 2 공통(0~76, 필수, 응시일 때만) · 3 선택(0~24, 필수, 응시일 때만)
     * · 4 총점(2+3, 0~100)
     */
    private Survey gradeInput(boolean anonymous, Boolean allowEdit) {
        Survey survey = surveyService.create(branchAdmin, new SurveyCommand(
                SurveyType.GRADE_INPUT, SurveyScope.BRANCH, bundang.getId(), null,
                "수능 가채점", null, anonymous, hours(-1), hours(1),
                List.of(
                        new QuestionCommand(SurveyQuestionType.SINGLE_CHOICE, "국어 응시", true,
                                null, null, List.of("응시", "미응시")),
                        new QuestionCommand(SurveyQuestionType.NUMBER, "국어 공통", true,
                                BigDecimal.ZERO, BigDecimal.valueOf(76), null, 1, 1, null),
                        new QuestionCommand(SurveyQuestionType.NUMBER, "국어 선택", true,
                                BigDecimal.ZERO, BigDecimal.valueOf(24), null, 1, 1, null),
                        new QuestionCommand(SurveyQuestionType.NUMBER, "국어 총점", false,
                                BigDecimal.ZERO, BigDecimal.valueOf(100), null, 1, 1,
                                List.of(2, 3))),
                allowEdit));
        em.flush();
        return survey;
    }

    private SurveyQuestion q(Survey survey, int index) {
        return survey.activeQuestions().get(index);
    }

    private Long option(Survey survey, int optionIndex) {
        return q(survey, 0).activeOptions().get(optionIndex).getId();
    }

    private List<AnswerCommand> took(Survey survey, int common, int elective, Integer clientTotal) {
        return List.of(
                new AnswerCommand(q(survey, 0).getId(), List.of(option(survey, 0)), null, null),
                new AnswerCommand(q(survey, 1).getId(), null, null, BigDecimal.valueOf(common)),
                new AnswerCommand(q(survey, 2).getId(), null, null, BigDecimal.valueOf(elective)),
                new AnswerCommand(q(survey, 3).getId(), null, null,
                        clientTotal == null ? null : BigDecimal.valueOf(clientTotal)));
    }

    private BigDecimal numberOf(Survey survey, int questionIndex) {
        Long qid = q(survey, questionIndex).getId();
        return responseRepository.findMine(survey.getId(), student.getId()).orElseThrow()
                .activeAnswers().stream()
                .filter(a -> a.getQuestion().getId().equals(qid))
                .map(SurveyAnswer::getNumberValue)
                .findFirst().orElse(null);
    }

    @Test
    @DisplayName("★ 총점은 서버가 더한다 — 앱이 다른 값을 보내도 합이 저장된다")
    void serverComputesTotal() {
        Survey survey = gradeInput(false, null);

        surveyService.submit(student.getId(), survey.getId(), took(survey, 60, 20, 99));
        em.flush();

        assertThat(numberOf(survey, 3)).isEqualByComparingTo("80");
    }

    @Test
    @DisplayName("★ 미응시면 그 과목은 필수여도 묻지 않고, 보낸 점수는 버린다")
    void untakenSubjectSkipped() {
        Survey survey = gradeInput(false, null);

        surveyService.submit(student.getId(), survey.getId(), List.of(
                new AnswerCommand(q(survey, 0).getId(), List.of(option(survey, 1)), null, null),
                // 미응시로 바꾸기 전에 적어 둔 점수가 같이 온다
                new AnswerCommand(q(survey, 1).getId(), null, null, BigDecimal.valueOf(50))));
        em.flush();

        assertThat(numberOf(survey, 1)).isNull();
        assertThat(numberOf(survey, 3)).isNull();
    }

    @Test
    @DisplayName("응시했는데 필수 점수가 빠지면 막힌다")
    void takenSubjectRequiresScores() {
        Survey survey = gradeInput(false, null);

        assertThatThrownBy(() -> surveyService.submit(student.getId(), survey.getId(), List.of(
                new AnswerCommand(q(survey, 0).getId(), List.of(option(survey, 0)), null, null))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("국어 공통");
    }

    @Test
    @DisplayName("★ 가채점은 기본으로 다시 낼 수 있다 — 응답은 한 벌로 교체된다")
    void resubmitReplacesAnswers() {
        Survey survey = gradeInput(false, null);
        assertThat(survey.isAllowEdit()).isTrue();

        surveyService.submit(student.getId(), survey.getId(), took(survey, 60, 20, null));
        em.flush();
        surveyService.submit(student.getId(), survey.getId(), took(survey, 70, 20, null));
        em.flush();

        assertThat(responseRepository.findBySurvey(survey.getId())).hasSize(1);
        assertThat(numberOf(survey, 1)).isEqualByComparingTo("70");
        assertThat(numberOf(survey, 3)).isEqualByComparingTo("90");
    }

    @Test
    @DisplayName("수정을 끈 설문은 두 번 낼 수 없다")
    void resubmitBlockedWhenNotAllowed() {
        Survey survey = gradeInput(false, false);
        surveyService.submit(student.getId(), survey.getId(), took(survey, 60, 20, null));
        em.flush();

        assertThatThrownBy(() -> surveyService.submit(student.getId(), survey.getId(),
                took(survey, 70, 20, null)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("익명 설문은 수정을 허용할 수 없다 — 고칠 응답을 찾을 수 없다")
    void anonymousCannotAllowEdit() {
        assertThatThrownBy(() -> gradeInput(true, true)).isInstanceOf(BusinessException.class);
        assertThat(gradeInput(true, null).isAllowEdit()).isFalse();
    }

    @Test
    @DisplayName("★ 임시저장은 검증 없이 저장되고 그대로 되살아난다 — 제출하면 지워진다")
    void draftRoundTrip() {
        Survey survey = gradeInput(false, null);
        // 필수가 빠진 중간 상태
        List<AnswerCommand> partial = List.of(
                new AnswerCommand(q(survey, 0).getId(), List.of(option(survey, 0)), null, null),
                new AnswerCommand(q(survey, 1).getId(), null, null, BigDecimal.valueOf(55)));

        surveyService.saveDraft(student.getId(), survey.getId(), partial);
        em.flush();
        var draft = surveyService.draft(student.getId(), survey.getId()).orElseThrow();
        assertThat(draft.answers()).hasSize(2);
        assertThat(draft.answers().get(1).numberValue()).isEqualByComparingTo("55");

        // 덮어쓴다
        surveyService.saveDraft(student.getId(), survey.getId(), took(survey, 60, 20, null));
        em.flush();
        assertThat(surveyService.draft(student.getId(), survey.getId()).orElseThrow().answers())
                .hasSize(4);

        surveyService.submit(student.getId(), survey.getId(), took(survey, 60, 20, null));
        em.flush();
        assertThat(surveyService.draft(student.getId(), survey.getId())).isEmpty();
    }

    @Test
    @DisplayName("익명 설문은 임시저장할 수 없다 — 응답자와 답이 한 행에 묶인다")
    void anonymousDraftRejected() {
        Survey survey = gradeInput(true, null);

        assertThatThrownBy(() -> surveyService.saveDraft(student.getId(), survey.getId(), List.of()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("조건은 앞쪽 단일 선택 문항이어야 한다")
    void conditionMustPointToEarlierSingleChoice() {
        assertThatThrownBy(() -> surveyService.create(branchAdmin, new SurveyCommand(
                SurveyType.GRADE_INPUT, SurveyScope.BRANCH, bundang.getId(), null,
                "잘못된 조건", null, false, hours(-1), hours(1),
                List.of(new QuestionCommand(SurveyQuestionType.NUMBER, "점수", true,
                                null, null, null, 2, 1, null),
                        new QuestionCommand(SurveyQuestionType.SINGLE_CHOICE, "응시", true,
                                null, null, List.of("응시", "미응시"))),
                null)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("합산 문항은 숫자 문항만 더한다")
    void sumPartsMustBeNumbers() {
        assertThatThrownBy(() -> surveyService.create(branchAdmin, new SurveyCommand(
                SurveyType.GRADE_INPUT, SurveyScope.BRANCH, bundang.getId(), null,
                "잘못된 합산", null, false, hours(-1), hours(1),
                List.of(new QuestionCommand(SurveyQuestionType.TEXT, "메모", false,
                                null, null, null),
                        new QuestionCommand(SurveyQuestionType.NUMBER, "총점", false,
                                null, null, null, null, null, List.of(1))),
                null)))
                .isInstanceOf(BusinessException.class);
    }
}
