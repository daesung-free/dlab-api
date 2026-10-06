package com.dlab.api.firewall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dlab.domain.approval.entity.ApprovalItem;
import com.dlab.domain.approval.entity.ApproverType;
import com.dlab.domain.approval.entity.RequestType;
import com.dlab.domain.approval.service.ApprovalService;
import com.dlab.domain.firewall.entity.FirewallRequest;
import com.dlab.domain.firewall.entity.UnlockStatus;
import com.dlab.domain.firewall.repository.FirewallRequestRepository;
import com.dlab.domain.firewall.service.FirewallAdminService;
import com.dlab.domain.firewall.service.FirewallRequestService;
import com.dlab.domain.kiosk.entity.BranchConfig;
import com.dlab.domain.user.entity.*;
import com.dlab.integration.zyxel.NebulaClient;
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
 * 와이파이 해제 — Voucher 배정 · 회수 (2026-10-06 벤더 확정분).
 *
 * <p>지키려는 것 넷이다.
 * <ol>
 *   <li><b>배정된 코드가 신청 건에 남는다</b> — 없으면 열어 준 와이파이를 닫을 수 없다</li>
 *   <li><b>지정한 시각이 와야 연다</b> — "15시에 열어달라"가 승인 즉시 열리면 안 된다</li>
 *   <li><b>★ 차단이 실패하면 해제중으로 남는다</b> — 만료로 기록하면 열린 채 영원히 남는다</li>
 *   <li><b>한 건의 실패가 다른 건을 막지 않는다</b> — 지점 하나의 장애가 전 지점을 막으면 안 된다</li>
 * </ol>
 */
@SpringBootTest
@Transactional
class NebulaVoucherFlowTest {

    private static final String SITE = "site-fw-01";

    @Autowired FirewallRequestService requestService;
    @Autowired FirewallAdminService adminService;
    @Autowired FirewallRequestRepository requestRepository;
    @Autowired ApprovalService approvalService;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean NebulaClient nebulaClient;
    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;
    @MockitoBean com.dlab.domain.approval.service.ApprovalReminderScheduler reminderScheduler;

    Academy academy;
    StudentEnrollment enrollment;
    Account guardianAccount;
    short year;

    @BeforeEach
    void setUp() {
        year = (short) LocalDate.now(clock).getYear();

        academy = new Academy("FWV1", "보우처테스트지점", LocalTime.of(9, 0));
        em.persist(academy);

        BranchConfig config = new BranchConfig(academy.getId());
        config.changeNebulaSiteId(SITE);
        em.persist(config);

        Teacher teacher = new Teacher(academy, "담임쌤", "010-1000-0001");
        em.persist(teacher);
        ClassMaster clazz = new ClassMaster(academy, year, "1반", ClassType.FIXED, teacher);
        em.persist(clazz);

        Student student = new Student("FWV-001", "해제학생", "010-2000-0001");
        em.persist(student);
        enrollment = new StudentEnrollment(student, academy, year, "0001", "RF-FWV", GradeType.N_SU);
        em.persist(enrollment);
        em.persist(new ClassAssignment(academy, enrollment, clazz, ClassType.FIXED));

        ParentGuardian guardian = new ParentGuardian("학부모", "010-3000-0001", "F");
        em.persist(guardian);
        em.persist(new StudentGuardianLink(student, guardian, (short) 1, true));
        guardianAccount = Account.forGuardian(guardian, "FWVPAR", "x");
        em.persist(guardianAccount);

        em.persist(new ApprovalItem(academy, year, RequestType.FIREWALL_UNLOCK,
                ApproverType.PARENT, (short) 10, ApproverType.TEACHER));
        em.flush();

        when(nebulaClient.assign(anyString(), anyInt())).thenReturn("528129");
    }

    /**
     * 신청 → 학부모 승인까지. 승인만으로는 아직 열리지 않는다.
     *
     * <p>★ <b>id 만 돌려준다.</b> 승인 경로가 영속성 컨텍스트를 비워서, 들고 있던 인스턴스는
     * 분리(detached)된다 — 그걸 그대로 보면 배치가 바꾼 값이 안 보인다.
     */
    private Long approvedRequest(Instant startAt, Instant endAt) {
        FirewallRequest request = startAt == null
                ? requestService.create(enrollment.getId(), 60, "인강 수강")
                : requestService.create(enrollment.getId(), 60, "인강 수강", startAt, endAt);
        em.flush();

        approvalService.approve(request.getApprovalRequest().getId(), guardianAccount.getId());
        em.flush();
        return request.getId();
    }

