package com.dlab.api.survey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.survey.entity.Survey;
import com.dlab.domain.survey.entity.SurveyParticipant;
import com.dlab.domain.survey.entity.SurveyQuestion;
import com.dlab.domain.survey.entity.SurveyQuestionType;
import com.dlab.domain.survey.entity.SurveyResponse;
import com.dlab.domain.survey.entity.SurveyScope;
import com.dlab.domain.survey.entity.SurveyType;
import com.dlab.domain.survey.repository.SurveyParticipantRepository;
import com.dlab.domain.survey.service.SurveyService;
import com.dlab.domain.survey.service.SurveyService.AnswerCommand;
import com.dlab.domain.survey.service.SurveyService.QuestionCommand;
import com.dlab.domain.survey.service.SurveyService.SurveyCommand;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.ClassAssignment;
import com.dlab.domain.user.entity.ClassMaster;
import com.dlab.domain.user.entity.ClassType;
import com.dlab.domain.user.entity.Employee;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.entity.Teacher;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 설문 (F-4.11-3 · A-14).
 *
 * <p>고정해두는 것은 넷이다 — <b>범위별 개설 권한</b>, <b>기간을 서버가 판정</b>,
 * <b>한 번만 제출</b>, <b>익명이면 응답에 응답자가 없다</b>.
 */
@SpringBootTest
@Transactional
class SurveyFlowTest {

    @Autowired SurveyService surveyService;
    @Autowired SurveyParticipantRepository participantRepository;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    Academy ilsan;
    ClassMaster class1;
    Teacher homeroom;
    StudentEnrollment minji;      // 분당 1반
    StudentEnrollment seojun;     // 분당 미배정

    AuthPrincipal headOffice;
    AuthPrincipal branchAdmin;
    AuthPrincipal homeroomTeacher;
    AuthPrincipal otherTeacher;

    short year;

    @BeforeEach
    void setUp() {
        year = (short) java.time.LocalDate.now(clock).getYear();

        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        ilsan = new Academy("32", "일산", LocalTime.of(9, 0));
        em.persist(bundang);
        em.persist(ilsan);

        homeroom = new Teacher(bundang, "박담임", "010-1111-1111");
        Teacher another = new Teacher(bundang, "최선생", "010-2222-2222");
        em.persist(homeroom);
        em.persist(another);

        Employee hq = new Employee(bundang, "본사행정");
        Employee staff = new Employee(bundang, "지점행정");
        em.persist(hq);
        em.persist(staff);

        class1 = new ClassMaster(bundang, year, "1반", ClassType.FIXED, homeroom);
        em.persist(class1);

        minji = enroll("김민지", "2026-0001", bundang);
        em.persist(new ClassAssignment(bundang, minji, class1, ClassType.FIXED));
        seojun = enroll("박서준", "2026-0002", bundang);
        em.flush();

        headOffice = principal(account(Account.forEmployee(hq, "hq", "x")),
                null, List.of(Role.SUPER_ADMIN), true);
        branchAdmin = principal(account(Account.forEmployee(staff, "staff", "x")),
                bundang.getId(), List.of(Role.BRANCH_ADMIN), false);
        homeroomTeacher = principal(account(Account.forTeacher(homeroom, "t1", "x")),
                bundang.getId(), List.of(Role.TEACHER), false);
        otherTeacher = principal(account(Account.forTeacher(another, "t2", "x")),
                bundang.getId(), List.of(Role.TEACHER), false);
    }

    // ── 픽스처 ───────────────────────────────────────────

    private StudentEnrollment enroll(String name, String stdNo, Academy academy) {
        Student student = new Student("DL-" + stdNo, name, "010-0000-0000");
        em.persist(student);
        StudentEnrollment e = new StudentEnrollment(
                student, academy, year, stdNo, null, GradeType.HIGH3);
        em.persist(e);
        return e;
    }

    private Account account(Account account) {
        em.persist(account);
        em.flush();
        return account;
    }

    private AuthPrincipal principal(Account account, Long academyId,
                                    List<Role> roles, boolean allAcademy) {
        return AuthPrincipal.of(account.getId(), account.getAccountType().name(),
                academyId, roles, allAcademy);
    }

