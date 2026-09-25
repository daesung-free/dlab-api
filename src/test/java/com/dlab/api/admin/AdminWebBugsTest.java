package com.dlab.api.admin;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.audit.AuditAction;
import com.dlab.domain.audit.AuditLog;
import com.dlab.domain.audit.AuditLogRepository;
import com.dlab.domain.event.entity.AnnualEvent;
import com.dlab.domain.event.repository.AnnualEventRepository;
import com.dlab.domain.master.service.YearlySnapshotService;
import com.dlab.domain.routine.entity.DailyRoutine;
import com.dlab.domain.routine.entity.DailyRoutineResult;
import com.dlab.domain.routine.entity.RoutineResultStatus;
import com.dlab.domain.routine.service.DailyRoutineService;
import com.dlab.domain.routine.service.DailyRoutineService.ResultInput;
import com.dlab.domain.user.entity.*;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 관리자 웹 버그 — 루틴 결과가 조용히 무시되던 것 · 계정 이력 작성자 이름 · 연간 행사 전년도 복사. */
@SpringBootTest
@Transactional
class AdminWebBugsTest {

    @Autowired DailyRoutineService routineService;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired com.dlab.api.admin.staff.AdminStaffController staffController;
    @Autowired YearlySnapshotService snapshotService;
    @Autowired AnnualEventRepository eventRepository;
    @Autowired EntityManager em;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    StudentEnrollment student;
    AuthPrincipal admin;
    DailyRoutine routine;
    final LocalDate day = LocalDate.of(2026, 9, 1);

    @BeforeEach
    void setUp() {
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);
        Student s = new Student("DL-BUG", "루틴학생", "010-0000-0000");
        em.persist(s);
        student = new StudentEnrollment(s, bundang, (short) 2026, "2026-0001", null, GradeType.N_SU);
        em.persist(student);
        em.flush();
        admin = AuthPrincipal.of(1L, "EMPLOYEE", bundang.getId(), List.of(Role.BRANCH_ADMIN), false);
        routine = routineService.create(bundang.getId(), (short) 2026, (short) 9, null,
                "수학 테스트", "수학", (short) 10, true, (short) 1, admin);
        em.flush();
    }

    private void save(RoutineResultStatus status, Integer self, Integer reviewed) {
        routineService.saveResults(routine.getId(), day, List.of(new ResultInput(student.getId(),
                status, self == null ? null : self.shortValue(),
                reviewed == null ? null : reviewed.shortValue(), null)), admin);
        em.flush();
    }

    private DailyRoutineResult result() {
        return routineService.grid(routine.getId(), day, admin).stream()
                .filter(r -> r.getEnrollment().getId().equals(student.getId()))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("★ '예정' 으로 되돌리면 실제로 되돌아간다 — 예전엔 무시되면서 저장 1건이라고 답했다")
    void revertToPlanned() {
        save(RoutineResultStatus.REVIEWED, 7, 8);
        save(RoutineResultStatus.PLANNED, null, null);

        DailyRoutineResult r = result();
        assertThat(r.getStatus()).isEqualTo(RoutineResultStatus.PLANNED);
        assertThat(r.getSelfScore()).isNull();
        assertThat(r.getReviewedScore()).isNull();
    }

    @Test
    @DisplayName("★ 검수완료에서 제출로 되돌리면 검수 점수가 지워진다 — 남아 있던 자리")
    void revertToSubmittedClearsReview() {
        save(RoutineResultStatus.REVIEWED, 7, 8);
        save(RoutineResultStatus.SUBMITTED, 7, null);

        assertThat(result().getStatus()).isEqualTo(RoutineResultStatus.SUBMITTED);
        assertThat(result().getSelfScore()).isEqualTo((short) 7);
        assertThat(result().getReviewedScore()).isNull();
    }

    @Test
    @DisplayName("검수 점수를 지우면 지워진다")
    void clearingReviewedScoreSticks() {
        save(RoutineResultStatus.REVIEWED, 7, 8);
        save(RoutineResultStatus.REVIEWED, null, null);

        assertThat(result().getReviewedScore()).isNull();
        // 가채점은 학생이 적어낸 값이라 비워 보내도 남는다
        assertThat(result().getSelfScore()).isEqualTo((short) 7);
    }

    @Test
    @DisplayName("모순된 입력은 막는다 — 결시에 점수, 제출에 검수 점수")
    void contradictionsRejected() {
        assertThatThrownBy(() -> save(RoutineResultStatus.ABSENT, 5, null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> save(RoutineResultStatus.SUBMITTED, 5, 6))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("공개된 결과는 검수 전으로 되돌릴 수 없다 — 공개 상태에서 점수 정정은 된다")
    void publishedCannotGoBack() {
        save(RoutineResultStatus.PUBLISHED, 7, 8);

        assertThatThrownBy(() -> save(RoutineResultStatus.PLANNED, null, null))
                .isInstanceOf(BusinessException.class);
        save(RoutineResultStatus.PUBLISHED, 7, 9);
        assertThat(result().getReviewedScore()).isEqualTo((short) 9);
    }

    @Test
    @WithMockUser(roles = "SUPER_ADMIN")
    @DisplayName("★ 계정 이력의 작성자는 이름이다 — 'EMPLOYEE' 가 나오던 자리")
    void accountHistoryShowsActorName() {
        Employee hq = new Employee(bundang, "김행정");
        em.persist(hq);
        Account actor = Account.forEmployee(hq, "hq-bug", "x", false);
        em.persist(actor);
        em.flush();
        auditLogRepository.save(new AuditLog(com.dlab.domain.user.service.StaffAccountService.AUDIT_ACCOUNT, 999L, AuditAction.UPDATE, bundang.getId(),
                actor.getId(), "EMPLOYEE", null, null, Instant.now()));

        var rows = staffController.accountHistory(999L).data();

        assertThat(rows).extracting(r -> r.actorName()).contains("김행정");
    }

    @Test
    @DisplayName("★ 전년도 복사에 연간 행사가 함께 넘어간다 — 날짜는 한 해 뒤로")
    void yearlyCopyIncludesAnnualEvents() {
        eventRepository.save(new AnnualEvent((short) 2026, bundang.getId(), "여름 설명회",
                LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 11), null, true, null));
        em.flush();
        AuthPrincipal head = new AuthPrincipal(1L, "admin", bundang.getId(),
                Set.of(Role.SUPER_ADMIN), true, false);

        var result = snapshotService.copy(bundang.getId(), (short) 2026, (short) 2027, head);
        em.flush();

        assertThat(result.copied()).containsEntry("annualEvent", 1);
        assertThat(eventRepository.findAllOfYear((short) 2027, bundang.getId()))
                .extracting(AnnualEvent::getStartDate).containsExactly(LocalDate.of(2027, 7, 10));
    }
}
