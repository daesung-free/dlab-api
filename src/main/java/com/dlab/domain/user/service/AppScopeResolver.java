package com.dlab.domain.user.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.AccountRepository;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 앱 계정 → 조회 대상 등록 건.
 *
 * <p><b>학생은 본인, 학부모는 자녀를 지정</b>한다. 계정 하나에 자녀가 여럿이라
 * (계정 1개 + 자녀 N 구조) 서버가 누구 기준인지 정할 수 없다.
 *
 * <p><b>학부모 경로는 반드시 {@link ParentSignupService#requireMyChild}를 거친다.</b>
 * 자녀 ID를 요청 파라미터로 받는 구조라, 검사하지 않으면 남의 자녀 ID를 넣는 것만으로
 * 그 학생의 출결·성적이 열린다 — 지점 필터({@code SearchScope})와 같은 급의 규칙이다.
 *
 * <p>앱 컨트롤러마다 같은 코드를 다시 쓰지 않으려고 한곳에 뒀다. 도메인마다 따로 짜면
 * <b>한 군데서 자녀 확인을 빠뜨려도 나머지가 멀쩡해 보여</b> 눈에 띄지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AppScopeResolver {

    private final AccountRepository accountRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final ParentSignupService parentSignupService;

    /**
     * @param studentId 학부모일 때만 쓴다. 학생 계정이면 무시된다
     */
    @Transactional(readOnly = true)
    public StudentEnrollment resolve(Long accountId, Long studentId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

        if (account.getStudent() != null) {
            return enrollmentRepository.findCurrentByStudentId(account.getStudent().getId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));
        }
        if (account.getGuardian() != null) {
            if (studentId == null) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "자녀를 지정해야 합니다.");
            }
            return parentSignupService.requireMyChild(account.getGuardian().getId(), studentId);
        }
        throw new BusinessException(ErrorCode.FORBIDDEN, "학생·학부모 계정만 이용할 수 있습니다.");
    }

    /** 계정 자체가 필요할 때. 마이페이지가 학생/학부모를 갈라 보여준다. */
    @Transactional(readOnly = true)
    public Account account(Long accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }
}
