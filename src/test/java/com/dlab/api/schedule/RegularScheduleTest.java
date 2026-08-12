package com.dlab.api.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.approval.entity.ApprovalItem;
import com.dlab.domain.approval.entity.ApprovalStatus;
import com.dlab.domain.approval.entity.ApproverType;
import com.dlab.domain.approval.entity.RequestType;
import com.dlab.domain.attendance.entity.AttendanceEventType;
import com.dlab.domain.attendance.entity.AttendanceSource;
import com.dlab.domain.attendance.entity.AttendanceTaggingLog;
import com.dlab.domain.schedule.entity.RegularSchedule;
import com.dlab.domain.schedule.service.RegularScheduleService;
import com.dlab.domain.schedule.service.RegularScheduleService.ItemInput;
import com.dlab.domain.schedule.service.ScheduleComplianceService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정기일정 (F-4.1-7, 앱 A-7).
 *
 * <p>현강·과외를 월 단위로 미리 등록하고, 승인되면 그 시간 외출이 무단이 아니게 된다.
 * 등록 시각과 실제 외출이 30분 이상 어긋나면 미인정이다.
 */
@SpringBootTest
@Transactional
class RegularScheduleTest {

    @Autowired RegularScheduleService scheduleService;
    @Autowired ScheduleComplianceService complianceService;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    StudentEnrollment minji;
    AuthPrincipal admin;
    LocalDate today;
    short thisMonth;

    @BeforeEach
    void setUp() {
        today = LocalDate.now(clock);
        thisMonth = (short) today.getMonthValue();

        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);

        // 승인 정책이 없으면 학생 등록 자체가 안 된다 — 라우팅이 여기서 나온다
        em.persist(new ApprovalItem(bundang, (short) 2026,
                RequestType.REGULAR_SCHEDULE, ApproverType.PARENT, null, null));

        Student student = new Student("DL-2026-0419", "김민지", "010-1111-2222");
        em.persist(student);
        minji = new StudentEnrollment(student, bundang, (short) 2026,
                "2026-0001", null, GradeType.HIGH3);
        em.persist(minji);
        em.flush();

