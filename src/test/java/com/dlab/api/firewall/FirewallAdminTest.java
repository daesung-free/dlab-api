package com.dlab.api.firewall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.approval.entity.ApprovalItem;
import com.dlab.domain.approval.entity.ApproverType;
import com.dlab.domain.approval.entity.RequestType;
import com.dlab.domain.firewall.entity.FirewallRequest;
import com.dlab.domain.firewall.entity.UnlockStatus;
import com.dlab.domain.firewall.service.FirewallAdminService;
import com.dlab.domain.firewall.service.FirewallRequestService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
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
 * 방화벽 해제 관리 (F-4.11-10).
 *
 * <p>Nebula 실제 제어는 목업이다(E-1) — 상태 전이만 검증한다.
 */
@SpringBootTest
@Transactional
class FirewallAdminTest {

    @Autowired FirewallRequestService requestService;
    @Autowired FirewallAdminService adminService;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    Academy ilsan;
    StudentEnrollment minji;
    AuthPrincipal admin;
    AuthPrincipal ilsanAdmin;

    @BeforeEach
    void setUp() {
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        ilsan = new Academy("32", "일산", LocalTime.of(9, 0));
        em.persist(bundang);
        em.persist(ilsan);

        em.persist(new ApprovalItem(bundang, (short) 2026, RequestType.FIREWALL_UNLOCK,
                ApproverType.PARENT, (short) 10, ApproverType.TEACHER));

        Student student = new Student("DL-2026-0419", "김민지", "010-1111-2222");
        em.persist(student);
        minji = new StudentEnrollment(student, bundang, (short) 2026,
                "2026-0001", null, GradeType.HIGH3);
        em.persist(minji);
        em.flush();

        admin = AuthPrincipal.of(1L, "EMPLOYEE", bundang.getId(),
                List.of(Role.BRANCH_ADMIN), false);
        ilsanAdmin = AuthPrincipal.of(2L, "EMPLOYEE", ilsan.getId(),
                List.of(Role.BRANCH_ADMIN), false);
    }

    private FirewallRequest request(int minutes) {
        FirewallRequest r = requestService.create(minji.getId(), minutes, "인강 수강");
        em.flush();
        return r;
    }

    // ── 상태 ──────────────────────────────────────────────────

    @Test
    @DisplayName("★ 신청만으로는 해제중이 아니다 — 승인 상태와 해제 상태는 다르다")
    void newRequestIsWaiting() {
        FirewallRequest r = request(60);

        assertThat(r.getUnlockStatus()).isEqualTo(UnlockStatus.WAITING);
        assertThat(adminService.active(admin, null)).isEmpty();
    }

    @Test
    @DisplayName("해제가 시작되면 해제중 목록에 뜬다")
    void activatedRequestIsListed() {
        FirewallRequest r = request(60);
        r.activate(Instant.now(clock));
        em.flush();
        em.clear();

        assertThat(adminService.active(admin, null)).hasSize(1);
    }

    // ── 만료 ──────────────────────────────────────────────────

    @Test
    @DisplayName("★★ 종료 시각이 지나면 만료 처리된다 — 지금까지는 시간이 지나도 열린 채였다")
    void overdueUnlockIsExpired() {
        FirewallRequest r = request(60);
        r.activate(Instant.now(clock).minusSeconds(2 * 60 * 60));   // 2시간 전 시작 → 이미 만료
        em.flush();
        em.clear();

        assertThat(adminService.expireOverdue()).isEqualTo(1);
        em.flush();
        em.clear();

        assertThat(em.find(FirewallRequest.class, r.getId()).getUnlockStatus())
                .isEqualTo(UnlockStatus.EXPIRED);
    }

    @Test
    @DisplayName("아직 안 지난 해제는 건드리지 않는다")
    void activeUnlockIsNotExpired() {
        FirewallRequest r = request(60);
        r.activate(Instant.now(clock));
        em.flush();

        assertThat(adminService.expireOverdue()).isZero();
    }

    @Test
    @DisplayName("만료 처리는 다시 돌려도 중복되지 않는다 — 이미 EXPIRED는 대상이 아니다")
    void expiryIsIdempotent() {
        FirewallRequest r = request(60);
        r.activate(Instant.now(clock).minusSeconds(7200));
        em.flush();

        adminService.expireOverdue();
        em.flush();

        assertThat(adminService.expireOverdue()).isZero();
    }

