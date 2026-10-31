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
 *
 * <p><b>{@code accountType}을 따로 보지 않고 연관관계로 판별한다.</b> 계정 생성 경로가
 * {@code Account.forStudent/forGuardian/forEmployee/forTeacher} 넷뿐이고 셋 다 타입과
 * 연관관계를 짝으로 세팅하므로, 둘을 함께 검사해도 같은 결과다 —
 * 조건이 둘이면 나중에 한쪽만 고쳐 어긋날 여지가 생긴다.
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

    /**
     * <b>학생 본인</b>만 통과한다.
     *
     * <p>신청·제출처럼 <b>학부모가 하면 안 되는 행위</b>가 쓴다 — 사유 제출은 학부모가
     * 승인자라 본인이 내고 본인이 승인하면 절차가 무의미해지고, 특강·예약은 신청 주체가
     * 학생이다. 조회와 달리 {@code studentId}를 받지 않는 이유이기도 하다.
     *
     * @param what 거절 문구에 들어갈 행위 이름. "사유 신청은 …" 처럼 쓰인다
     */
    @Transactional(readOnly = true)
    public StudentEnrollment requireStudent(Long accountId, String what) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

        if (account.getStudent() == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "%s은(는) 학생 본인만 할 수 있습니다.".formatted(what));
        }
        return enrollmentRepository.findCurrentByStudentId(account.getStudent().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));
    }

    /**
     * ★★ <b>학부모 본인</b>만 통과한다. 자녀인지 매번 확인한다.
     *
     * <h2>★ 왜 필요한가 — 승인권은 학부모의 것이다</h2>
     * 우선 승인자 지정이 그렇다(디랩 1029·1031). 원래 <b>학생만</b> 고를 수 있었는데
     * (dd09a27), 그러면 <b>학생이 「담임」을 골라 학부모 모르게 학부모가 승인에서
     * 빠진다.</b> 그리고 이 선택은 <b>「10분 무응답 시 직원에게 승인권이 넘어가는 것에
     * 동의」 기록</b>이라 <b>그 동의도 학부모가 해야 의미가 있다.</b>
     *
     * <p>⚠️ {@link #resolve} 와 다르다 — {@code resolve} 는 <b>학생 계정도 통과</b>시킨다.
     * 그래서 조회에는 {@code resolve}, <b>학부모만 해야 하는 행위</b>에는 이것을 쓴다.
     *
     * @param what 거절 문구에 들어갈 행위 이름. "승인자 지정은 …" 처럼 쓰인다
     */
    @Transactional(readOnly = true)
    public StudentEnrollment requireGuardian(Long accountId, Long studentId, String what) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

        if (account.getGuardian() == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "%s은(는) 학부모만 할 수 있습니다.".formatted(what));
        }
        if (studentId == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "자녀를 지정해야 합니다.");
        }
        return parentSignupService.requireMyChild(account.getGuardian().getId(), studentId);
    }

    /** 계정 자체가 필요할 때. 마이페이지가 학생/학부모를 갈라 보여준다. */
    @Transactional(readOnly = true)
    public Account account(Long accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }
}