        admin = AuthPrincipal.of(1L, "EMPLOYEE", bundang.getId(),
                List.of(Role.BRANCH_ADMIN), false);
    }

    private ItemInput item(DayOfWeek day, int startHour, int endHour) {
        return new ItemInput(day, LocalTime.of(startHour, 0), LocalTime.of(endHour, 0),
                "○○학원 수학", "강남");
    }

    /** 오늘 요일로 일정을 잡는다 — 판정을 오늘 날짜로 돌리기 위해서다. */
    private ItemInput todayItem(int startHour, int endHour) {
        return item(today.getDayOfWeek(), startHour, endHour);
    }

    private void tag(AttendanceEventType type, int hour, int minute) {
        Instant at = ZonedDateTime.of(today, LocalTime.of(hour, minute),
                com.dlab.common.config.TimeConfig.KST).toInstant();
        em.persist(new AttendanceTaggingLog(bundang, minji, type,
                AttendanceSource.KIOSK_NFC, at, today));
    }

    // ── 등록 ──────────────────────────────────────────────────

    @Test
    @DisplayName("★ 학생 등록은 승인 라우팅을 탄다 — 승인 전에는 인정되지 않는다")
    void studentSubmissionNeedsApproval() {
        RegularSchedule schedule = scheduleService.submitByStudent(
                minji, thisMonth, List.of(item(DayOfWeek.TUESDAY, 19, 21)));
        em.flush();

        assertThat(schedule.getApprovalRequest().getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(schedule.isApproved()).isFalse();
    }

    @Test
    @DisplayName("★★ 관리자(담임) 등록분은 자동 승인이다 — 승인자가 곧 등록자다")
    void adminRegistrationIsAutoApproved() {
        RegularSchedule schedule = scheduleService.registerByAdmin(
                admin, minji.getId(), thisMonth, List.of(item(DayOfWeek.TUESDAY, 19, 21)));
        em.flush();

        assertThat(schedule.getApprovalRequest()).isNull();
        assertThat(schedule.isApproved()).isTrue();
    }

    @Test
    @DisplayName("★ 같은 달에 두 번 제출할 수 없다 — 병합 규칙(I-27)이 미확정이라 사람이 판단한다")
    void duplicateMonthIsRejected() {
        scheduleService.submitByStudent(minji, thisMonth,
                List.of(item(DayOfWeek.TUESDAY, 19, 21)));
        em.flush();

        assertThatThrownBy(() -> scheduleService.registerByAdmin(
                admin, minji.getId(), thisMonth, List.of(item(DayOfWeek.THURSDAY, 19, 21))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 등록된");
    }

    @Test
    @DisplayName("★ 같은 요일에 겹치는 시간은 막는다 — 어느 줄로 판정할지 정할 수 없다")
    void overlappingItemsRejected() {
        assertThatThrownBy(() -> scheduleService.submitByStudent(minji, thisMonth,
                List.of(item(DayOfWeek.TUESDAY, 19, 21), item(DayOfWeek.TUESDAY, 20, 22))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("겹치는");
    }

    @Test
    @DisplayName("다른 요일이면 겹치지 않는다")
    void differentDayIsFine() {
        RegularSchedule schedule = scheduleService.submitByStudent(minji, thisMonth,
                List.of(item(DayOfWeek.TUESDAY, 19, 21), item(DayOfWeek.THURSDAY, 19, 21)));
        em.flush();

        assertThat(schedule.getItems()).hasSize(2);
    }

    @Test
    @DisplayName("★ 지난 달은 등록할 수 없다 — 사후 인정 통로가 되면 사유신청이 무의미해진다")
    void pastMonthIsRejected() {
        short lastMonth = (short) today.minusMonths(1).getMonthValue();
        if (lastMonth > thisMonth) {
            return;     // 1월이면 지난 달이 작년이라 이 검사 대상이 아니다
        }
        assertThatThrownBy(() -> scheduleService.submitByStudent(minji, lastMonth,
                List.of(item(DayOfWeek.TUESDAY, 19, 21))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("지난 달");
    }

    @Test
    @DisplayName("★★ 학생이 고치면 승인을 다시 받는다 — 승인 안 한 시간대가 인정되면 안 된다")
    void editRequiresReapproval() {
        RegularSchedule schedule = scheduleService.submitByStudent(minji, thisMonth,
                List.of(item(DayOfWeek.TUESDAY, 19, 21)));
        em.flush();
        Long firstApprovalId = schedule.getApprovalRequest().getId();

        scheduleService.replaceItems(schedule.getId(),
                List.of(item(DayOfWeek.TUESDAY, 20, 22)));
        em.flush();

        assertThat(schedule.getApprovalRequest().getId()).isNotEqualTo(firstApprovalId);
        assertThat(schedule.isApproved()).isFalse();
    }

    // ── 인정 판정 ─────────────────────────────────────────────

    @Test
    @DisplayName("★ 등록 시각에 맞춰 나가면 인정된다")
    void onTimeDepartureIsRecognized() {
        scheduleService.registerByAdmin(admin, minji.getId(), thisMonth,
                List.of(todayItem(19, 21)));
        tag(AttendanceEventType.OUTING, 19, 5);
        em.flush();
        em.clear();

        var verdicts = complianceService.judge(reload(), today);

        assertThat(verdicts).hasSize(1);
        assertThat(verdicts.get(0).recognized()).isTrue();
        assertThat(verdicts.get(0).gapMinutes()).isEqualTo(5);
    }

    @Test
    @DisplayName("★★ 30분 이상 어긋나면 미인정이다")
    void lateDepartureIsNotRecognized() {
        scheduleService.registerByAdmin(admin, minji.getId(), thisMonth,
                List.of(todayItem(19, 21)));
        tag(AttendanceEventType.OUTING, 19, 35);
        em.flush();
        em.clear();

        var verdicts = complianceService.judge(reload(), today);

        assertThat(verdicts.get(0).recognized()).isFalse();
        assertThat(verdicts.get(0).gapMinutes()).isEqualTo(35);
    }

    @Test
    @DisplayName("★ 일찍 나가도 30분 이상이면 미인정이다 — 차이의 절댓값으로 본다")
    void earlyDepartureIsAlsoNotRecognized() {
        scheduleService.registerByAdmin(admin, minji.getId(), thisMonth,
                List.of(todayItem(19, 21)));
        tag(AttendanceEventType.OUTING, 18, 20);
        em.flush();
        em.clear();

        assertThat(complianceService.judge(reload(), today).get(0).recognized()).isFalse();
    }

    @Test
    @DisplayName("★★ 아예 안 나가면 미인정이다 — 등록만 해두고 자리를 비우는 통로가 된다")
    void noDepartureIsNotRecognized() {
        scheduleService.registerByAdmin(admin, minji.getId(), thisMonth,
                List.of(todayItem(19, 21)));
        tag(AttendanceEventType.CHECK_IN, 9, 0);
        em.flush();
        em.clear();

        var verdict = complianceService.judge(reload(), today).get(0);

        assertThat(verdict.recognized()).isFalse();
        assertThat(verdict.actualDeparture()).isNull();
        assertThat(verdict.gapMinutes()).isNull();
    }

    @Test
    @DisplayName("★★ 복귀가 늦어도 나간 시각이 맞으면 인정이다 — 돌아오는 시각은 외부 사정이다")
    void lateReturnDoesNotBreakRecognition() {
        scheduleService.registerByAdmin(admin, minji.getId(), thisMonth,
                List.of(todayItem(19, 21)));
        tag(AttendanceEventType.OUTING, 19, 0);
        tag(AttendanceEventType.RETURN, 22, 10);      // 70분 지연 복귀
        em.flush();
        em.clear();

        assertThat(complianceService.judge(reload(), today).get(0).recognized()).isTrue();
    }

    @Test
    @DisplayName("★ 승인 전 일정은 판정 대상이 아니다 — 대기중인 일정으로 외출이 인정되면 승인이 무의미해진다")
    void pendingScheduleIsNotJudged() {
        scheduleService.submitByStudent(minji, thisMonth, List.of(todayItem(19, 21)));
        tag(AttendanceEventType.OUTING, 19, 0);
        em.flush();
        em.clear();

        assertThat(complianceService.judge(reload(), today)).isEmpty();
    }

    @Test
    @DisplayName("다른 요일 일정은 그날 판정에 안 나온다")
    void otherWeekdayIsNotJudged() {
        scheduleService.registerByAdmin(admin, minji.getId(), thisMonth,
                List.of(item(today.getDayOfWeek().plus(1), 19, 21)));
        tag(AttendanceEventType.OUTING, 19, 0);
        em.flush();
        em.clear();

        assertThat(complianceService.judge(reload(), today)).isEmpty();
    }

    @Test
    @DisplayName("정기일정이 없는 학생은 판정 대상이 아니다")
    void noScheduleMeansNoVerdict() {
        tag(AttendanceEventType.OUTING, 19, 0);
        em.flush();
        em.clear();

        assertThat(complianceService.judge(reload(), today)).isEmpty();
    }

    @Test
    @DisplayName("★ 규칙이 없으면 미인정이어도 벌점이 생기지 않는다 (I-5 미확정)")
    void noPenaltyRuleMeansNoPoints() {
        scheduleService.registerByAdmin(admin, minji.getId(), thisMonth,
                List.of(todayItem(19, 21)));
        tag(AttendanceEventType.OUTING, 19, 40);
        em.flush();
        em.clear();

        var verdicts = complianceService.judgeAndPenalize(reload(), today);

        assertThat(verdicts.get(0).recognized()).isFalse();
        Long count = em.createQuery(
                        "SELECT COUNT(p) FROM PenaltyPoint p WHERE p.enrollment.id = :id", Long.class)
                .setParameter("id", minji.getId())
                .getSingleResult();
        assertThat(count).isZero();
    }

    private StudentEnrollment reload() {
        return em.find(StudentEnrollment.class, minji.getId());
    }
}
