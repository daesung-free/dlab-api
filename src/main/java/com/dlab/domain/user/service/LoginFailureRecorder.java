package com.dlab.domain.user.service;

import com.dlab.common.security.LoginAttemptStore;
import com.dlab.common.security.PasswordPolicy;
import com.dlab.domain.user.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * 로그인 실패 기록 · 잠금 (A-1 "로그인 실패 5회 계정 잠금").
 *
 * <p><b>★ 별도 컴포넌트인 이유 — {@code REQUIRES_NEW}가 반드시 필요하다.</b>
 * 로그인 실패는 {@code BusinessException}으로 끝나는데, {@code AuthService.login()} 안에서
 * 잠금을 걸면 그 예외가 트랜잭션을 롤백시켜 <b>방금 건 잠금까지 같이 지워진다</b>.
 * 그러면 몇 번을 틀려도 계정이 영원히 안 잠긴다 — 코드는 멀쩡해 보이고 로그도 남는데
 * DB만 그대로다.
 *
 * <p>같은 클래스 안에 메서드로 두면 self-invocation이라 프록시를 안 타서 전파 설정이
 * 무시된다. 그래서 클래스를 분리했다.
 *
 * <p>실패 <b>횟수</b>는 Redis라 트랜잭션과 무관하게 이미 증가해 있다 — 롤백에 걸리는 건
 * DB 쪽 잠금 플래그뿐이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginFailureRecorder {

    private final AccountRepository accountRepository;
    private final com.dlab.common.security.SessionCutoff sessionCutoff;
    private final LoginAttemptStore loginAttemptStore;
    private final Clock clock;

    /**
     * 실패를 세고, 한도를 넘으면 계정을 잠근다.
     *
     * @param accountId 존재하지 않는 아이디로 시도한 경우 {@code null} — 셀 계정이 없어도
     *                  카운터는 올린다(있는 아이디인지 없는 아이디인지 구분되지 않게)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String loginId, Long accountId) {
        long failures = loginAttemptStore.recordFailure(loginId);
        if (failures < PasswordPolicy.MAX_LOGIN_FAILURES || accountId == null) {
            return;
        }
        accountRepository.findById(accountId).ifPresent(account -> {
            if (!account.isLocked()) {
                account.lock(Instant.now(clock));
                // ★ 잠긴 계정이 이미 로그인해 있을 수 있다 — 다른 기기에서 비밀번호를
                //   틀려 잠긴 경우다. 끊지 않으면 그 세션이 최대 1시간 더 살아 있어
                //   "잠겼는데 쓰고 있다"가 된다.
                sessionCutoff.revokeAll(accountId);
                log.warn("로그인 실패 {}회 누적으로 계정 잠금: accountId={}", failures, accountId);
            }
        });
    }
}
