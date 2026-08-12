package com.dlab.api.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.approval.entity.ApprovalItem;
import com.dlab.domain.approval.entity.ApproverType;
import com.dlab.domain.approval.entity.RequestType;
import com.dlab.domain.approval.service.ApprovalItemService;
import com.dlab.domain.approval.service.ApprovalService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.EntityManager;
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
 * 승인 라우팅 정책 관리 (F-4.11-5).
 *
 * <p>이 행이 없으면 사유신청·정기일정·방화벽이 전부 거절된다.
 * 승인 주체 자체는 아직 미확정(I-12)이라 값이 아니라 <b>넣을 수 있는 경로</b>를 검증한다.
 */
@SpringBootTest
@Transactional
class ApprovalItemAdminTest {

    @Autowired ApprovalItemService approvalItemService;
    @Autowired ApprovalService approvalService;
    @Autowired EntityManager em;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    Academy ilsan;
    StudentEnrollment minji;
    AuthPrincipal branchAdmin;
    AuthPrincipal superAdmin;
    short year = 2026;

    @BeforeEach
    void setUp() {
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        ilsan = new Academy("32", "일산", LocalTime.of(9, 0));
        em.persist(bundang);
        em.persist(ilsan);

        Student student = new Student("DL-2026-0419", "김민지", "010-1111-2222");
        em.persist(student);
        minji = new StudentEnrollment(student, bundang, year, "2026-0001", null, GradeType.HIGH3);
        em.persist(minji);
        em.flush();

        branchAdmin = AuthPrincipal.of(1L, "EMPLOYEE", bundang.getId(),
                List.of(Role.BRANCH_ADMIN), false);
        superAdmin = AuthPrincipal.of(2L, "EMPLOYEE", bundang.getId(),
                List.of(Role.SUPER_ADMIN), true);
    }

    private void save(RequestType type, ApproverType approver,
                      Short timeout, ApproverType escalation) {
        approvalItemService.save(branchAdmin, null, year, type, approver, timeout, escalation);
        em.flush();
    }

    // ── 목록 ──────────────────────────────────────────────────

    @Test
    @DisplayName("★★ 안 정한 유형도 목록에 나온다 — 빠지면 신청이 막혀 있는 줄도 모른다")
    void unconfiguredTypesAreStillListed() {
        var rows = approvalItemService.list(branchAdmin, null, year);

        assertThat(rows).hasSize(RequestType.values().length);
        assertThat(rows).allSatisfy(r -> assertThat(r.configured()).isFalse());
    }

    @Test
    @DisplayName("★ 정책을 넣으면 그 유형의 신청이 통한다 — 없으면 거절된다")
    void policyEnablesRequests() {
        assertThatThrownBy(() ->
                approvalService.create(minji, RequestType.REGULAR_SCHEDULE))
                .isInstanceOf(BusinessException.class);

        save(RequestType.REGULAR_SCHEDULE, ApproverType.PARENT, null, null);
        em.clear();

        assertThat(approvalService.create(reload(), RequestType.REGULAR_SCHEDULE)).isNotNull();
    }

    // ── 저장 ──────────────────────────────────────────────────

