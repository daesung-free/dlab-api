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
