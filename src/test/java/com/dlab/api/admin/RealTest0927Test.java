package com.dlab.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.grade.service.StudentGradeService;
import com.dlab.domain.lecture.entity.LectureStatus;
import com.dlab.domain.lecture.entity.LectureType;
import com.dlab.domain.lecture.service.LectureService;
import com.dlab.domain.statistics.service.StatisticsService;
import com.dlab.domain.user.entity.*;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
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
 * 0927 실데이터 테스트에서 나온 백엔드 결함 4건.
 *
 * <p>성적 404 · 담임 대시보드 403 · 특강 담당 강사 미검증 · 대시보드 고정 예시값.
 */
@SpringBootTest
@Transactional
class RealTest0927Test {

    @Autowired StudentGradeService gradeService;
    @Autowired StatisticsService statisticsService;
    @Autowired LectureService lectureService;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy daegu;
    short year;
    AuthPrincipal admin;
    AuthPrincipal teacher;
    Teacher me;
    ClassMaster myClass;
    StudentEnrollment mine;
    StudentEnrollment others;

    @BeforeEach
    void setUp() {
        year = (short) LocalDate.now(clock).getYear();
        daegu = new Academy("48", "대구", LocalTime.of(9, 0));
        em.persist(daegu);

        me = new Teacher(daegu, "테스트_담임", null);
        Teacher other = new Teacher(daegu, "다른_담임", null);
        em.persist(me);
        em.persist(other);

        myClass = new ClassMaster(daegu, year, "테스트_N수1반", ClassType.FIXED, me);
        ClassMaster otherClass = new ClassMaster(daegu, year, "2반", ClassType.FIXED, other);
        em.persist(myClass);
        em.persist(otherClass);

        mine = enroll("테스트_김대구", "2026-0001", myClass);
        others = enroll("남의반_학생", "2026-0002", otherClass);

        Account teacherAccount = Account.forTeacher(me, "tq_teacher_test", "x", false);
        em.persist(teacherAccount);
        em.flush();

        admin = AuthPrincipal.of(1L, "EMPLOYEE", daegu.getId(), List.of(Role.BRANCH_ADMIN), false);
        teacher = AuthPrincipal.of(teacherAccount.getId(), "TEACHER", daegu.getId(),
                List.of(Role.TEACHER), false);
    }

    private StudentEnrollment enroll(String name, String stdNo, ClassMaster clazz) {
        Student s = new Student("DL-" + stdNo, name, "010-0000-0000");
        em.persist(s);
        StudentEnrollment e = new StudentEnrollment(s, daegu, year, stdNo, null, GradeType.N_SU);
        e.recordAdmission(LocalDate.now(clock));
        em.persist(e);
        em.persist(new ClassAssignment(daegu, e, clazz, ClassType.FIXED));
        em.flush();
        return e;
    }

    // ── 긴급 2 · 3-4 ────────────────────────────────────────

    @Test
    @DisplayName("★ 성적을 낸 적 없는 학생도 404가 아니라 빈 성적으로 열린다")
    void emptyGradeIsNotAnError() {
        var submission = gradeService.of(mine);

        assertThat(submission).isNotNull();
        assertThat(submission.getSubmittedAt())
                .as("아직 낸 적이 없으므로 제출 시각이 없다")
                .isNull();
        assertThat(submission.activeScores()).isEmpty();
    }

    @Test
    @DisplayName("조회만으로 빈 제출이 저장되지는 않는다 — '아직 안 낸 학생' 집계가 어긋난다")
    void readingDoesNotCreateRow() {
        gradeService.of(mine);
        em.flush();
        em.clear();

        Long saved = em.createQuery("""
                SELECT COUNT(s) FROM StudentGradeSubmission s WHERE s.enrollment.id = :id
                """, Long.class).setParameter("id", mine.getId()).getSingleResult();
        assertThat(saved).isZero();
    }

    // ── 1-2 담임 대시보드 ───────────────────────────────────

    @Test
    @DisplayName("★ 담임도 대시보드를 본다 — 맡은 학생 기준이고 남의 반은 안 센다")
    void teacherSeesOwnScopeDashboard() {
        var forTeacher = statisticsService.overview(teacher, daegu.getId(), year,
                LocalDate.now(clock).minusDays(7), LocalDate.now(clock));

        assertThat(forTeacher.students().enrolled())
                .as("맡은 학생 1명만 세어야 한다")
                .isEqualTo(1);

        var forAdmin = statisticsService.overview(admin, daegu.getId(), year,
                LocalDate.now(clock).minusDays(7), LocalDate.now(clock));
        assertThat(forAdmin.students().enrolled()).isEqualTo(2);
    }

    @Test
    @DisplayName("담임에게 급식·수납은 비워서 준다 — 담임 업무가 아니다")
    void teacherDashboardHidesMoney() {
        var forTeacher = statisticsService.overview(teacher, daegu.getId(), year,
                LocalDate.now(clock).minusDays(7), LocalDate.now(clock));

        assertThat(forTeacher.meals()).isNull();
        assertThat(forTeacher.revenue()).isNull();
    }

    // ── 1-4 대시보드 카드 ───────────────────────────────────

    @Test
    @DisplayName("★ 대시보드 카드 2개가 실데이터다 — 예전에는 9건·7명이 상수로 박혀 있었다")
    void dashboardCardsAreReal() {
        var overview = statisticsService.overview(admin, daegu.getId(), year,
                LocalDate.now(clock).minusDays(7), LocalDate.now(clock));

        assertThat(overview.todo()).isNotNull();
        assertThat(overview.todo().pendingApprovals())
                .as("승인 신청이 하나도 없으면 0이어야 한다")
                .isZero();
        // 오늘 등원 태깅이 없으니 재원생 2명이 그대로 잡힌다
        assertThat(overview.todo().unexcusedAbsentToday()).isEqualTo(2);
    }

    // ── 2-3 특강 담당 강사 ──────────────────────────────────

    @Test
    @DisplayName("★ 담당 강사 없이 접수를 열 수 없다 — 담당 「미지정」 특강이 학생에게 열렸다")
    void cannotOpenLectureWithoutTeacher() {
        var lecture = lectureService.create(daegu.getId(), year, LectureType.LECTURE,
                "테스트_수학 특강", null, admin);
        em.flush();

        assertThatThrownBy(() ->
                lectureService.changeStatus(lecture.getId(), LectureStatus.OPEN, admin))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("담당 강사");
    }

    @Test
    @DisplayName("준비 중(DRAFT)으로 두는 것은 막지 않는다 — 만든 뒤 채워 가는 흐름이다")
    void draftIsAllowedWithoutTeacher() {
        var lecture = lectureService.create(daegu.getId(), year, LectureType.LECTURE,
                "준비 중 특강", null, admin);
        em.flush();

        assertThatCode(() ->
                lectureService.changeStatus(lecture.getId(), LectureStatus.DRAFT, admin))
                .doesNotThrowAnyException();
    }
}
