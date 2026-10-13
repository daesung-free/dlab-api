package com.dlab.api.survey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.survey.entity.Survey;
import com.dlab.domain.survey.entity.SurveyParticipant;
import com.dlab.domain.survey.entity.SurveyParticipant.ConsentState;
import com.dlab.domain.survey.entity.SurveyQuestionType;
import com.dlab.domain.survey.entity.SurveyScope;
import com.dlab.domain.survey.entity.SurveyType;
import com.dlab.domain.survey.repository.SurveyParticipantRepository;
import com.dlab.domain.survey.service.SurveyService;
import com.dlab.domain.survey.service.SurveyService.QuestionCommand;
import com.dlab.domain.survey.service.SurveyService.SurveyCommand;
import com.dlab.domain.user.entity.*;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 장학 동의서 (2026-10-13 디랩 회신 5-1 · 5-2-1).
 *
 * <p>디랩 요구가 <b>「구글 설문처럼 양식을 만들고 고치고, 복사하고, 발송·열람·동의·제출을
 * 추적하고, 내려받는 기능」</b>이라 설문 구조를 그대로 쓴다.
 *
 * <p>지키려는 것 넷이다.
 * <ol>
 *   <li><b>★★ 제출이 있으면 문항을 고칠 수 없다</b> — 이미 동의한 내용이 소급해서 바뀐다</li>
 *   <li><b>★ 대상자 행이 있어도 「제출했다」가 아니다</b> — 기존 판정이 행 존재만 봤다</li>
 *   <li><b>4단계를 가린다</b> — 발송 / 열람 / 동의 / 제출</li>
 *   <li><b>복제는 양식만</b> — 대상자·응답을 복사하면 남의 동의가 새 양식에 붙는다</li>
 * </ol>
 */
@SpringBootTest
@Transactional
class ScholarshipConsentTest {

    @Autowired SurveyService surveyService;
    @Autowired SurveyParticipantRepository participantRepository;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy academy;
    StudentEnrollment minji;
    StudentEnrollment seojun;
    AuthPrincipal admin;
    short year;

    @BeforeEach
    void setUp() {
        year = (short) java.time.LocalDate.now(clock).getYear();

        academy = new Academy("SC01", "장학동의지점", LocalTime.of(9, 0));
        em.persist(academy);

        Employee staff = new Employee(academy, "장학담당");
        em.persist(staff);
        Account account = Account.forEmployee(staff, "sc-staff", "x", false);
        em.persist(account);

        minji = enroll("김민지", "0001");
        seojun = enroll("박서준", "0002");
        em.flush();

        admin = AuthPrincipal.of(account.getId(), "EMPLOYEE", academy.getId(),
                List.of(Role.BRANCH_ADMIN), false);
    }

    private StudentEnrollment enroll(String name, String studentNo) {
        Student person = new Student("SC-" + studentNo, name, "010-9000-" + studentNo);
        em.persist(person);
        StudentEnrollment e = new StudentEnrollment(person, academy, year, studentNo,
                "RF-SC" + studentNo, GradeType.N_SU);
        em.persist(e);
        return e;
    }

    /** 장학 동의서 한 벌. 서명은 체크만이라 단일 선택으로 충분하다 */
    private Survey consentForm(String title) {
        Survey survey = surveyService.create(admin, new SurveyCommand(
                SurveyType.SCHOLARSHIP_CONSENT, SurveyScope.BRANCH, academy.getId(), null,
                title, "장학 혜택 수혜 동의서", false,
                Instant.now(clock).minusSeconds(3600), Instant.now(clock).plusSeconds(3600),
                List.of(new QuestionCommand(SurveyQuestionType.SINGLE_CHOICE,
                        "위 내용에 동의합니다", true, null, null, List.of("동의", "비동의")))));
        em.flush();
        return survey;
    }

    @Test
    @DisplayName("★ 대상자를 발송하면 「발송」 상태로 쌓인다 — 제출은 아니다")
    void assignsTargetsAsSent() {
        Survey form = consentForm("수능 성적 우수 장학 동의서");

        int sent = surveyService.assignTargets(admin, form.getId(),
                List.of(minji.getId(), seojun.getId()));
        em.flush();

        assertThat(sent).isEqualTo(2);
        var targets = surveyService.participants(admin, form.getId());
        assertThat(targets).hasSize(2);
        assertThat(targets).allMatch(p -> p.state() == ConsentState.SENT);
        assertThat(targets).allMatch(p -> p.getSubmittedAt() == null);
    }

    @Test
    @DisplayName("★★ 대상자로 등록만 됐는데 「이미 냈다」로 판정되면 제출이 막힌다")
    void targetIsNotASubmission() {
        Survey form = consentForm("환급반 장학 동의서");
        surveyService.assignTargets(admin, form.getId(), List.of(minji.getId()));
        em.flush();

        // 행은 있지만 제출은 아니다 — 행 존재만 보면 발송받은 학생이 제출을 못 한다
        assertThat(participantRepository.isTarget(form.getId(), minji.getId())).isTrue();
        assertThat(participantRepository.hasSubmitted(form.getId(), minji.getId())).isFalse();
        assertThat(participantRepository.countSubmitted(form.getId())).isZero();
    }

