package com.dlab.domain.attendance.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.domain.approval.entity.ApprovalItem;
import com.dlab.domain.approval.entity.ApprovalRequest;
import com.dlab.domain.approval.entity.ApprovalStatus;
import com.dlab.domain.approval.entity.ApproverType;
import com.dlab.domain.approval.entity.RequestType;
import com.dlab.domain.attendance.entity.AbsenceReason;
import com.dlab.domain.attendance.entity.AbsenceReasonType;
import com.dlab.domain.attendance.entity.AttendanceDailyStatus;
import com.dlab.domain.attendance.entity.AttendanceEventType;
import com.dlab.domain.attendance.entity.AttendanceSource;
import com.dlab.domain.attendance.entity.AttendanceTaggingLog;
import com.dlab.domain.attendance.entity.DailyStatus;
import com.dlab.domain.attendance.repository.AttendanceDailyStatusRepository;
import com.dlab.domain.period.entity.DayType;
import com.dlab.domain.period.entity.PeriodMaster;
import com.dlab.domain.period.entity.PeriodType;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.EnrollmentStatus;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * 일자 출결 확정 배치.
 *
 * <p><b>결석은 "없는 것"을 찾아내는 유일한 판정</b>이라 원장만 봐서는 셀 수 없다.
 * 이 배치가 없으면 {@code absence_cnt}가 영원히 0이고, 그 0이 "결석 없음"으로 읽힌다.
 */
@SpringBootTest
@Transactional
class DailyAttendanceConfirmServiceTest {

    @Autowired DailyAttendanceConfirmService confirmService;
    @Autowired AttendanceDailyStatusRepository dailyStatusRepository;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean
    MissingAttendanceScheduler scheduler;

    Academy bundang;
    LocalDate day;

    @BeforeEach
    void setUp() {
        day = LocalDate.now(clock).minusDays(1);

        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);

