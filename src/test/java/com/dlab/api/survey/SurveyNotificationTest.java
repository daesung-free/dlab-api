package com.dlab.api.survey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.notification.entity.NotificationEvent;
import com.dlab.domain.survey.entity.Survey;
import com.dlab.domain.survey.entity.SurveyQuestionType;
import com.dlab.domain.survey.entity.SurveyScope;
import com.dlab.domain.survey.entity.SurveyType;
import com.dlab.domain.survey.service.SurveyService;
import com.dlab.domain.survey.service.SurveyService.QuestionCommand;
import com.dlab.domain.survey.service.SurveyService.SurveyCommand;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.EntityManager;
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

/**
 * 설문 알림 (F-4.6 "미응답자 독려").
 *
 * <h2>★★ 지키려는 것 둘</h2>
 * <ul>
 *   <li><b>대상자 행이 없는 설문에 「0건 발송 성공」을 돌려주지 않을 것.</b> 범위로 돌리는
 *       일반 설문은 참여 행을 만들지 않아 미응답자를 알 수 없다 — 그때 성공을 주면
 *       데스크는 독촉을 보냈다고 알고 끝내는데 <b>아무에게도 가지 않았다.</b></li>
 *   <li><b>같은 날 두 번 눌러도 두 번 가지 않을 것.</b> 문자는 건당 과금이다.</li>
 * </ul>
 *
 * <p>⚠️ 지금 템플릿은 {@code content_confirmed = FALSE} 다(디랩 문구 미수령). 그래서
 * 이력은 {@code SKIPPED} 로 남는다 — <b>그래도 이력 행은 남으므로</b> 중복 방지는 검증된다.
 */
/*
 * ⚠️⚠️ @Transactional 을 붙이지 않는다.
 *
 * 알림은 REQUIRES_NEW 로 떼어져 있다(본 행위를 되돌리지 않기 위해). 테스트를 한
 * 트랜잭션으로 감싸면 그 새 트랜잭션이 **바깥의 미커밋 데이터를 보지 못해** 계정 조회가
 * 전부 빈손이 되고, 기록된 알림도 테스트 쪽에서 보이지 않는다 — 즉 **운영에서 실제로
 * 도는 경로를 테스트가 통째로 가린다.** 교습비 일괄 청구 테스트와 같은 이유다.
 *
 * 그래서 커밋하며 돌리고 쓴 데이터를 직접 지운다. 정리는 시작할 때도 한다 —
 * 앞선 실행이 깨졌으면 남은 행이 다음 실행을 또 깨뜨린다.
 */
@SpringBootTest
class SurveyNotificationTest {

    @Autowired SurveyService surveyService;
    @Autowired EntityManager em;
    @Autowired Clock clock;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy academy;
    StudentEnrollment withApp;
    StudentEnrollment withoutApp;
    AuthPrincipal admin;

    @BeforeEach
    void setUp() {
        purge();
        tx.executeWithoutResult(status -> {
            short year = (short) java.time.LocalDate.now(clock).getYear();
            academy = new Academy("SN1", "설문알림테스트", LocalTime.of(9, 0));
            em.persist(academy);

            withApp = enrollment(year, "SN-0001", "앱학생", "2096-9001");
            // ★ 앱 계정이 있는 학생에게만 간다 — 계정 없는 학생은 경고만 남는다
            em.persist(Account.forStudent(withApp.getStudent(), "sn0001", "{noop}x"));
            withoutApp = enrollment(year, "SN-0002", "무앱학생", "2096-9002");
            });
        admin = AuthPrincipal.of(1L, "EMPLOYEE", null, List.of(Role.SUPER_ADMIN), true);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        purge();
    }

