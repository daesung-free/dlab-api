package com.dlab.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.AccountStatus;
import com.dlab.domain.user.entity.ClassAssignment;
import com.dlab.domain.user.entity.ClassMaster;
import com.dlab.domain.user.entity.ClassType;
import com.dlab.domain.user.entity.EnrollmentStatus;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.entity.Teacher;
import com.dlab.domain.user.repository.ClassAssignmentRepository;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * 학생 상태 전이 + 후속처리 (P1-04).
 *
 * <p>후속처리가 <b>같은 트랜잭션</b>에서 도는지, 그리고 <b>휴원과 종료가 다르게</b>
 * 처리되는지가 핵심이다. 반만 처리되면 "퇴원인데 반 배정이 살아 있는" 행이 남고,
 * 그건 화면에 안 보여서 아무도 못 고친다.
 */
@SpringBootTest
@Transactional
class StudentStatusServiceTest {

    @Autowired
    private StudentStatusService studentStatusService;

    @Autowired
    private StudentEnrollmentRepository enrollmentRepository;

    @Autowired
    private ClassAssignmentRepository classAssignmentRepository;

    @Autowired
    private EntityManager em;

    private Academy bundang;
    private StudentEnrollment minji;
    private Account minjiAccount;
    private ClassAssignment assignment;
    private AuthPrincipal admin;

    @BeforeEach
    void setUp() {
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);

        Student student = new Student("DL-2026-0419", "김민지", "010-1111-2222");
        em.persist(student);

        minji = new StudentEnrollment(student, bundang, (short) 2026,
                "2026-0001", "ABC001", GradeType.HIGH3);
        em.persist(minji);

        Teacher homeroom = new Teacher(bundang, "박담임", "010-7777-7777");
        em.persist(homeroom);
        ClassMaster classMaster =
                new ClassMaster(bundang, (short) 2026, "3반", ClassType.FIXED, homeroom);
        em.persist(classMaster);
        assignment = new ClassAssignment(bundang, minji, classMaster, ClassType.FIXED);
        em.persist(assignment);

        minjiAccount = Account.forStudent(student, "minji", "hash");
        minjiAccount.approve();
        em.persist(minjiAccount);

        em.flush();

