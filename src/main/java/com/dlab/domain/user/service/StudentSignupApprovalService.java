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
     *
     * @param requestedAcademyId 조회할 지점. <b>비우면 내 지점</b>이고,
     *                           전 지점 권한자는 지정해야 한다
     */
    @Transactional(readOnly = true)
    public List<PendingSignup> pending(AuthPrincipal me, Long requestedAcademyId) {
        return pending(me, requestedAcademyId, false);
    }

    /**
     * @param includeApproved 승인된 건까지 함께 본다.
     *                        <p>★ <b>OT 대기자를 보려면 이게 필요하다.</b> 승인하는 순간
     *                        목록에서 사라지는데, <b>OT 완료는 그 뒤에 관리자가 눌러야 하는
     *                        단계</b>다. 승인분이 안 보이면 누구를 OT 처리해야 하는지 알
     *                        방법이 없어 화면이 거기서 끊긴다.
     *                        <p>기본은 {@code false} 다 — 승인 대기 화면에 이미 처리한 건이
     *                        섞이면 무엇을 눌러야 하는지 흐려진다.
     */
    @Transactional(readOnly = true)
    public List<PendingSignup> pending(AuthPrincipal me, Long requestedAcademyId,
                                       boolean includeApproved) {
        return enrollmentRepository
                .findCurrentByAcademyId(me.requireAcademyScope(requestedAcademyId)).stream()
                .map(e -> accountRepository.findByStudentId(e.getStudent().getId())
                        .filter(a -> visible(a, includeApproved))
                        .map(a -> new PendingSignup(e, a))
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * 승인 대기 + (선택) 승인분.
     *
     * <p>★ <b>온보딩이 끝난 학생은 승인분에서도 뺀다.</b> 이 화면이 다루는 것은 "가입 후
     * 아직 정리가 안 끝난 학생" 이라, 다 끝난 재원생까지 올라오면 목록이 전교생이 된다.
     */
    private boolean visible(Account account, boolean includeApproved) {
        if (account.getStatus() == AccountStatus.PENDING) {
            return true;
        }
        return includeApproved
                && account.getStatus() == AccountStatus.ACTIVE
                && account.getStudent() != null
                && account.getStudent().getOnboardingStatus() != OnboardingStatus.ACTIVE;
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

    /** 승인 대기 1건. */
    public record PendingSignup(StudentEnrollment enrollment, Account account) {
    }
}