    /** 이 테스트가 쓴 것만 지운다. 시작할 때도 돌린다 — 위 주석 참고. */
    private void purge() {
        tx.executeWithoutResult(status -> {
            em.createNativeQuery("DELETE FROM notification_log WHERE event_code LIKE 'SURVEY%'")
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM survey_participant WHERE survey_id IN "
                    + "(SELECT id FROM survey WHERE academy_id IN "
                    + "(SELECT id FROM academy WHERE acad_cd = 'SN1'))").executeUpdate();
            em.createNativeQuery("DELETE FROM survey_question_option WHERE question_id IN "
                    + "(SELECT id FROM survey_question WHERE survey_id IN "
                    + "(SELECT id FROM survey WHERE academy_id IN "
                    + "(SELECT id FROM academy WHERE acad_cd = 'SN1')))").executeUpdate();
            em.createNativeQuery("DELETE FROM survey_question WHERE survey_id IN "
                    + "(SELECT id FROM survey WHERE academy_id IN "
                    + "(SELECT id FROM academy WHERE acad_cd = 'SN1'))").executeUpdate();
            em.createNativeQuery("DELETE FROM survey WHERE academy_id IN "
                    + "(SELECT id FROM academy WHERE acad_cd = 'SN1')").executeUpdate();
            em.createNativeQuery("DELETE FROM account WHERE login_id = 'sn0001'").executeUpdate();
            em.createNativeQuery("DELETE FROM student_enrollment WHERE student_no LIKE '2096-90%'")
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM student WHERE unique_code LIKE 'SN-%'")
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM academy WHERE acad_cd = 'SN1'").executeUpdate();
        });
    }

    private StudentEnrollment enrollment(short year, String code, String name, String no) {
        Student s = new Student(code, name, "010-0000-0000");
        em.persist(s);
        StudentEnrollment e = new StudentEnrollment(s, academy, year, no, null, GradeType.N_SU);
        em.persist(e);
        return e;
    }

    private Survey survey() {
        Instant now = clock.instant();
        return surveyService.create(admin, new SurveyCommand(
                SurveyType.SCHOLARSHIP_CONSENT, SurveyScope.BRANCH, academy.getId(), null,
                "장학 동의서", null, false, now, now.plus(7, ChronoUnit.DAYS),
                List.of(new QuestionCommand(SurveyQuestionType.SINGLE_CHOICE, "동의하십니까",
                        true, null, null, List.of("동의", "미동의"))),
                null));
    }

    private long logCount(NotificationEvent event) {
        return em.createQuery("SELECT COUNT(l) FROM NotificationLog l WHERE l.eventCode = :e",
                        Long.class)
                .setParameter("e", event).getSingleResult();
    }

    @Test
    @DisplayName("★★ 대상자를 지정하지 않은 설문에는 독촉을 보낼 수 없다 — 0건 성공을 돌려주면 아무도 모른다")
    void cannotRemindSurveyWithoutTargets() {
        Survey s = survey();

        assertThatThrownBy(() -> surveyService.remindPending(admin, s.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("미응답자를 알 수 없습니다");

        var preview = surveyService.remindPreview(admin, s.getId());
        assertThat(preview.remindable()).isFalse();
        assertThat(preview.targetCount()).isZero();
    }

    @Test
    @DisplayName("★ 대상자 지정 시 첫 발송이 나간다 — 이미 대상이던 학생에게 또 보내지 않는다")
    void assignSendsOnceEvenIfCalledTwice() {
        Survey s = survey();

        surveyService.assignTargets(admin, s.getId(), List.of(withApp.getId()));
        long after1 = logCount(NotificationEvent.SURVEY_ASSIGNED);

        // 두 번 누르는 일이 실제로 있다 — 같은 설문 안내를 두 번 받으면 안 된다
        surveyService.assignTargets(admin, s.getId(), List.of(withApp.getId()));

        assertThat(after1).isEqualTo(1);
        assertThat(logCount(NotificationEvent.SURVEY_ASSIGNED)).isEqualTo(after1);
    }

    @Test
    @DisplayName("★★ 같은 날 독촉을 두 번 눌러도 한 번만 나간다 — 문자는 건당 과금이다")
    void remindIsDedupedWithinTheDay() {
        Survey s = survey();
        surveyService.assignTargets(admin, s.getId(), List.of(withApp.getId()));

        int first = surveyService.remindPending(admin, s.getId());
        int second = surveyService.remindPending(admin, s.getId());

        // 시도는 두 번이지만 이력은 하나다 — dedupKey 에 날짜가 들어간다
        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(1);
        assertThat(logCount(NotificationEvent.SURVEY_REMINDER)).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 집계가 제출/미제출을 가른다 — 모달이 이 숫자로 「○○명에게 보냅니다」를 띄운다")
    void previewCountsPendingOnly() {
        Survey s = survey();
        surveyService.assignTargets(admin, s.getId(),
                List.of(withApp.getId(), withoutApp.getId()));

        var before = surveyService.remindPreview(admin, s.getId());
        assertThat(before.targetCount()).isEqualTo(2);
        assertThat(before.pendingCount()).isEqualTo(2);
        assertThat(before.remindable()).isTrue();
    }

    @Test
    @DisplayName("앱 계정이 없는 학생은 발송 대상에서 빠진다 — 거절이 아니라 건너뜀이다")
    void studentWithoutAppAccountIsSkippedNotRejected() {
        Survey s = survey();
        surveyService.assignTargets(admin, s.getId(), List.of(withoutApp.getId()));

        // 대상자 지정 자체는 성공한다 — 알림이 본 행위를 되돌리면 안 된다
        assertThat(surveyService.remindPreview(admin, s.getId()).targetCount()).isEqualTo(1);
        // 계정이 없어 이력이 남지 않는다
        assertThat(logCount(NotificationEvent.SURVEY_ASSIGNED)).isZero();
    }
}