        admin = AuthPrincipal.of(1L, "EMPLOYEE", bundang.getId(),
                List.of(Role.BRANCH_ADMIN), false);
    }

    private StudentEnrollment change(EnrollmentStatus to, String reason) {
        StudentEnrollment result = studentStatusService.changeStatus(
                admin, minji.getId(), to, LocalDate.of(2026, 8, 4), reason);
        em.flush();
        return result;
    }

    @Test
    @DisplayName("★ 퇴원하면 current가 내려간다 — 안 내리면 퇴원생 카드로 태깅이 통과한다")
    void withdrawalRevokesCurrentSoCardStopsWorking() {
        change(EnrollmentStatus.WITHDRAWN, "자진 퇴원");

        assertThat(minji.isCurrent()).isFalse();
        assertThat(minji.getWithdrawalDate()).isEqualTo(LocalDate.of(2026, 8, 4));
        assertThat(enrollmentRepository.findCurrentByRfidNo("ABC001")).isEmpty();
    }

    @Test
    @DisplayName("★ 휴원은 current를 건드리지 않는다 — 복귀 예정이라 등록 건이 유효하다")
    void leaveKeepsEnrollmentCurrent() {
        change(EnrollmentStatus.ON_LEAVE, "건강상 휴원");

        assertThat(minji.isCurrent()).isTrue();
        assertThat(minji.getWithdrawalDate()).isNull();
    }

    @Test
    @DisplayName("★ 종료 시 반 배정이 풀린다 — 같은 트랜잭션이다")
    void terminationDeactivatesClassAssignment() {
        change(EnrollmentStatus.EXPELLED, "규정 위반");

        assertThat(assignment.isActive()).isFalse();
        assertThat(classAssignmentRepository.findActiveFixedByEnrollmentId(minji.getId()))
                .isEmpty();
    }

    @Test
    @DisplayName("★ 휴원은 반 배정을 풀지 않는다 — 풀면 복귀 시 담임이 사라져 승인자도 없어진다")
    void leaveKeepsClassAssignment() {
        change(EnrollmentStatus.ON_LEAVE, "건강상 휴원");

        assertThat(assignment.isActive()).isTrue();
        assertThat(classAssignmentRepository.findActiveFixedByEnrollmentId(minji.getId()))
                .isPresent();
    }

    @Test
    @DisplayName("앱 계정이 상태를 따라간다 — 휴원은 정지, 퇴원은 탈퇴")
    void appAccountFollowsStatus() {
        change(EnrollmentStatus.ON_LEAVE, "휴원");
        assertThat(minjiAccount.getStatus()).isEqualTo(AccountStatus.SUSPENDED);

        change(EnrollmentStatus.ENROLLED, "복귀");
        assertThat(minjiAccount.getStatus()).isEqualTo(AccountStatus.ACTIVE);

        change(EnrollmentStatus.WITHDRAWN, "퇴원");
        assertThat(minjiAccount.getStatus()).isEqualTo(AccountStatus.WITHDRAWN);
    }

    @Test
    @DisplayName("★ 승인 대기(PENDING) 계정은 활성화되지 않는다 — 학생 승인제가 뚫린다")
    void pendingAccountIsNeverActivatedByStatusChange() {
        ReflectionTestUtils.setField(minjiAccount, "status", AccountStatus.PENDING);
        em.flush();

        change(EnrollmentStatus.ON_LEAVE, "휴원");
        assertThat(minjiAccount.getStatus()).isEqualTo(AccountStatus.PENDING);

        change(EnrollmentStatus.ENROLLED, "복귀");
        assertThat(minjiAccount.getStatus()).isEqualTo(AccountStatus.PENDING);
    }

    @Test
    @DisplayName("★ 착오 정정으로 재원 복귀 시 퇴원일이 지워진다")
    void revertClearsWithdrawalDate() {
        change(EnrollmentStatus.WITHDRAWN, "퇴원");
        assertThat(minji.getWithdrawalDate()).isNotNull();

        change(EnrollmentStatus.ENROLLED, "착오 입력 정정");

        assertThat(minji.getWithdrawalDate()).isNull();
        assertThat(minji.isCurrent()).isTrue();
        assertThat(enrollmentRepository.findCurrentByRfidNo("ABC001")).isPresent();
    }

    @Test
    @DisplayName("★ 종료끼리 직접 전이는 거부된다")
    void terminalToTerminalIsRejected() {
        change(EnrollmentStatus.WITHDRAWN, "퇴원");

        assertThatThrownBy(() -> change(EnrollmentStatus.EXPELLED, "제적으로 정정"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_ENROLLMENT_STATUS_TRANSITION);
    }

    @Test
    @DisplayName("모든 변경이 이력에 남는다 — 처리자와 사유 포함")
    void everyChangeIsRecorded() {
        change(EnrollmentStatus.ON_LEAVE, "건강상 휴원");
        change(EnrollmentStatus.ENROLLED, "복귀");
        change(EnrollmentStatus.WITHDRAWN, "자진 퇴원");

        var history = studentStatusService.history(admin, minji.getId());

        assertThat(history).hasSize(3);
        assertThat(history).extracting(h -> h.getToStatus())
                .containsExactlyInAnyOrder(EnrollmentStatus.ON_LEAVE,
                        EnrollmentStatus.ENROLLED, EnrollmentStatus.WITHDRAWN);
        assertThat(history).allSatisfy(h -> {
            assertThat(h.getFromStatus()).isNotEqualTo(h.getToStatus());
            assertThat(h.getEffectiveDate()).isEqualTo(LocalDate.of(2026, 8, 4));
            assertThat(h.getCreatedBy()).isNotNull();
            assertThat(h.getReason()).isNotBlank();
        });
    }

    @Test
    @DisplayName("★ 효력일은 처리일과 다를 수 있다 — 소급 처리(환불 일할계산이 이 날짜를 쓴다)")
    void effectiveDateCanBeBackdated() {
        studentStatusService.changeStatus(admin, minji.getId(), EnrollmentStatus.WITHDRAWN,
                LocalDate.of(2026, 7, 15), "지난달 그만둔 건 이번 달 입력");
        em.flush();

        assertThat(minji.getWithdrawalDate()).isEqualTo(LocalDate.of(2026, 7, 15));
        assertThat(studentStatusService.history(admin, minji.getId()))
                .first()
                .satisfies(h -> {
                    assertThat(h.getEffectiveDate()).isEqualTo(LocalDate.of(2026, 7, 15));
                    // 처리 시각은 오늘이다 — 둘을 섞으면 소급분이 통계에서 사라진다
                    assertThat(h.getCreatedAt()).isNotNull();
                });
    }

    @Test
    @DisplayName("★ 상태 변경도 지점을 확인한다")
    void statusChangeChecksAcademy() {
        Academy ilsan = new Academy("32", "일산", LocalTime.of(9, 0));
        em.persist(ilsan);
        em.flush();

        AuthPrincipal ilsanAdmin = AuthPrincipal.of(2L, "EMPLOYEE", ilsan.getId(),
                List.of(Role.BRANCH_ADMIN), false);

        assertThatThrownBy(() -> studentStatusService.changeStatus(ilsanAdmin, minji.getId(),
                EnrollmentStatus.WITHDRAWN, null, "타지점 시도"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
    }
}
