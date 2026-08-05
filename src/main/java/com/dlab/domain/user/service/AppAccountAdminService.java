package com.dlab.domain.user.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.LoginAttemptStore;
import com.dlab.common.security.PasswordPolicy;
import com.dlab.common.security.RefreshTokenStore;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 앱 계정 관리 — 잠금 해제 · 임시 비밀번호 재발급 (요구사항 F-4.12-1).
 *
 * <p><b>계정을 새로 만드는 기능은 없다.</b> 2026-08-05 시트 확정:
 * <i>"학생은 앱에서 직접 가입(자가가입 단일 경로) → 관리자 승인 후 완료. 관리자는 계정을
 * 직접 생성하지 않고 앱 계정 승인·초기화(분실 시 임시 비밀번호 재발급)·잠금 해제를 담당"</i>.
 * 관리자 발급 경로를 만들면 승인 절차를 우회하는 두 번째 가입 경로가 생긴다.
 *
 * <p>온보딩 상태 모니터링·가입 승인/반려는 A-2 작업에서 붙인다(현재 미구현).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppAccountAdminService {

    private final AccountRepository accountRepository;
    private final LoginAttemptStore loginAttemptStore;
    private final RefreshTokenStore refreshTokenStore;
    private final PasswordEncoder passwordEncoder;

    /**
     * 잠금 해제.
     *
     * <p><b>실패 카운터도 함께 지운다.</b> 안 지우면 카운터가 이미 한도에 도달해 있어
     * 다음 실패 한 번에 곧바로 다시 잠긴다 — 관리자가 풀어줬는데 안 풀린 것처럼 보인다.
     */
    @Transactional
    public void unlock(Long accountId) {
        Account account = find(accountId);
        account.unlock();
        loginAttemptStore.clear(account.getLoginId());
        log.info("계정 잠금 해제: accountId={}", accountId);
    }

    /**
     * 임시 비밀번호 재발급 (분실 시에 한함).
     *
     * <p>평문을 <b>이 반환값으로 딱 한 번만</b> 돌려준다. 저장하지 않으므로 관리자가
     * 놓치면 다시 발급해야 한다 — 저장해두면 그 자체가 유출 경로가 된다.
     *
     * <p><b>기존 세션을 끊는다.</b> 계정 탈취 때문에 재발급하는 경우가 있는데, Refresh Token을
     * 남겨두면 공격자가 계속 갱신해 비밀번호를 바꾼 의미가 사라진다.
     * 다만 <b>이미 발급된 Access Token은 최대 1시간 살아 있다</b> — 값을 알아야 블랙리스트에
     * 올릴 수 있는데 서버가 들고 있지 않기 때문이다. 즉시 끊어야 하면 계정을 정지시켜야 한다.
     */
    @Transactional
    public String reissueTemporaryPassword(Long accountId) {
        Account account = find(accountId);
        String temporary = PasswordPolicy.generateTemporary();
        account.issueTemporaryPassword(passwordEncoder.encode(temporary));
        loginAttemptStore.clear(account.getLoginId());
        refreshTokenStore.delete(accountId);
        log.info("임시 비밀번호 재발급: accountId={}", accountId);
        return temporary;
    }

    private Account find(Long accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
    }
}
