package com.dlab.domain.approval.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.approval.entity.ApprovalRequest;
import com.dlab.domain.approval.repository.ApprovalRequestRepository;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.AccountType;
import com.dlab.domain.user.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 승인 대기 목록 조회.
 *
 * <p>"내가 승인해야 할 것"은 계정 종류에 따라 완전히 다른 질의다 — 학부모는 연결된 자녀
 * 기준이고, 담당선생님은 신청 시점에 자기가 에스컬레이션 대상으로 박힌 건 기준이다.
 * 그래서 클라이언트가 대상을 지정하는 게 아니라 <b>토큰의 주체로 판단</b>한다.
 */
@Service
@RequiredArgsConstructor
public class ApprovalQueryService {

    private final ApprovalRequestRepository approvalRequestRepository;
    private final AccountRepository accountRepository;

    /**
     * 관리자 현황 — 지점 단위로 "지금 승인이 몇 건 밀렸나".
     *
     * <p><b>담임 개인 대기열과 다른 질의다.</b> 담임용({@link #pendingForTeacher})은
     * 자기가 에스컬레이션 대상으로 박힌 건만 보는 것이라, 관리자가 그걸 부르면
     * 담당선생님이 아니어서 거절된다.
     */
    @Transactional(readOnly = true)
    public List<ApprovalRequest> board(com.dlab.common.security.AuthPrincipal me, Long academyId,
                                       com.dlab.domain.approval.entity.ApprovalStatus status,
                                       com.dlab.domain.approval.entity.RequestType requestType,
                                       java.time.LocalDate from, java.time.LocalDate to,
                                       java.time.ZoneId zone) {
        Long scope = me.resolveAcademyScope(academyId);
        return approvalRequestRepository.findBoard(scope, status, requestType,
                from.atStartOfDay(zone).toInstant(),
                // 끝 날짜를 포함해야 한다 — 오늘 들어온 건이 오늘 조회에서 빠지면 안 된다
                to.plusDays(1).atStartOfDay(zone).toInstant());
    }

    /** 학부모 앱 — 내 자녀들의 승인 대기 건. */
    @Transactional(readOnly = true)
    public List<ApprovalRequest> pendingForGuardian(Long accountId) {
        return approvalRequestRepository.findPendingForGuardianAccount(accountId);
    }

    /** 관리자 웹 — 내가 담당선생님인 승인 대기 건. */
    @Transactional(readOnly = true)
    public List<ApprovalRequest> pendingForTeacher(Long accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

        if (account.getAccountType() != AccountType.TEACHER || account.getTeacher() == null) {
            // 행정(EMPLOYEE)은 승인 에스컬레이션 대상이 아니다 — 담당선생님만 해당된다
            throw new BusinessException(ErrorCode.NOT_AN_APPROVER);
        }
        return approvalRequestRepository.findPendingForTeacher(account.getTeacher().getId());
    }
}
