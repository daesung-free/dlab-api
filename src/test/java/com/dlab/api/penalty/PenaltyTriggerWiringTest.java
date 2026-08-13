package com.dlab.api.penalty;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.attendance.entity.AttendanceEventType;
import com.dlab.domain.attendance.entity.AttendanceSource;
import com.dlab.domain.attendance.entity.AttendanceTaggingLog;
import com.dlab.domain.attendance.service.DailyAttendanceConfirmService;
import com.dlab.domain.penalty.entity.PenaltyTriggerType;
import com.dlab.domain.penalty.service.PenaltyRuleEngine;
import com.dlab.domain.routine.entity.DailyRoutine;
import com.dlab.domain.routine.entity.DailyRoutineResult;
import com.dlab.domain.routine.service.DailyRoutineService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.EntityManager;
import java.time.Clock;
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
 * 자동 상벌점 트리거 연결 (I-5).
 *
 * <p><b>규칙 화면을 만들어도 엔진을 부르는 곳이 없으면 아무 일도 안 일어난다.</b>
 * 여기서 검증하는 건 점수 계산이 아니라 <b>호출이 실제로 걸리는가</b>다 —
 * 엔진 자체는 {@code PenaltyRuleEngineTest}가 따로 검증한다.
 *
 * <p>엔진이 {@code REQUIRES_NEW}라 같은 트랜잭션에서 만든 규칙을 못 읽는다. 그래서
 * 부여 결과가 아니라 <b>호출 자체</b>를 본다.
 */
@SpringBootTest
@Transactional
class PenaltyTriggerWiringTest {

    @Autowired DailyAttendanceConfirmService confirmService;
    @Autowired DailyRoutineService routineService;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean PenaltyRuleEngine penaltyRuleEngine;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    StudentEnrollment minji;
    AuthPrincipal admin;
    LocalDate yesterday;

    @BeforeEach
    void setUp() {
        yesterday = LocalDate.now(clock).minusDays(1);

        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);
        // 교시가 없으면 운영일이 아니라 확정 배치가 그냥 넘어간다
        em.persist(new com.dlab.domain.period.entity.PeriodMaster(bundang, (short) 2026,
                (short) 1, "자습", com.dlab.domain.period.entity.DayType.of(yesterday),
                com.dlab.domain.period.entity.PeriodType.SELF_STUDY,
                LocalTime.of(8, 0), LocalTime.of(22, 0)));

        Student student = new Student("DL-2026-0419", "김민지", "010-1111-2222");
        em.persist(student);
        minji = new StudentEnrollment(student, bundang, (short) 2026,
                "2026-0001", null, GradeType.HIGH3);
        em.persist(minji);
        em.flush();

        admin = AuthPrincipal.of(1L, "EMPLOYEE", bundang.getId(),
                List.of(Role.BRANCH_ADMIN), false);
    }

    private void tag(AttendanceEventType type, int hour) {
        em.persist(new AttendanceTaggingLog(bundang, minji, type, AttendanceSource.KIOSK_NFC,
                yesterday.atTime(hour, 0).atZone(com.dlab.common.config.TimeConfig.KST).toInstant(),
                yesterday));
    }

    // ── 출결 ──────────────────────────────────────────────────

    @Test
    @DisplayName("★★ 결석은 확정 배치가 건다 — 태깅이 없어서 태깅 경로가 알 수 없는 유일한 상태다")
    void absenceIsTriggeredByBatch() {
        confirmService.confirm(bundang, yesterday);      // 태깅 없음 → 결석
        em.flush();

        verify(penaltyRuleEngine).apply(eq(minji), eq(PenaltyTriggerType.ATTENDANCE),
                eq(DailyAttendanceConfirmService.ABSENT_CONDITION), eq(yesterday));
    }

    @Test
    @DisplayName("★★ 사유가 승인된 결석은 벌점 대상이 아니다 — 무단결석만 건다")
    void excusedAbsenceIsNotTriggered() {
        // 사유 없이 등원한 날은 결석이 아니므로 트리거되지 않는다
        tag(AttendanceEventType.CHECK_IN, 9);
        em.flush();

        confirmService.confirm(bundang, yesterday);
        em.flush();

        verify(penaltyRuleEngine, never()).apply(eq(minji), eq(PenaltyTriggerType.ATTENDANCE),
                eq(DailyAttendanceConfirmService.ABSENT_CONDITION), eq(yesterday));
    }

    @Test
    @DisplayName("★ 배치는 지각을 다시 걸지 않는다 — 태깅 시점에 이미 부여됐다")
    void batchDoesNotRetriggerLate() {
        tag(AttendanceEventType.LATE, 10);
        em.flush();

        confirmService.confirm(bundang, yesterday);
        em.flush();

        verify(penaltyRuleEngine, never()).apply(eq(minji), eq(PenaltyTriggerType.ATTENDANCE),
                eq(AttendanceEventType.LATE.getCode()), eq(yesterday));
    }

    // ── 루틴 ──────────────────────────────────────────────────

    @Test
    @DisplayName("★★ 루틴은 공개 시점에 건다 — 검수 중인 결과로 벌점을 주면 교사가 고치는 동안 학생에게 보인다")
    void routineIsTriggeredOnPublish() {
        DailyRoutine routine = routineService.create(bundang.getId(), (short) 2026,
                (short) yesterday.getMonthValue(), null, "수학 테스트", "수학",
                (short) 10, true, (short) 1, admin);
        em.flush();

        routineService.saveResults(routine.getId(), yesterday,
                List.of(new DailyRoutineService.ResultInput(minji.getId(),
                        com.dlab.domain.routine.entity.RoutineResultStatus.REVIEWED,
                        (short) 8, (short) 8, null)),
                admin);
        em.flush();

        verify(penaltyRuleEngine, never()).apply(eq(minji), eq(PenaltyTriggerType.DAILY_ROUTINE),
                org.mockito.ArgumentMatchers.anyString(), eq(yesterday));

        routineService.publishAll(routine.getId(), yesterday, admin);
        em.flush();

        DailyRoutineResult result = em.createQuery(
                        "SELECT r FROM DailyRoutineResult r WHERE r.enrollment.id = :id",
                        DailyRoutineResult.class)
                .setParameter("id", minji.getId())
                .getSingleResult();

        verify(penaltyRuleEngine).apply(eq(minji), eq(PenaltyTriggerType.DAILY_ROUTINE),
                eq(result.getStatus().name()), eq(yesterday));
    }
}