    @Test
    @DisplayName("★ 두 번 발송해도 행이 늘지 않는다 — 중복 행은 미제출자 집계부터 틀어진다")
    void assigningTwiceDoesNotDuplicate() {
        Survey form = consentForm("성적 우수 장학 동의서");
        surveyService.assignTargets(admin, form.getId(), List.of(minji.getId()));
        em.flush();

        int again = surveyService.assignTargets(admin, form.getId(),
                List.of(minji.getId(), seojun.getId()));
        em.flush();

        // 새로 보낸 것만 센다
        assertThat(again).isEqualTo(1);
        assertThat(surveyService.participants(admin, form.getId())).hasSize(2);
    }

    @Test
    @DisplayName("★ 발송 → 열람 → 동의 순으로 상태가 올라간다")
    void tracksFourStates() {
        Survey form = consentForm("장학 동의서");
        surveyService.assignTargets(admin, form.getId(), List.of(minji.getId()));
        em.flush();

        surveyService.markViewed(form.getId(), minji.getId());
        em.flush();
        assertThat(state(form, minji)).isEqualTo(ConsentState.VIEWED);

        surveyService.markAgreed(form.getId(), minji.getId());
        em.flush();
        // ★ 동의와 제출은 다른 축이다 — 동의만 하고 제출을 안 누를 수 있다
        assertThat(state(form, minji)).isEqualTo(ConsentState.AGREED);
    }

    @Test
    @DisplayName("열람은 처음 한 번만 찍는다 — 다시 열어도 첫 열람 시각이 남는다")
    void viewedAtIsFirstOnly() {
        Survey form = consentForm("장학 동의서");
        surveyService.assignTargets(admin, form.getId(), List.of(minji.getId()));
        em.flush();

        surveyService.markViewed(form.getId(), minji.getId());
        em.flush();
        Instant first = participant(form, minji).getViewedAt();

        surveyService.markViewed(form.getId(), minji.getId());
        em.flush();

        assertThat(participant(form, minji).getViewedAt()).isEqualTo(first);
    }

    @Test
    @DisplayName("★★ 제출이 있으면 문항을 고칠 수 없다 — 이미 동의한 내용이 소급해서 바뀐다")
    void cannotEditQuestionsAfterSubmission() {
        Survey form = consentForm("장학 동의서");
        // 제출 한 건을 만든다
        em.persist(new SurveyParticipant(form, minji, Instant.now(clock)));
        em.flush();

        assertThatThrownBy(() -> surveyService.replaceQuestions(admin, form.getId(),
                List.of(new QuestionCommand(SurveyQuestionType.TEXT, "바뀐 문항", true,
                        null, null, null))))
                .hasMessageContaining("복제해서 새로 만들어");
    }

    @Test
    @DisplayName("★ 제출이 없으면 문항을 고칠 수 있다 — 막아야 하는 것은 제출 뒤의 수정뿐이다")
    void canEditQuestionsBeforeSubmission() {
        Survey form = consentForm("장학 동의서");
        // 대상자만 있다(미제출). 이것으로는 막히지 않아야 한다
        surveyService.assignTargets(admin, form.getId(), List.of(minji.getId()));
        em.flush();

        surveyService.replaceQuestions(admin, form.getId(), List.of(
                new QuestionCommand(SurveyQuestionType.SINGLE_CHOICE, "고친 동의 문항",
                        true, null, null, List.of("동의", "비동의"))));
        em.flush();
        em.clear();

        var reloaded = surveyService.participants(admin, form.getId());
        assertThat(reloaded).hasSize(1);
        assertThat(em.find(Survey.class, form.getId()).activeQuestions())
                .extracting(com.dlab.domain.survey.entity.SurveyQuestion::getTitle)
                .containsExactly("고친 동의 문항");
    }

    @Test
    @DisplayName("★★ 복제는 양식만 옮긴다 — 대상자·응답을 복사하면 남의 동의가 새 양식에 붙는다")
    void duplicateCopiesFormOnly() {
        Survey form = consentForm("정규반 장학 동의서");
        surveyService.assignTargets(admin, form.getId(), List.of(minji.getId()));
        em.persist(new SurveyParticipant(form, seojun, Instant.now(clock)));
        em.flush();

        Survey copy = surveyService.duplicate(admin, form.getId(), "환급반 장학 동의서");
        em.flush();

        assertThat(copy.getId()).isNotEqualTo(form.getId());
        assertThat(copy.getSurveyType()).isEqualTo(SurveyType.SCHOLARSHIP_CONSENT);
        // 문항·선택지는 따라온다
        assertThat(copy.activeQuestions()).hasSize(1);
        assertThat(copy.activeQuestions().get(0).activeOptions())
                .extracting(com.dlab.domain.survey.entity.SurveyQuestionOption::getLabel)
                .containsExactly("동의", "비동의");
        // ★ 대상자·응답은 따라오지 않는다
        assertThat(surveyService.participants(admin, copy.getId())).isEmpty();
    }

    @Test
    @DisplayName("복제 제목을 비우면 거절한다 — 같은 제목이 둘이면 어느 것을 보냈는지 모른다")
    void duplicateRequiresTitle() {
        Survey form = consentForm("장학 동의서");

        assertThatThrownBy(() -> surveyService.duplicate(admin, form.getId(), "  "))
                .hasMessageContaining("제목");
    }

    private ConsentState state(Survey form, StudentEnrollment enrollment) {
        return participant(form, enrollment).state();
    }

    private SurveyParticipant participant(Survey form, StudentEnrollment enrollment) {
        return participantRepository.findMine(form.getId(), enrollment.getId()).orElseThrow();
    }
}
