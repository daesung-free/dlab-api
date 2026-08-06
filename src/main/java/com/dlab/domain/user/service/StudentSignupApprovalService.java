package com.dlab.domain.user.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.AccountStatus;
import com.dlab.domain.user.entity.OnboardingStatus;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.AccountRepository;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 학생 가입 승인 (F-4.1 · A-2).
 *
 * <p><b>승인 전에는 앱 접근이 완전히 차단된다.</b> 중간 상태 없이
 * {@code PENDING} → {@code ACTIVE} 이분법이다(CLAUDE.md §3).
 *
 * <h2>승인은 온보딩 단계를 건드리지 않는다</h2>
 * 승인 여부는 {@link AccountStatus}, 온보딩 진행은 {@link OnboardingStatus}로 <b>축이 다르다.</b>
 * 승인했다고 온보딩을 전진시키면 OT를 안 받은 학생이 다음 단계로 넘어간다 —
 * 승인 직후 상태는 여전히 {@code REGISTERED}(OT 전)다.
 *
 * <h2>승인 주체는 행정({@code Employee})이다</h2>
 * 담당선생님이 아니다(CLAUDE.md §2). 권한 체크는 컨트롤러의 {@code @PreAuthorize}가 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class StudentSignupApprovalService {

    private final AccountRepository accountRepository;
    private final StudentEnrollmentRepository enrollmentRepository;

    /**
     * 가입 승인.
     *
     * <p>이미 승인된 계정은 <b>조용히 통과시킨다.</b> 관리자가 목록에서 두 번 눌렀을 때
     * 오류를 띄우면 "실패한 건가?" 하고 다시 누르게 된다 — 결과는 같으므로 멱등하게 둔다.
     */
    public Account approve(AuthPrincipal me, Long enrollmentId) {
        StudentEnrollment enrollment = requireEnrollment(me, enrollmentId);
        Account account = requireAccount(enrollment.getStudent());

        if (account.getStatus() == AccountStatus.ACTIVE) {
            return account;
        }
        if (account.getStatus() != AccountStatus.PENDING) {
            // 탈퇴·정지 계정을 승인으로 되살리지 않는다. 그건 별도 절차다
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE);
        }

        account.approve();
        log.info("학생 가입 승인: enrollmentId={}, accountId={}", enrollmentId, account.getId());
        return account;
    }

    /**
     * 승인 대기 목록.
     *
     * <p><b>지점 스코프가 걸린다</b> — 다른 지점 가입 신청까지 보이면 안 된다.
     */
    @Transactional(readOnly = true)
    public List<PendingSignup> pending(AuthPrincipal me) {
        return enrollmentRepository.findCurrentByAcademyId(scopeOf(me)).stream()
                .map(e -> accountRepository.findByStudentId(e.getStudent().getId())
                        .filter(a -> a.getStatus() == AccountStatus.PENDING)
                        .map(a -> new PendingSignup(e, a))
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * 대면 OT 완료 처리.
     *
     * <p>OT는 오프라인이라 <b>관리자가 눌러줘야</b> 학생 앱이 다음 단계로 넘어간다.
     * 앱이 스스로 못 넘기는 유일한 단계다.
     */
    public Student completeOt(AuthPrincipal me, Long enrollmentId) {
        StudentEnrollment enrollment = requireEnrollment(me, enrollmentId);
        Student student = enrollment.getStudent();

        // 승인 전에 OT부터 찍는 건 순서가 뒤집힌 것이다
        requireAccount(student);
        if (!accountRepository.findByStudentId(student.getId())
                .map(Account::isActive).orElse(false)) {
            throw new BusinessException(ErrorCode.STUDENT_NOT_APPROVED);
        }

        student.advanceOnboarding(OnboardingStatus.REGISTERED);
        return student;
    }

    private StudentEnrollment requireEnrollment(AuthPrincipal me, Long enrollmentId) {
        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));

        if (!me.canAccessAcademy(enrollment.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return enrollment;
    }

    /** 앱 가입을 안 한 학생은 승인할 대상이 없다 — 관리자가 먼저 등록한 경우다. */
    private Account requireAccount(Student student) {
        return accountRepository.findByStudentId(student.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
    }

    private Long scopeOf(AuthPrincipal me) {
        Long academyId = me.academyScopeFilter();
        if (academyId == null) {
            // 전 지점 권한자는 지점을 골라야 한다. 전 지점 대기목록을 한 번에 뿌리면
            // 어느 지점 건인지 구분 없이 승인 버튼이 눌린다
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "지점을 지정해야 합니다.");
        }
        return academyId;
    }

    /** 승인 대기 1건. */
    public record PendingSignup(StudentEnrollment enrollment, Account account) {
    }
}
