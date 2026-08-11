package com.dlab.api.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.domain.attendance.entity.AttendanceDailyStatus;
import com.dlab.domain.attendance.entity.DailyStatus;
import com.dlab.domain.report.entity.RankingPeriod;
import com.dlab.domain.report.service.DailyReportService;
import com.dlab.domain.report.service.StudyTimeRankingService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily Report 집계 (F-4.11-6, 앱 A-3).
 *
 * <p>진도·온라인 질의응답·FCM 요약 푸시는 범위 밖이다(각각 I-23 보류 · F-4.11-7 미구현 ·
 * E-7 대기).
 */
@SpringBootTest
@Transactional
class DailyReportTest {

    @Autowired DailyReportService reportService;
    @Autowired StudyTimeRankingService rankingService;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    Academy ilsan;
    StudentEnrollment minji;    // 분당 · 880분
    StudentEnrollment seojun;   // 분당 · 880분 (민지와 동점)
    StudentEnrollment doyun;    // 분당 · 300분
    StudentEnrollment jiwoo;    // 일산 · 1000분
    LocalDate day1;

    @BeforeEach
    void setUp() {
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        ilsan = new Academy("32", "일산", LocalTime.of(9, 0));
        em.persist(bundang);
        em.persist(ilsan);

        minji = enroll(bundang, "20260001", "김민지");
        seojun = enroll(bundang, "20260002", "이서준");
        doyun = enroll(bundang, "20260003", "최도윤");
        jiwoo = enroll(ilsan, "20260101", "박지우");

        day1 = LocalDate.of(2026, 8, 3);        // 월요일

        daily(minji, day1, DailyStatus.PRESENT, 480);
        daily(minji, day1.plusDays(1), DailyStatus.LATE, 400);
        daily(seojun, day1, DailyStatus.PRESENT, 880);
        daily(doyun, day1, DailyStatus.PRESENT, 300);
        daily(doyun, day1.plusDays(1), DailyStatus.ABSENT, null);
        daily(jiwoo, day1, DailyStatus.PRESENT, 1000);
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

    // ── 랭킹 ──────────────────────────────────────────────────

    @Test
    @DisplayName("★ 전체 1등과 지점 1등이 다르다 — 지점 랭킹을 따로 매기지 않으면 둘 다 못 보여준다")
    void overallAndAcademyTopDiffer() {
        rankingService.rebuild(RankingPeriod.WEEKLY, day1);
        em.flush();
        em.clear();

        var view = rankingService.view(reload(minji), RankingPeriod.WEEKLY, day1);

        assertThat(view.overallTop().studentName()).isEqualTo("박지우");   // 일산 1000분
        assertThat(view.academyTop().studyMinutes()).isEqualTo(880);       // 분당 1등
    }

    @Test
    @DisplayName("★★ 동점은 같은 등수다 — 임의로 순서를 매기면 같은 시간을 공부한 둘에게 다른 등수가 보인다")
    void tiedStudentsShareRank() {
        rankingService.rebuild(RankingPeriod.WEEKLY, day1);
        em.flush();
        em.clear();

        var minjiView = rankingService.view(reload(minji), RankingPeriod.WEEKLY, day1);
        var seojunView = rankingService.view(reload(seojun), RankingPeriod.WEEKLY, day1);

        assertThat(minjiView.myAcademy().ranking()).isEqualTo(1);
        assertThat(seojunView.myAcademy().ranking()).isEqualTo(1);

        // 동점 다음은 2등이 아니라 3등이다
        var doyunView = rankingService.view(reload(doyun), RankingPeriod.WEEKLY, day1);
        assertThat(doyunView.myAcademy().ranking()).isEqualTo(3);
    }

    @Test
    @DisplayName("★ 다시 돌려도 등수가 중복되지 않는다 — 출결이 뒤늦게 정정되면 실제로 다시 돌린다")
    void rebuildIsIdempotent() {
        rankingService.rebuild(RankingPeriod.WEEKLY, day1);
        rankingService.rebuild(RankingPeriod.WEEKLY, day1);
        em.flush();
        em.clear();

        var view = rankingService.view(reload(minji), RankingPeriod.WEEKLY, day1);

        assertThat(view.academySize()).isEqualTo(3);    // 분당 3명. 두 배로 늘지 않는다
    }

    @Test
    @DisplayName("★ 순공 기록이 없는 학생은 랭킹에 없다 — 0분으로 채우면 꼬리가 재원생 명단이 된다")
    void studentWithoutStudyTimeIsAbsentFromRanking() {
        StudentEnrollment newbie = enroll(bundang, "20260004", "정하늘");
        em.flush();

        rankingService.rebuild(RankingPeriod.WEEKLY, day1);
        em.flush();
        em.clear();

        var view = rankingService.view(reload(newbie), RankingPeriod.WEEKLY, day1);

        assertThat(view.myAcademy()).isNull();
        assertThat(view.academySize()).isEqualTo(3);
    }

    @Test
    @DisplayName("주간은 그 주 전체를 합산한다 — 일간은 그날만")
    void weeklySumsTheWholeWeek() {
        rankingService.rebuild(RankingPeriod.WEEKLY, day1);
        rankingService.rebuild(RankingPeriod.DAILY, day1);
        em.flush();
        em.clear();

        assertThat(rankingService.view(reload(minji), RankingPeriod.WEEKLY, day1)
                .myAcademy().studyMinutes()).isEqualTo(880);
        assertThat(rankingService.view(reload(minji), RankingPeriod.DAILY, day1)
                .myAcademy().studyMinutes()).isEqualTo(480);
    }

    @Test
    @DisplayName("배치 전이면 비어 있다 — 그 자리에서 집계하지 않는다")
    void emptyBeforeBatch() {
        var view = rankingService.view(minji, RankingPeriod.WEEKLY, day1);

        assertThat(view.overallTop()).isNull();
        assertThat(view.myAcademy()).isNull();
    }

    // ── 달력·일자 ─────────────────────────────────────────────

    @Test
    @DisplayName("★ 확정 전인 날은 상태·순공이 null이다 — 0으로 채우면 결석처럼 보인다")
    void unconfirmedDayIsNull() {
        var report = reportService.day(minji, day1.plusDays(5));

        assertThat(report.finalStatus()).isNull();
        assertThat(report.studyMinutes()).isNull();
    }

    @Test
    @DisplayName("달력은 월간 합계·출석률을 함께 내린다 — 지각은 출석으로 센다")
    void monthlyCarriesTotals() {
        var month = reportService.month(minji, 2026, 8);

        assertThat(month.studyMinutes()).isEqualTo(880);
        assertThat(month.attendanceRate()).isEqualTo(100);      // 등원 + 지각
        assertThat(month.days()).hasSize(2);
    }

    @Test
    @DisplayName("결석일은 출석률 분모에 들어간다")
    void absentDayLowersRate() {
        var month = reportService.month(doyun, 2026, 8);

        assertThat(month.attendanceRate()).isEqualTo(50);
    }

    // ── 셀프 피드백 ───────────────────────────────────────────

    @Test
    @DisplayName("★ 하루 1행이다 — 다시 쓰면 덮어쓴다")
    void feedbackIsOverwritten() {
        LocalDate today = LocalDate.now(clock);
        reportService.writeFeedback(minji, today, "오늘은 집중이 잘 됐다");
        reportService.writeFeedback(minji, today, "다시 생각해보니 아쉬웠다");
        em.flush();

        assertThat(reportService.day(minji, today).selfFeedback())
                .isEqualTo("다시 생각해보니 아쉬웠다");
    }

    @Test
    @DisplayName("★ 미래 날짜에는 못 쓴다 — 달력이 오지 않은 칸을 채운 것으로 표시한다")
    void futureFeedbackIsRejected() {
        assertThatThrownBy(() -> reportService.writeFeedback(
                minji, LocalDate.now(clock).plusDays(1), "내일 잘하겠다"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("미래");
    }

    @Test
    @DisplayName("달력이 피드백 작성 여부를 함께 내린다 — 앱이 날짜마다 다시 묻지 않게")
    void calendarMarksFeedback() {
        reportService.writeFeedback(minji, day1, "첫날 회고");
        em.flush();

        var month = reportService.month(minji, 2026, 8);

        assertThat(month.days()).anySatisfy(cell -> {
            assertThat(cell.date()).isEqualTo(day1);
            assertThat(cell.hasFeedback()).isTrue();
        });
    }

    private StudentEnrollment reload(StudentEnrollment enrollment) {
        return em.find(StudentEnrollment.class, enrollment.getId());
    }
}