    @Test
    @DisplayName("유형당 1행이다 — 다시 저장하면 고쳐진다")
    void saveIsUpsert() {
        save(RequestType.FIREWALL_UNLOCK, ApproverType.PARENT, (short) 10, ApproverType.TEACHER);
        save(RequestType.FIREWALL_UNLOCK, ApproverType.PARENT, (short) 15, ApproverType.TEACHER);
        em.clear();

        var rows = approvalItemService.list(branchAdmin, null, year).stream()
                .filter(r -> r.requestType() == RequestType.FIREWALL_UNLOCK).toList();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).timeoutMinutes()).isEqualTo((short) 15);
    }

    @Test
    @DisplayName("★★ 타임아웃과 에스컬레이션은 함께 지정한다 — 한쪽만 있으면 조용히 안 돈다")
    void halfConfiguredEscalationIsRejected() {
        assertThatThrownBy(() ->
                save(RequestType.FIREWALL_UNLOCK, ApproverType.PARENT, (short) 10, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("함께");

        assertThatThrownBy(() ->
                save(RequestType.FIREWALL_UNLOCK, ApproverType.PARENT, null, ApproverType.TEACHER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("함께");
    }

    @Test
    @DisplayName("★ 학부모로는 에스컬레이션할 수 없다 — 학부모는 최대 1인이라 넘길 곳이 없다")
    void escalationToParentIsRejected() {
        assertThatThrownBy(() ->
                save(RequestType.FIREWALL_UNLOCK, ApproverType.PARENT, (short) 10, ApproverType.PARENT))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("담당선생님");
    }

    @Test
    @DisplayName("★ AUTO면 타임아웃·에스컬레이션을 지운다 — 자동 승인에 무응답이 있을 수 없다")
    void autoClearsEscalation() {
        save(RequestType.REGULAR_SCHEDULE, ApproverType.AUTO, (short) 10, ApproverType.TEACHER);
        em.clear();

        var row = approvalItemService.list(branchAdmin, null, year).stream()
                .filter(r -> r.requestType() == RequestType.REGULAR_SCHEDULE).findFirst().get();

        assertThat(row.approverType()).isEqualTo(ApproverType.AUTO);
        assertThat(row.timeoutMinutes()).isNull();
        assertThat(row.escalationApproverType()).isNull();
    }

    @Test
    @DisplayName("타임아웃 0분은 막는다")
    void zeroTimeoutIsRejected() {
        assertThatThrownBy(() ->
                save(RequestType.FIREWALL_UNLOCK, ApproverType.PARENT, (short) 0, ApproverType.TEACHER))
                .isInstanceOf(BusinessException.class);
    }

    // ── 소급 ──────────────────────────────────────────────────

    @Test
    @DisplayName("★★ 정책을 바꿔도 대기중인 신청의 타임아웃은 안 바뀐다 — 안내한 시간이 뒤집히면 안 된다")
    void changeDoesNotAffectPendingRequests() {
        save(RequestType.FIREWALL_UNLOCK, ApproverType.PARENT, (short) 10, ApproverType.TEACHER);
        em.clear();

        var request = approvalService.create(reload(), RequestType.FIREWALL_UNLOCK);
        em.flush();
        Long requestId = request.getId();

        save(RequestType.FIREWALL_UNLOCK, ApproverType.PARENT, (short) 60, ApproverType.TEACHER);
        em.flush();
        em.clear();

        var reloaded = em.find(com.dlab.domain.approval.entity.ApprovalRequest.class, requestId);
        assertThat(reloaded.getTimeoutMinutes()).isEqualTo((short) 10);
    }

    // ── 스코프 ────────────────────────────────────────────────

    @Test
    @DisplayName("★★ 지점 관리자는 남의 지점 정책을 못 바꾼다")
    void branchAdminCannotTouchOtherBranch() {
        assertThatThrownBy(() -> approvalItemService.save(branchAdmin, ilsan.getId(), year,
                RequestType.FIREWALL_UNLOCK, ApproverType.PARENT, null, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("★ 전 지점 권한자는 지점을 지정해야 한다 — 정책은 지점마다 다르다")
    void superAdminMustPickBranch() {
        assertThatThrownBy(() -> approvalItemService.list(superAdmin, null, year))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("지점");
    }

    // ── 삭제 ──────────────────────────────────────────────────

    @Test
    @DisplayName("삭제하면 그 유형의 신청이 다시 막힌다")
    void deleteBlocksRequestsAgain() {
        save(RequestType.ABSENCE_REASON, ApproverType.PARENT, null, null);
        approvalItemService.delete(branchAdmin, null, year, RequestType.ABSENCE_REASON);
        em.flush();
        em.clear();

        assertThatThrownBy(() -> approvalService.create(reload(), RequestType.ABSENCE_REASON))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("전년도 복사분은 원본 id가 함께 나온다 — 복사본과 신규분을 구분할 유일한 근거다")
    void copiedRowExposesSource() {
        ApprovalItem source = new ApprovalItem(bundang, (short) 2025,
                RequestType.FIREWALL_UNLOCK, ApproverType.PARENT, (short) 10, ApproverType.TEACHER);
        em.persist(source);
        ApprovalItem copy = new ApprovalItem(bundang, year,
                RequestType.FIREWALL_UNLOCK, ApproverType.PARENT, (short) 10, ApproverType.TEACHER);
        copy.markCopiedFrom(source.getId());
        em.persist(copy);
        em.flush();
        em.clear();

        var row = approvalItemService.list(branchAdmin, null, year).stream()
                .filter(r -> r.requestType() == RequestType.FIREWALL_UNLOCK).findFirst().get();

        assertThat(row.copiedFrom()).isEqualTo(source.getId());
    }

    private StudentEnrollment reload() {
        return em.find(StudentEnrollment.class, minji.getId());
    }
}
