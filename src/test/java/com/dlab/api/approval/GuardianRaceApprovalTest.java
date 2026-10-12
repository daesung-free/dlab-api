package com.dlab.api.approval;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.domain.approval.entity.ApprovalItem;
import com.dlab.domain.approval.entity.ApprovalRequest;
import com.dlab.domain.approval.entity.ApprovalStatus;
import com.dlab.domain.approval.entity.ApproverType;
import com.dlab.domain.approval.entity.RequestType;
import com.dlab.domain.approval.service.ApprovalService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.ClassAssignment;
import com.dlab.domain.user.entity.ClassMaster;
import com.dlab.domain.user.entity.ClassType;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.ParentGuardian;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.entity.StudentGuardianLink;
import com.dlab.domain.user.entity.Teacher;
import com.dlab.domain.user.repository.AccountRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 부·모 양쪽 알림 + 선착순 승인 (2026-10-12 디랩 회신).
 *
 * <h2>★★ 이 테스트의 목적은 「되돌리는 것을 막는 것」이다</h2>
 * 0803 에 <b>「학부모 최대 1인」</b>으로 확정돼 {@code student_guardian_link.is_approver}
 * 컬럼을 만들었는데, <b>2026-10-12 회신으로 그 결정이 뒤집혔다</b> — 부·모 양쪽에 알림이
 * 가고 먼저 승인한 쪽으로 확정된다.
 *
 * <p>지금 코드는 <b>연결돼 있으면 통과</b>시켜서 이미 그렇게 동작한다. 그런데 컬럼이 그대로
 * 남아 있어, 나중에 보는 사람이 <i>"`is_approver` 가 있는데 왜 안 쓰지"</i> 라며 조건을
 * 넣으면 <b>지정되지 않은 쪽(대개 아버지)이 승인할 때 터진다.</b>
 *
 * <p>주석은 읽히지 않을 수 있으니 <b>테스트로 고정한다.</b> 이 테스트가 깨지면
 * 그 조건을 넣은 것이다 — 넣지 말아야 한다.
 */
@SpringBootTest
@Transactional
class GuardianRaceApprovalTest {

    @Autowired ApprovalService approvalService;
    @Autowired AccountRepository accountRepository;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy academy;
    StudentEnrollment enrollment;
    Student student;
    /** 승인자로 지정된 쪽(어머니) */
    Account designated;
    /** 지정되지 않은 쪽(아버지) — 여기가 핵심이다 */
    Account notDesignated;

    @BeforeEach
    void setUp() {
        short year = (short) java.time.LocalDate.now(clock).getYear();

        academy = new Academy("GR01", "선착순지점", LocalTime.of(9, 0));
        em.persist(academy);

        Teacher teacher = new Teacher(academy, "담임쌤", "010-1000-0002");
        em.persist(teacher);
        em.persist(Account.forTeacher(teacher, "GRTCH01", "x", false));

        ClassMaster classMaster = new ClassMaster(academy, year, "1반", ClassType.FIXED, teacher);
        em.persist(classMaster);

        student = new Student("GRSTU001", "선착순학생", "010-2000-0002");
        em.persist(student);
        enrollment = new StudentEnrollment(student, academy, year, "0001", "RF-GR01",
                GradeType.N_SU);
        em.persist(enrollment);
        em.persist(new ClassAssignment(academy, enrollment, classMaster, ClassType.FIXED));

        // 어머니 — is_approver = true (0803 구조에서 「그 1인」이었던 쪽)
        ParentGuardian mother = new ParentGuardian("어머니", "010-3000-0002", "F");
        em.persist(mother);
        em.persist(new StudentGuardianLink(student, mother, (short) 1, true));
        designated = Account.forGuardian(mother, "GRMOM01", "x");
        em.persist(designated);

        // 아버지 — is_approver = false. 연락처 보관용으로 연결돼 있다
        ParentGuardian father = new ParentGuardian("아버지", "010-3000-0003", "M");
        em.persist(father);
        em.persist(new StudentGuardianLink(student, father, (short) 2, false));
        notDesignated = Account.forGuardian(father, "GRDAD01", "x");
        em.persist(notDesignated);

        em.persist(new ApprovalItem(academy, year, RequestType.FIREWALL_UNLOCK,
                ApproverType.PARENT, (short) 10, ApproverType.TEACHER));
        em.flush();
    }

    private ApprovalRequest request() {
        ApprovalRequest r = approvalService.create(enrollment, RequestType.FIREWALL_UNLOCK);
        em.flush();
        return r;
    }

    @Test
    @DisplayName("★★ 승인자로 지정되지 않은 학부모도 승인할 수 있다 — is_approver 조건을 넣으면 깨진다")
    void notDesignatedGuardianCanApprove() {
        ApprovalRequest r = request();

        // 아버지(is_approver=false)가 승인한다.
        // is_approver 를 조건으로 걸면 여기서 NOT_AN_APPROVER 가 난다
        ApprovalRequest resolved = approvalService.approve(r.getId(), notDesignated.getId());
        em.flush();

        assertThat(resolved.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(resolved.getResolverType()).isEqualTo(ApproverType.PARENT);
        assertThat(resolved.getResolverAccount().getId()).isEqualTo(notDesignated.getId());
    }

    @Test
    @DisplayName("지정된 학부모도 물론 승인할 수 있다 — 둘 중 먼저 누른 쪽으로 확정된다")
    void designatedGuardianCanApprove() {
        ApprovalRequest r = request();

        ApprovalRequest resolved = approvalService.approve(r.getId(), designated.getId());
        em.flush();

        assertThat(resolved.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
    }

    @Test
    @DisplayName("★ 알림은 연결된 학부모 전원에게 간다 — 한쪽만 받으면 선착순이 성립하지 않는다")
    void bothGuardiansAreNotified() {
        var guardians = accountRepository.findGuardianAccountsOfStudent(student.getId());

        assertThat(guardians)
                .extracting(Account::getId)
                .containsExactlyInAnyOrder(designated.getId(), notDesignated.getId());
    }

    @Test
    @DisplayName("★ 먼저 승인한 쪽으로 끝난다 — 나중 쪽은 이미 처리된 건으로 막힌다")
    void firstApprovalWins() {
        ApprovalRequest r = request();

        ApprovalRequest first = approvalService.approve(r.getId(), notDesignated.getId());
        em.flush();

        // 어머니가 뒤늦게 눌러도 두 번 반영되지 않는다 — 처리된 건은 막힌다.
        // 막지 않으면 승인자·시각이 나중 사람으로 덮어써져 "누가 승인했나"가 바뀐다
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> approvalService.approve(r.getId(), designated.getId()))
                .isInstanceOf(com.dlab.common.exception.BusinessException.class);

        assertThat(first.getResolverAccount().getId()).isEqualTo(notDesignated.getId());
    }
}