        // 그날 요일 구분에 교시를 심는다 — 없으면 "운영일 아님"으로 건너뛴다
        em.persist(new PeriodMaster(bundang, (short) 2026, (short) 1, "자습",
                DayType.of(day), PeriodType.SELF_STUDY,
                LocalTime.of(8, 0), LocalTime.of(22, 0)));
        em.flush();
    }

    private StudentEnrollment enroll(String code, String name, String stdNo) {
        Student student = new Student(code, name, "010-0000-0000");
        em.persist(student);
        StudentEnrollment enrollment = new StudentEnrollment(
                student, bundang, (short) 2026, stdNo, null, GradeType.HIGH3);
        em.persist(enrollment);
        em.flush();
        return enrollment;
    }

    private void tag(StudentEnrollment enrollment, AttendanceEventType type, int hour) {
        em.persist(new AttendanceTaggingLog(bundang, enrollment, type,
                AttendanceSource.KIOSK_NFC,
                day.atTime(hour, 0).atZone(clock.getZone()).toInstant(), day));
        em.flush();
    }

    private void approvedReason(StudentEnrollment enrollment, AbsenceReasonType type) {
        ApprovalItem item = new ApprovalItem(bundang, (short) 2026,
                RequestType.ABSENCE_REASON, ApproverType.TEACHER, null, null);
        em.persist(item);
        ApprovalRequest approval = new ApprovalRequest(
                bundang, item, enrollment, null, Instant.now(clock));
        ReflectionTestUtils.setField(approval, "status", ApprovalStatus.APPROVED);
        em.persist(approval);

        AbsenceReason reason = new AbsenceReason(bundang, enrollment, day, type, "사유");
        reason.linkApproval(approval);
        em.persist(reason);
        em.flush();
    }

    private Optional<AttendanceDailyStatus> statusOf(StudentEnrollment enrollment) {
        em.flush();
        em.clear();
        return dailyStatusRepository.findByEnrollmentIdAndAttendanceDate(enrollment.getId(), day);
    }

    @Test
    @DisplayName("★ 등원 기록이 없으면 결석 — 원장에 없는 것을 찾아내는 유일한 판정이다")
    void noArrivalBecomesAbsent() {
        StudentEnrollment student = enroll("DL-1", "김민지", "2026-0001");

        confirmService.confirm(bundang, day);

        assertThat(statusOf(student)).get()
                .satisfies(s -> {
                    assertThat(s.getFinalStatus()).isEqualTo(DailyStatus.ABSENT);
                    assertThat(s.isExcused()).isFalse();   // 무단
                });
    }

    @Test
    @DisplayName("★ 승인된 사유가 있어도 결석은 결석이다 — excused 플래그만 붙는다")
    void approvedReasonKeepsAbsentButMarksExcused() {
        StudentEnrollment student = enroll("DL-1", "김민지", "2026-0001");
        approvedReason(student, AbsenceReasonType.ABSENCE);

        confirmService.confirm(bundang, day);

        // 사유가 결석을 없애면 키오스크 화면에서 "결석 0회"로 보인다
        assertThat(statusOf(student)).get()
                .satisfies(s -> {
                    assertThat(s.getFinalStatus()).isEqualTo(DailyStatus.ABSENT);
                    assertThat(s.isExcused()).isTrue();
                });
    }

    @Test
    @DisplayName("★ 제출만 하고 승인 전인 사유는 무단이다 — 신청만 넣고 안 나오는 걸 막는다")
    void unapprovedReasonIsStillUnexcused() {
        StudentEnrollment student = enroll("DL-1", "김민지", "2026-0001");
        em.persist(new AbsenceReason(bundang, student, day, AbsenceReasonType.ABSENCE, "사유"));
        em.flush();

        confirmService.confirm(bundang, day);

        assertThat(statusOf(student)).get()
                .satisfies(s -> assertThat(s.isExcused()).isFalse());
    }

    @Test
    @DisplayName("등원하면 PRESENT, 지각하면 LATE")
    void arrivalBecomesPresentOrLate() {
        StudentEnrollment onTime = enroll("DL-1", "김민지", "2026-0001");
        StudentEnrollment late = enroll("DL-2", "박서준", "2026-0002");
        tag(onTime, AttendanceEventType.CHECK_IN, 8);
        tag(late, AttendanceEventType.LATE, 10);

        confirmService.confirm(bundang, day);

        assertThat(statusOf(onTime)).get()
                .satisfies(s -> assertThat(s.getFinalStatus()).isEqualTo(DailyStatus.PRESENT));
        assertThat(statusOf(late)).get()
                .satisfies(s -> assertThat(s.getFinalStatus()).isEqualTo(DailyStatus.LATE));
    }

    @Test
    @DisplayName("★ 지각하고 조퇴하면 지각이 이긴다 — 상태 필드가 하나뿐이라 골라야 한다")
    void lateWinsOverEarlyLeave() {
        StudentEnrollment student = enroll("DL-1", "김민지", "2026-0001");
        tag(student, AttendanceEventType.LATE, 10);
        tag(student, AttendanceEventType.EARLY_LEAVE, 15);

        confirmService.confirm(bundang, day);

        assertThat(statusOf(student)).get()
                .satisfies(s -> assertThat(s.getFinalStatus()).isEqualTo(DailyStatus.LATE));
    }

    @Test
    @DisplayName("정시 등원 후 조퇴는 EARLY_LEAVE")
    void earlyLeaveAfterOnTimeArrival() {
        StudentEnrollment student = enroll("DL-1", "김민지", "2026-0001");
        tag(student, AttendanceEventType.CHECK_IN, 8);
        tag(student, AttendanceEventType.EARLY_LEAVE, 15);

        confirmService.confirm(bundang, day);

        assertThat(statusOf(student)).get()
                .satisfies(s -> assertThat(s.getFinalStatus()).isEqualTo(DailyStatus.EARLY_LEAVE));
    }

    @Test
    @DisplayName("★ 휴원생은 대상이 아니다 — 넣으면 매일 결석이 쌓인다")
    void nonEnrolledStudentsAreSkipped() {
        StudentEnrollment onLeave = enroll("DL-1", "김민지", "2026-0001");
        onLeave.updateEnrollment(null, null, EnrollmentStatus.LEAVE);
        em.flush();

        confirmService.confirm(bundang, day);

        assertThat(statusOf(onLeave)).isEmpty();
    }

    @Test
    @DisplayName("★ 운영하지 않는 날은 행을 만들지 않는다 — 결석이 아니라 대상 아님이다")
    void nonOperatingDayCreatesNothing() {
        StudentEnrollment student = enroll("DL-1", "김민지", "2026-0001");

        // 교시가 없는 날을 고른다
        LocalDate noPeriodDay = day.minusDays(1);
        while (DayType.of(noPeriodDay) == DayType.of(day)) {
            noPeriodDay = noPeriodDay.minusDays(1);
        }

        int confirmed = confirmService.confirm(bundang, noPeriodDay);

        assertThat(confirmed).isZero();
        assertThat(dailyStatusRepository
                .findByEnrollmentIdAndAttendanceDate(student.getId(), noPeriodDay)).isEmpty();
    }

    @Test
    @DisplayName("★ 다시 돌려도 행이 늘지 않고, 뒤늦은 사유 승인이 반영된다")
    void rerunUpdatesInsteadOfDuplicating() {
        StudentEnrollment student = enroll("DL-1", "김민지", "2026-0001");

        confirmService.confirm(bundang, day);
        assertThat(statusOf(student)).get()
                .satisfies(s -> assertThat(s.isExcused()).isFalse());

        // 사유가 뒤늦게 승인됐다
        StudentEnrollment reloaded = em.find(StudentEnrollment.class, student.getId());
        approvedReason(reloaded, AbsenceReasonType.ABSENCE);
        confirmService.confirm(bundang, day);

        assertThat(dailyStatusRepository.findAll())
                .filteredOn(s -> s.getEnrollment().getId().equals(student.getId()))
                .hasSize(1);
        assertThat(statusOf(student)).get()
                .satisfies(s -> assertThat(s.isExcused()).isTrue());
    }
}
