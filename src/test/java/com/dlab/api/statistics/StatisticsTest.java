package com.dlab.api.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.attendance.entity.AttendanceDailyStatus;
import com.dlab.domain.attendance.entity.DailyStatus;
import com.dlab.domain.statistics.service.StatisticsService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.EntityManager;
import java.time.Instant;
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
 * 통계·관리자 대시보드 (F-4.11-11).
 *
 * <p><b>지점 스코프가 서버에서 강제되는지</b>가 핵심이다 — 요청 파라미터를 그대로 믿으면
 * 지점 관리자가 값만 바꿔 다른 지점 통계를 본다.
 */
@SpringBootTest
@Transactional
class StatisticsTest {

    @Autowired StatisticsService statisticsService;
    @Autowired EntityManager em;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    Academy ilsan;
    StudentEnrollment minji;    // 분당
    StudentEnrollment seojun;   // 분당
    StudentEnrollment jiwoo;    // 일산
    LocalDate from;
    LocalDate to;

    @BeforeEach
    void setUp() {
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        ilsan = new Academy("32", "일산", LocalTime.of(9, 0));
        em.persist(bundang);
        em.persist(ilsan);

        minji = enroll(bundang, "20260001", "김민지");
        seojun = enroll(bundang, "20260002", "이서준");
        jiwoo = enroll(ilsan, "20260101", "박지우");

        from = LocalDate.of(2026, 8, 1);
        to = LocalDate.of(2026, 8, 5);

        daily(minji, from, DailyStatus.PRESENT, 480);
        daily(minji, from.plusDays(1), DailyStatus.LATE, 400);
        daily(seojun, from, DailyStatus.ABSENT, null);
        daily(jiwoo, from, DailyStatus.PRESENT, 600);
        em.flush();
    }

    private StudentEnrollment enroll(Academy academy, String studentNo, String name) {
        Student student = new Student("DL-" + studentNo, name, "010-0000-0000");
        em.persist(student);
        StudentEnrollment enrollment = new StudentEnrollment(
                student, academy, (short) 2026, studentNo, null, GradeType.HIGH3);
        em.persist(enrollment);
        return enrollment;
    }

    private void daily(StudentEnrollment enrollment, LocalDate date,
                       DailyStatus status, Integer studyMinutes) {
        AttendanceDailyStatus row = new AttendanceDailyStatus(
                enrollment.getAcademy(), enrollment, date, status);
        if (studyMinutes != null) {
            row.recordStudyMinutes(studyMinutes, Instant.now());
        }
        em.persist(row);
    }

    private AuthPrincipal superAdmin() {
        return AuthPrincipal.of(1L, "EMPLOYEE", bundang.getId(),
                List.of(Role.SUPER_ADMIN), true);
    }

    private AuthPrincipal branchAdmin(Academy academy) {
        return AuthPrincipal.of(2L, "EMPLOYEE", academy.getId(),
                List.of(Role.BRANCH_ADMIN), false);
    }

    @Test
    @DisplayName("★★ 지점 관리자는 academyId를 남의 지점으로 보내도 막힌다")
    void branchAdminCannotPeekOtherBranch() {
        assertThatThrownBy(() -> statisticsService.overview(
                branchAdmin(bundang), ilsan.getId(), (short) 2026, from, to))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("★ 지점 관리자가 academyId를 비워도 전 지점이 되지 않는다 — 자기 지점으로 고정")
    void branchAdminScopeIsForced() {
        var result = statisticsService.overview(
                branchAdmin(bundang), null, (short) 2026, from, to);

        assertThat(result.students().enrolled()).isEqualTo(2);   // 일산 지우 제외
    }

    @Test
    @DisplayName("전 지점 권한자가 비우면 전 지점을 합산한다")
    void superAdminSeesAllBranches() {
        var result = statisticsService.overview(
                superAdmin(), null, (short) 2026, from, to);

        assertThat(result.students().enrolled()).isEqualTo(3);
    }

    @Test
    @DisplayName("★ 지각은 출석으로 센다 — 3일 확정 중 결석 1일이면 67%")
    void lateCountsAsPresent() {
        var result = statisticsService.overview(
                branchAdmin(bundang), null, (short) 2026, from, to);

        assertThat(result.attendance().confirmedDays()).isEqualTo(3);
        assertThat(result.attendance().attendanceRate()).isEqualTo(67);
    }

    @Test
    @DisplayName("★ 확정된 날이 없으면 출석률은 0%가 아니라 null — 아무 일도 없었는데 결석처럼 보인다")
    void noConfirmedDayIsNull() {
        var result = statisticsService.overview(branchAdmin(bundang), null, (short) 2026,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 5));

        assertThat(result.attendance().attendanceRate()).isNull();
    }

    @Test
    @DisplayName("★ 순공 없는 날은 평균 분모에서 빠진다 — 결석일을 0으로 세면 평균이 반토막 난다")
    void studyTimeAverageIgnoresNullDays() {
        var result = statisticsService.overview(
                branchAdmin(bundang), null, (short) 2026, from, to);

        assertThat(result.studyTime().totalMinutes()).isEqualTo(880);
        assertThat(result.studyTime().countedDays()).isEqualTo(2);
        assertThat(result.studyTime().avgMinutesPerDay()).isEqualTo(440);
    }

    @Test
    @DisplayName("순공 랭킹은 높은 순이고 지점 스코프를 탄다")
    void rankingIsScoped() {
        var ranking = statisticsService.studyTimeRanking(
                branchAdmin(bundang), null, from, to, 10);

        assertThat(ranking).hasSize(1);      // 서준은 순공이 없다
        assertThat(ranking.get(0).studentName()).isEqualTo("김민지");
        assertThat(ranking.get(0).studyMinutes()).isEqualTo(880);
    }
}