    private FirewallRequest reload(Long id) {
        em.flush();
        em.clear();
        return requestRepository.findById(id).orElseThrow();
    }

    @Test
    @DisplayName("★ 승인되면 코드를 배정받아 열리고, 그 코드가 신청 건에 남는다")
    void activatesWithVoucherCode() {
        Long id = approvedRequest(null, null);

        assertThat(adminService.activateApproved()).isEqualTo(1);

        FirewallRequest request = reload(id);
        assertThat(request.getUnlockStatus()).isEqualTo(UnlockStatus.ACTIVE);
        // ★ 이 값이 없으면 닫을 수가 없다
        assertThat(request.getVoucherCode()).isEqualTo("528129");
        verify(nebulaClient).assign(SITE, 60);
    }

    @Test
    @DisplayName("신청 시 지점 사이트가 박힌다 — 설정이 바뀌어도 열어 준 곳에서 닫아야 한다")
    void snapshotsSiteOnCreate() {
        FirewallRequest request = requestService.create(enrollment.getId(), 30, "인강");
        em.flush();

        assertThat(request.getZyxelSiteId()).isEqualTo(SITE);
    }

    @Test
    @DisplayName("★ 지정한 시각이 오기 전에는 열지 않는다 — 15시 신청이 13시에 열리면 안 된다")
    void waitsForRequestedWindow() {
        Instant later = Instant.now(clock).plusSeconds(3600);
        approvedRequest(later, later.plusSeconds(3600));

        assertThat(adminService.activateApproved()).isZero();
        verify(nebulaClient, never()).assign(anyString(), anyInt());
    }

    @Test
    @DisplayName("만료되면 배정했던 코드로 회수한다")
    void revokesWithAssignedCode() {
        Long id = approvedRequest(null, null);
        adminService.activateApproved();
        rewindEnd(id);

        assertThat(adminService.expireOverdue()).isEqualTo(1);

        assertThat(reload(id).getUnlockStatus()).isEqualTo(UnlockStatus.EXPIRED);
        verify(nebulaClient).revoke(SITE, "528129");
    }

    @Test
    @DisplayName("★★ 차단이 실패하면 해제중으로 남는다 — 만료로 기록하면 열린 채 영원히 남는다")
    void failedBlockStaysActive() {
        Long id = approvedRequest(null, null);
        adminService.activateApproved();
        rewindEnd(id);

        doThrow(new IllegalStateException("Nebula 통신 실패"))
                .when(nebulaClient).revoke(eq(SITE), anyString());

        // 닫은 건수가 0이어야 한다 — 실패를 성공으로 세면 아무도 모른다
        assertThat(adminService.expireOverdue()).isZero();

        FirewallRequest request = reload(id);
        assertThat(request.getUnlockStatus()).isEqualTo(UnlockStatus.ACTIVE);
        assertThat(request.getBlockAttempts()).isEqualTo((short) 1);
        // 관리자 화면이 이 값으로 "열린 채 남아 있을 수 있다"를 드러낸다
        assertThat(request.blockFailing()).isTrue();
    }

    @Test
    @DisplayName("★ 실패한 건은 다음 배치가 다시 잡는다 — 재시도 없이는 수동 폴백뿐이다")
    void retriesOnNextRun() {
        Long id = approvedRequest(null, null);
        adminService.activateApproved();
        rewindEnd(id);

        doThrow(new IllegalStateException("일시 실패"))
                .when(nebulaClient).revoke(eq(SITE), anyString());
        adminService.expireOverdue();

        // 다음 분에는 통신이 된다
        org.mockito.Mockito.reset(nebulaClient);
        assertThat(adminService.expireOverdue()).isEqualTo(1);

        FirewallRequest request = reload(id);
        assertThat(request.getUnlockStatus()).isEqualTo(UnlockStatus.EXPIRED);
        // 성공하면 실패 표시가 지워진다 — 안 지우면 화면이 영원히 경고를 띄운다
        assertThat(request.blockFailing()).isFalse();
    }

    /** 시각을 앞당길 수 없으므로 종료 시각을 과거로 밀어 "만료된" 상황을 만든다. */
    private void rewindEnd(Long id) {
        em.createNativeQuery("""
                        UPDATE firewall_request
                           SET unlock_end_at = unlock_end_at - make_interval(mins => 120)
                         WHERE id = :id
                        """)
                .setParameter("id", id)
                .executeUpdate();
        em.clear();
    }
}