    private Instant hoursFromNow(int hours) {
        return Instant.now(clock).plus(hours, ChronoUnit.HOURS);
    }

    /** 지금 열려 있는 지점 설문 하나. 문항은 단일선택 1 + 숫자 1이다. */
    private Survey openBranchSurvey(boolean anonymous) {
        Survey survey = surveyService.create(branchAdmin, new SurveyCommand(
                SurveyType.GENERAL, SurveyScope.BRANCH, bundang.getId(), null,
                "만족도 조사", "안내", anonymous,
                hoursFromNow(-1), hoursFromNow(1),
                List.of(
                        new QuestionCommand(SurveyQuestionType.SINGLE_CHOICE,
                                "급식은 어떤가요?", true, null, null,
                                List.of("좋다", "보통", "별로")),
                        new QuestionCommand(SurveyQuestionType.NUMBER,
                                "점수를 매긴다면?", false,
                                BigDecimal.ZERO, BigDecimal.valueOf(100), null))));
        em.flush();
        return survey;
    }

    private SurveyQuestion question(Survey survey, int index) {
        return survey.activeQuestions().get(index);
    }

    // ── 개설 권한 ────────────────────────────────────────

    @Test
    @DisplayName("★ 전 지점 설문은 본사만 — 지점관리자가 전 지점에 돌리면 안 된다")
    void onlyHeadOfficeCanCreateForAllBranches() {
        SurveyCommand command = new SurveyCommand(
                SurveyType.GENERAL, SurveyScope.ALL, null, null,
                "전체 설문", null, false, hoursFromNow(-1), hoursFromNow(1),
                List.of(new QuestionCommand(SurveyQuestionType.TEXT, "의견", true,
                        null, null, null)));

        assertThatCode(() -> surveyService.create(headOffice, command))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> surveyService.create(branchAdmin, command))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("본사");
    }

    @Test
    @DisplayName("★ 반 설문은 그 반 담임이 낸다 — 다른 선생님은 못 낸다")
    void classSurveyIsForHomeroomTeacher() {
        SurveyCommand command = new SurveyCommand(
                SurveyType.GENERAL, SurveyScope.CLASS, null, class1.getId(),
                "1반 설문", null, false, hoursFromNow(-1), hoursFromNow(1),
                List.of(new QuestionCommand(SurveyQuestionType.TEXT, "의견", true,
                        null, null, null)));

        assertThatCode(() -> surveyService.create(homeroomTeacher, command))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> surveyService.create(otherTeacher, command))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("문항이 없으면 개설되지 않는다 — 빈 설문이 앱에 뜨면 안 된다")
    void surveyWithoutQuestionIsRejected() {
        assertThatThrownBy(() -> surveyService.create(branchAdmin, new SurveyCommand(
                SurveyType.GENERAL, SurveyScope.BRANCH, bundang.getId(), null,
                "빈 설문", null, false, hoursFromNow(-1), hoursFromNow(1), List.of())))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("★ 선택형 문항에 선택지가 없으면 개설되지 않는다 — 고를 게 없는 문항이 된다")
    void choiceQuestionNeedsOptions() {
        assertThatThrownBy(() -> surveyService.create(branchAdmin, new SurveyCommand(
                SurveyType.GENERAL, SurveyScope.BRANCH, bundang.getId(), null,
                "설문", null, false, hoursFromNow(-1), hoursFromNow(1),
                List.of(new QuestionCommand(SurveyQuestionType.SINGLE_CHOICE,
                        "고르세요", true, null, null, List.of())))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("마감이 시작보다 앞서면 개설되지 않는다")
    void periodMustBeForward() {
        assertThatThrownBy(() -> surveyService.create(branchAdmin, new SurveyCommand(
                SurveyType.GENERAL, SurveyScope.BRANCH, bundang.getId(), null,
                "설문", null, false, hoursFromNow(1), hoursFromNow(-1),
                List.of(new QuestionCommand(SurveyQuestionType.TEXT, "의견", true,
                        null, null, null)))))
                .isInstanceOf(BusinessException.class);
    }

    // ── 앱 목록 ──────────────────────────────────────────

    @Test
    @DisplayName("★ 전 지점 + 내 지점 + 내 반이 한 목록으로 내려온다")
    void feedMergesEveryScope() {
        surveyService.create(headOffice, new SurveyCommand(
                SurveyType.GENERAL, SurveyScope.ALL, null, null, "전체", null, false,
                hoursFromNow(-1), hoursFromNow(1),
                List.of(new QuestionCommand(SurveyQuestionType.TEXT, "의견", true,
                        null, null, null))));
        openBranchSurvey(false);
        surveyService.create(homeroomTeacher, new SurveyCommand(
                SurveyType.GENERAL, SurveyScope.CLASS, null, class1.getId(), "1반", null, false,
                hoursFromNow(-1), hoursFromNow(1),
                List.of(new QuestionCommand(SurveyQuestionType.TEXT, "의견", true,
                        null, null, null))));
        em.flush();
        em.clear();

        assertThat(surveyService.feed(minji.getId()))
                .extracting(s -> s.survey().getTitle())
                .containsExactlyInAnyOrder("전체", "만족도 조사", "1반");
    }

    @Test
    @DisplayName("★ 다른 반 설문은 안 보인다 — 상세도 못 연다")
    void otherClassSurveyIsHidden() {
        Survey classSurvey = surveyService.create(homeroomTeacher, new SurveyCommand(
                SurveyType.GENERAL, SurveyScope.CLASS, null, class1.getId(), "1반", null, false,
                hoursFromNow(-1), hoursFromNow(1),
                List.of(new QuestionCommand(SurveyQuestionType.TEXT, "의견", true,
                        null, null, null))));
        em.flush();
        em.clear();

        assertThat(surveyService.feed(seojun.getId())).isEmpty();
        assertThatThrownBy(() -> surveyService.detail(seojun.getId(), classSurvey.getId()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("★ 시작 전 설문은 목록에 없다 — 앱이 시각을 비교하면 기기 시계에 좌우된다")
    void notYetOpenedSurveyIsHidden() {
        surveyService.create(branchAdmin, new SurveyCommand(
                SurveyType.GENERAL, SurveyScope.BRANCH, bundang.getId(), null,
                "예정", null, false, hoursFromNow(1), hoursFromNow(2),
                List.of(new QuestionCommand(SurveyQuestionType.TEXT, "의견", true,
                        null, null, null))));
        em.flush();
        em.clear();

        assertThat(surveyService.feed(minji.getId())).isEmpty();
    }

    @Test
    @DisplayName("★ 마감된 설문은 목록에 남되 open=false다 — 참여 여부를 확인할 수 있어야 한다")
    void closedSurveyRemainsButNotOpen() {
        surveyService.create(branchAdmin, new SurveyCommand(
                SurveyType.GENERAL, SurveyScope.BRANCH, bundang.getId(), null,
                "지난 설문", null, false, hoursFromNow(-3), hoursFromNow(-1),
                List.of(new QuestionCommand(SurveyQuestionType.TEXT, "의견", true,
                        null, null, null))));
        em.flush();
        em.clear();

        assertThat(surveyService.feed(minji.getId()))
                .singleElement()
                .satisfies(s -> assertThat(s.open()).isFalse());
    }

    // ── 제출 ────────────────────────────────────────────

    @Test
    @DisplayName("제출하면 응답과 참여 기록이 함께 남는다")
    void submitLeavesResponseAndParticipation() {
        Survey survey = openBranchSurvey(false);
        Long optionId = question(survey, 0).activeOptions().get(0).getId();

        SurveyResponse response = surveyService.submit(minji.getId(), survey.getId(), List.of(
                new AnswerCommand(question(survey, 0).getId(), List.of(optionId), null, null),
                new AnswerCommand(question(survey, 1).getId(), null, null,
                        BigDecimal.valueOf(90))));
        em.flush();

        assertThat(response.activeAnswers()).hasSize(2);
        assertThat(participantRepository.hasSubmitted(survey.getId(), minji.getId())).isTrue();
        assertThat(surveyService.feed(minji.getId()))
                .singleElement().satisfies(s -> assertThat(s.submitted()).isTrue());
    }

    @Test
    @DisplayName("★ 두 번 제출할 수 없다")
    void cannotSubmitTwice() {
        Survey survey = openBranchSurvey(false);
        Long optionId = question(survey, 0).activeOptions().get(0).getId();
        List<AnswerCommand> answers = List.of(
                new AnswerCommand(question(survey, 0).getId(), List.of(optionId), null, null));

        surveyService.submit(minji.getId(), survey.getId(), answers);
        em.flush();

        assertThatThrownBy(() -> surveyService.submit(minji.getId(), survey.getId(), answers))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미");
    }

    @Test
    @DisplayName("★ 마감 후에는 제출이 거절된다 — 목록을 열어둔 채 한참 뒤 내는 경로를 막는다")
    void submitAfterCloseIsRejected() {
        Survey survey = openBranchSurvey(false);
        Long optionId = question(survey, 0).activeOptions().get(0).getId();
        surveyService.closeNow(branchAdmin, survey.getId());
        em.flush();

        assertThatThrownBy(() -> surveyService.submit(minji.getId(), survey.getId(),
                List.of(new AnswerCommand(question(survey, 0).getId(),
                        List.of(optionId), null, null))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("기간");
    }

    @Test
    @DisplayName("★ 필수 문항을 빠뜨리면 거절되고, 어느 문항인지 알려준다")
    void requiredQuestionMustBeAnswered() {
        Survey survey = openBranchSurvey(false);

        assertThatThrownBy(() -> surveyService.submit(minji.getId(), survey.getId(),
                List.of(new AnswerCommand(question(survey, 1).getId(), null, null,
                        BigDecimal.valueOf(50)))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("급식은 어떤가요?");
    }

    @Test
    @DisplayName("선택 안 한 임의 문항은 통과한다 — 필수가 아니면 비워도 된다")
    void optionalQuestionCanBeSkipped() {
        Survey survey = openBranchSurvey(false);
        Long optionId = question(survey, 0).activeOptions().get(0).getId();

        assertThatCode(() -> surveyService.submit(minji.getId(), survey.getId(), List.of(
                new AnswerCommand(question(survey, 0).getId(), List.of(optionId), null, null),
                new AnswerCommand(question(survey, 1).getId(), null, null, null))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★ 단일 선택 문항에 여러 개를 고르면 거절된다")
    void singleChoiceRejectsMultiple() {
        Survey survey = openBranchSurvey(false);
        List<Long> two = question(survey, 0).activeOptions().stream()
                .limit(2).map(o -> o.getId()).toList();

        assertThatThrownBy(() -> surveyService.submit(minji.getId(), survey.getId(),
                List.of(new AnswerCommand(question(survey, 0).getId(), two, null, null))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("★ 다른 설문의 문항 id로는 답을 심을 수 없다")
    void foreignQuestionIsRejected() {
        Survey survey = openBranchSurvey(false);
        Survey other = surveyService.create(branchAdmin, new SurveyCommand(
                SurveyType.GENERAL, SurveyScope.BRANCH, bundang.getId(), null,
                "다른 설문", null, false, hoursFromNow(-1), hoursFromNow(1),
                List.of(new QuestionCommand(SurveyQuestionType.TEXT, "의견", true,
                        null, null, null))));
        em.flush();

        assertThatThrownBy(() -> surveyService.submit(minji.getId(), survey.getId(),
                List.of(new AnswerCommand(question(other, 0).getId(), null, "메모", null))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("★ 숫자 범위를 벗어나면 거절된다 — 세 자리 점수가 들어오면 평균이 망가진다")
    void numberOutOfRangeIsRejected() {
        Survey survey = openBranchSurvey(false);
        Long optionId = question(survey, 0).activeOptions().get(0).getId();

        assertThatThrownBy(() -> surveyService.submit(minji.getId(), survey.getId(), List.of(
                new AnswerCommand(question(survey, 0).getId(), List.of(optionId), null, null),
                new AnswerCommand(question(survey, 1).getId(), null, null,
                        BigDecimal.valueOf(150)))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("범위");
    }

    // ── 익명 ────────────────────────────────────────────

    @Test
    @DisplayName("★ 익명 설문은 응답에 응답자를 남기지 않는다 — 참여 사실만 따로 남는다")
    void anonymousSurveyKeepsNoRespondent() {
        Survey survey = openBranchSurvey(true);
        Long optionId = question(survey, 0).activeOptions().get(0).getId();

        SurveyResponse response = surveyService.submit(minji.getId(), survey.getId(),
                List.of(new AnswerCommand(question(survey, 0).getId(),
                        List.of(optionId), null, null)));
        em.flush();

        assertThat(response.getEnrollment()).isNull();
        // 그래도 중복 제출은 막히고, 누가 냈는지는 알 수 있다
        assertThat(participantRepository.hasSubmitted(survey.getId(), minji.getId())).isTrue();
        assertThat(surveyService.participants(branchAdmin, survey.getId()))
                .extracting(p -> p.getEnrollment().getId())
                .containsExactly(minji.getId());
    }

    @Test
    @DisplayName("★ 익명 설문은 내 응답을 다시 못 본다 — 되찾을 수 있으면 익명이 아니다")
    void anonymousResponseCannotBeReadBack() {
        Survey survey = openBranchSurvey(true);
        Long optionId = question(survey, 0).activeOptions().get(0).getId();
        surveyService.submit(minji.getId(), survey.getId(),
                List.of(new AnswerCommand(question(survey, 0).getId(),
                        List.of(optionId), null, null)));
        em.flush();

        assertThatThrownBy(() -> surveyService.myResponse(minji.getId(), survey.getId()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("실명 설문은 내 응답을 다시 볼 수 있다")
    void namedResponseCanBeReadBack() {
        Survey survey = openBranchSurvey(false);
        Long optionId = question(survey, 0).activeOptions().get(0).getId();
        surveyService.submit(minji.getId(), survey.getId(),
                List.of(new AnswerCommand(question(survey, 0).getId(),
                        List.of(optionId), null, null)));
        em.flush();
        em.clear();

        assertThat(surveyService.myResponse(minji.getId(), survey.getId()).activeAnswers())
                .hasSize(1);
    }

    // ── 집계 ────────────────────────────────────────────

    @Test
    @DisplayName("★ 아무도 안 고른 선택지도 0으로 나온다 — 빠지면 화면이 선택지를 못 그린다")
    void resultsIncludeUnpickedOptions() {
        Survey survey = openBranchSurvey(false);
        Long first = question(survey, 0).activeOptions().get(0).getId();
        surveyService.submit(minji.getId(), survey.getId(), List.of(
                new AnswerCommand(question(survey, 0).getId(), List.of(first), null, null),
                new AnswerCommand(question(survey, 1).getId(), null, null,
                        BigDecimal.valueOf(80))));
        em.flush();
        em.clear();

        var result = surveyService.results(branchAdmin, survey.getId());

        assertThat(result.responseCount()).isEqualTo(1);
        assertThat(result.questions().get(0).options())
                .extracting(o -> o.label(), o -> o.count())
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("좋다", 1L),
                        org.assertj.core.api.Assertions.tuple("보통", 0L),
                        org.assertj.core.api.Assertions.tuple("별로", 0L));
    }

    @Test
    @DisplayName("숫자 문항은 평균·최소·최대가 나온다")
    void numberQuestionIsAggregated() {
        Survey survey = openBranchSurvey(false);
        Long optionId = question(survey, 0).activeOptions().get(0).getId();
        surveyService.submit(minji.getId(), survey.getId(), List.of(
                new AnswerCommand(question(survey, 0).getId(), List.of(optionId), null, null),
                new AnswerCommand(question(survey, 1).getId(), null, null,
                        BigDecimal.valueOf(80))));
        surveyService.submit(seojun.getId(), survey.getId(), List.of(
                new AnswerCommand(question(survey, 0).getId(), List.of(optionId), null, null),
                new AnswerCommand(question(survey, 1).getId(), null, null,
                        BigDecimal.valueOf(60))));
        em.flush();
        em.clear();

        var number = surveyService.results(branchAdmin, survey.getId()).questions().get(1);

        assertThat(number.average()).isEqualByComparingTo("70.00");
        assertThat(number.min()).isEqualByComparingTo("60");
        assertThat(number.max()).isEqualByComparingTo("80");
    }

    @Test
    @DisplayName("★ 다른 지점 설문 결과는 못 본다")
    void otherAcademyResultIsDenied() {
        Survey survey = openBranchSurvey(false);
        em.flush();

        Employee ilsanStaff = new Employee(ilsan, "일산행정");
        em.persist(ilsanStaff);
        AuthPrincipal ilsanAdmin = principal(
                account(Account.forEmployee(ilsanStaff, "i1", "x")),
                ilsan.getId(), List.of(Role.BRANCH_ADMIN), false);

        assertThatThrownBy(() -> surveyService.results(ilsanAdmin, survey.getId()))
                .isInstanceOf(BusinessException.class);
    }

    // ── 관리 ────────────────────────────────────────────

    @Test
    @DisplayName("삭제는 soft — 그때 무슨 설문을 돌렸는지 추적이 끊기면 안 된다")
    void deleteIsSoft() {
        Survey survey = openBranchSurvey(false);
        surveyService.delete(branchAdmin, survey.getId());
        em.flush();
        em.clear();

        assertThat(surveyService.findForAdmin(branchAdmin, year)).isEmpty();
        assertThat(em.find(Survey.class, survey.getId())).isNotNull();
    }

    @Test
    @DisplayName("★ 수정에도 같은 권한을 다시 건다")
    void updateRechecksPermission() {
        Survey survey = surveyService.create(homeroomTeacher, new SurveyCommand(
                SurveyType.GENERAL, SurveyScope.CLASS, null, class1.getId(), "1반", null, false,
                hoursFromNow(-1), hoursFromNow(1),
                List.of(new QuestionCommand(SurveyQuestionType.TEXT, "의견", true,
                        null, null, null))));
        em.flush();

        assertThatThrownBy(() -> surveyService.update(otherTeacher, survey.getId(),
                "바꿈", null, hoursFromNow(-1), hoursFromNow(2)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("관리자 목록에는 전 지점 설문도 함께 나온다 — 조회는 공유다")
    void adminListIncludesHeadOfficeSurvey() {
        surveyService.create(headOffice, new SurveyCommand(
                SurveyType.GENERAL, SurveyScope.ALL, null, null, "전체", null, false,
                hoursFromNow(-1), hoursFromNow(1),
                List.of(new QuestionCommand(SurveyQuestionType.TEXT, "의견", true,
                        null, null, null))));
        openBranchSurvey(false);
        em.flush();
        em.clear();

        assertThat(surveyService.findForAdmin(branchAdmin, year))
                .extracting(Survey::getTitle)
                .containsExactlyInAnyOrder("전체", "만족도 조사");
    }

    @Test
    @DisplayName("가채점도 같은 구조를 쓴다 — 과목별 숫자 문항일 뿐이다")
    void gradeInputUsesSameStructure() {
        Survey survey = surveyService.create(branchAdmin, new SurveyCommand(
                SurveyType.GRADE_INPUT, SurveyScope.BRANCH, bundang.getId(), null,
                "9월 모의고사 가채점", null, false, hoursFromNow(-1), hoursFromNow(1),
                List.of(new QuestionCommand(SurveyQuestionType.NUMBER, "국어", true,
                                BigDecimal.ZERO, BigDecimal.valueOf(100), null),
                        new QuestionCommand(SurveyQuestionType.NUMBER, "수학", true,
                                BigDecimal.ZERO, BigDecimal.valueOf(100), null))));
        em.flush();

        surveyService.submit(minji.getId(), survey.getId(), List.of(
                new AnswerCommand(question(survey, 0).getId(), null, null,
                        BigDecimal.valueOf(88)),
                new AnswerCommand(question(survey, 1).getId(), null, null,
                        BigDecimal.valueOf(92))));
        em.flush();

        assertThat(survey.getSurveyType()).isEqualTo(SurveyType.GRADE_INPUT);
        assertThat(participantRepository.findBySurvey(survey.getId()))
                .extracting(SurveyParticipant::getEnrollment)
                .extracting(StudentEnrollment::getId)
                .containsExactly(minji.getId());
    }
}
