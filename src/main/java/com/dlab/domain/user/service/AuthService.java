package com.dlab.domain.user.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.*;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.AccountStatus;
import com.dlab.domain.user.entity.AccountType;
import com.dlab.domain.user.repository.AccountRepository;
import com.dlab.domain.user.repository.AccountRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * 로그인 · 토큰 재발급 · 로그아웃.
 *
 * <p>학생은 관리자 승인 전(PENDING)에는 로그인 자체가 막힌다 — 승인 전 앱 접근을 완전히
 * 차단해야 하기 때문이다(CLAUDE.md §3). 중간 상태는 두지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AccountRepository accountRepository;
    private final AccountRoleRepository accountRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final TokenBlacklist tokenBlacklist;
    private final Clock clock;

    public record TokenPair(String accessToken, String refreshToken) {
    }

    @Transactional
    public TokenPair login(String loginId, String rawPassword) {
        Account account = accountRepository.findByLoginId(loginId)
                // 계정 없음과 비밀번호 틀림을 구분해서 알려주지 않는다 — 계정 존재 여부가 새어나간다
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));

        if (account.getPasswordHash() == null
                || !passwordEncoder.matches(rawPassword, account.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        verifyLoginAllowed(account);

        AuthPrincipal principal = toPrincipal(account);
        String accessToken = tokenProvider.createAccessToken(principal);
        String refreshToken = tokenProvider.createRefreshToken(account.getId());
        refreshTokenStore.save(account.getId(), refreshToken, tokenProvider.refreshTokenTtl());

        account.recordLogin(Instant.now(clock));
        return new TokenPair(accessToken, refreshToken);
    }

    /**
     * Refresh Token으로 재발급.
     *
     * <p>역할·지점은 <b>DB에서 다시 읽는다</b> — 권한이 회수됐는데 옛 토큰의 역할을 그대로
     * 복사하면 회수가 무의미해진다.
     */
    @Transactional
    public TokenPair refresh(String refreshToken) {
        Long accountId = tokenProvider.parseRefreshTokenAccountId(refreshToken);
        if (accountId == null) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }
        // 저장된 것과 다르면 이미 재발급됐거나 탈취된 토큰이다
        if (!refreshTokenStore.matches(accountId, refreshToken)) {
            log.warn("저장되지 않은 Refresh Token 사용 시도: accountId={}", accountId);
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TOKEN));
        verifyLoginAllowed(account);

        AuthPrincipal principal = toPrincipal(account);
        String newAccess = tokenProvider.createAccessToken(principal);
        String newRefresh = tokenProvider.createRefreshToken(accountId);
        // 회전 — 이전 Refresh Token은 이 시점부터 무효다
        refreshTokenStore.save(accountId, newRefresh, tokenProvider.refreshTokenTtl());

        return new TokenPair(newAccess, newRefresh);
    }

    /**
     * 로그아웃.
     *
     * <p>Refresh Token을 지우는 것만으로는 부족하다 — 이미 발급된 Access Token이 만료될
     * 때까지 살아 있기 때문에 블랙리스트에 올려 즉시 막는다.
     */
    @Transactional
    public void logout(Long accountId, String accessToken) {
        refreshTokenStore.delete(accountId);
        if (accessToken != null) {
            // 남은 수명만큼만 들고 있으면 된다 — 그 뒤엔 어차피 만료로 거부된다
            tokenBlacklist.add(accessToken, tokenProvider.accessTokenTtl());
        }
    }

    private void verifyLoginAllowed(Account account) {
        if (account.getStatus() == AccountStatus.PENDING) {
            // 학생 가입 승인 대기. 안내 문구가 달라야 해서 별도 코드로 구분한다.
            throw new BusinessException(ErrorCode.SIGNUP_PENDING);
        }
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE);
        }
    }

    private AuthPrincipal toPrincipal(Account account) {
        Set<String> roles = accountRoleRepository.findRoleNamesByAccountId(account.getId());
        return new AuthPrincipal(
                account.getId(),
                account.getAccountType(),
                resolveAcademyId(account),
                roles);
    }

    /**
     * 소속 지점. 학부모는 자녀를 따라가므로 지점이 없다(§A2) — null이면
     * 지점 필터가 필요한 조회에서 걸러야 한다.
     */
    private Long resolveAcademyId(Account account) {
        if (account.getAccountType() == AccountType.TEACHER && account.getTeacher() != null) {
            return account.getTeacher().getAcademy().getId();
        }
        if (account.getAccountType() == AccountType.EMPLOYEE && account.getEmployee() != null) {
            return account.getEmployee().getAcademy().getId();
        }
        return null;
    }

    /** 남은 수명 계산용 — 블랙리스트 TTL을 정확히 잡고 싶을 때 쓴다. */
    public Duration accessTokenTtl() {
        return tokenProvider.accessTokenTtl();
    }
}