    // ── 위반·제재 ─────────────────────────────────────────────

    @Test
    @DisplayName("★ 1회 적발로는 제한되지 않는다")
    void singleViolationDoesNotRestrict() {
        adminService.recordViolation(admin, minji.getId(), null);
        em.flush();

        assertThat(adminService.activeRestriction(minji.getId())).isEmpty();
    }

    @Test
    @DisplayName("★★ 2회 적발이면 2주 신청 제한이 걸린다")
    void secondViolationRestricts() {
        adminService.recordViolation(admin, minji.getId(), null);
        adminService.recordViolation(admin, minji.getId(), null);
        em.flush();

        var restriction = adminService.activeRestriction(minji.getId());

        assertThat(restriction).isPresent();
        assertThat(restriction.get().getRestrictedUntil())
                .isAfter(Instant.now(clock).plusSeconds(13 * 24 * 3600));
    }

    @Test
    @DisplayName("★★ 제한 중에는 신청 자체가 막힌다 — 승인 큐까지 올라가면 학부모가 승인해도 거절된다")
    void restrictedStudentCannotRequest() {
        adminService.recordViolation(admin, minji.getId(), null);
        adminService.recordViolation(admin, minji.getId(), null);
        em.flush();

        assertThatThrownBy(() -> requestService.create(minji.getId(), 60, "인강"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("제한");
    }

    @Test
    @DisplayName("★ 3회째 적발이 제재를 새로 만들지 않는다 — 이미 걸린 제재가 연장되면 기간이 계속 밀린다")
    void thirdViolationDoesNotStackRestriction() {
        adminService.recordViolation(admin, minji.getId(), null);
        adminService.recordViolation(admin, minji.getId(), null);
        adminService.recordViolation(admin, minji.getId(), null);
        em.flush();

        Long count = em.createQuery(
                        "SELECT COUNT(r) FROM FirewallRestriction r WHERE r.enrollment.id = :id",
                        Long.class)
                .setParameter("id", minji.getId())
                .getSingleResult();

        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("★★ 해제중에 적발하면 즉시 차단된다 — 남은 시간 동안 계속 쓰게 두면 적발이 무의미하다")
    void violationDuringActiveUnlockBlocksImmediately() {
        FirewallRequest r = request(300);
        r.activate(Instant.now(clock));
        em.flush();

        adminService.recordViolation(admin, minji.getId(), r.getId());
        em.flush();
        em.clear();

        assertThat(em.find(FirewallRequest.class, r.getId()).getUnlockStatus())
                .isEqualTo(UnlockStatus.EXPIRED);
    }

    @Test
    @DisplayName("제재를 해제하면 다시 신청할 수 있다 — 착오 등록 정정용이다")
    void liftedRestrictionAllowsRequestAgain() {
        adminService.recordViolation(admin, minji.getId(), null);
        adminService.recordViolation(admin, minji.getId(), null);
        em.flush();

        Long restrictionId = adminService.activeRestriction(minji.getId()).get().getId();
        adminService.liftRestriction(admin, restrictionId);
        em.flush();

        assertThat(requestService.create(minji.getId(), 60, "인강")).isNotNull();
    }

    // ── 스코프 ────────────────────────────────────────────────

    @Test
    @DisplayName("★★ 다른 지점 학생은 적발할 수 없다")
    void otherBranchStudentCannotBeReported() {
        assertThatThrownBy(() ->
                adminService.recordViolation(ilsanAdmin, minji.getId(), null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("이력 조회도 자기 지점만 본다")
    void searchIsScoped() {
        request(60);
        em.flush();
        em.clear();

        assertThat(adminService.search(admin, null, null, null, null, null)).hasSize(1);
        assertThat(adminService.search(ilsanAdmin, null, null, null, null, null)).isEmpty();
    }

    @Test
    @DisplayName("상태로 걸러진다")
    void searchFiltersByStatus() {
        request(60);
        em.flush();
        em.clear();

        assertThat(adminService.search(admin, null, null, UnlockStatus.WAITING, null, null))
                .hasSize(1);
        assertThat(adminService.search(admin, null, null, UnlockStatus.ACTIVE, null, null))
                .isEmpty();
    }
}
