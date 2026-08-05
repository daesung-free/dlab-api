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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 로그인 · 토큰 재발급 · 로그아웃.
 *
 * <p>토큰의 서명·클레임은 {@link JwtProvider}가, 저장·무효화는 Redis
 * ({@link RefreshTokenStore} · {@link TokenBlacklist})가 담당한다.
 *
 * <p>학생은 관리자 승인 전(PENDING)에는 로그인 자체가 막힌다 — 승인 전 앱 접근을 완전히
 * 차단해야 하기 때문이다(CLAUDE.md §3). 중간 상태는 두지 않는다.
 *
 * <p><b>로그인 보안(A-1)</b> — 실패 5회 누적 시 계정 잠금(관리자가 해제),
 * 임시 비밀번호는 최초 로그인 시 변경 강제. 정책 상수는 {@link PasswordPolicy}에 모아뒀다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AccountRepository accountRepository;
    private final AccountRoleRepository accountRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final TokenBlacklist tokenBlacklist;
    private final LoginAttemptStore loginAttemptStore;
    private final LoginFailureRecorder loginFailureRecorder;
    private final Clock clock;

    @Value("${jwt.access-ttl:PT1H}")
    private Duration accessTtl;

    @Value("${jwt.refresh-ttl:P7D}")
    private Duration refreshTtl;

    /**
     * @param mustChangePassword 임시 비밀번호 상태. 앱이 비밀번호 변경 화면으로 보내야 한다
     */
    public record TokenPair(String accessToken, String refreshToken, boolean mustChangePassword) {
    }

    /**
     * 로그인.
     *
     * <p><b>실패는 세고, 성공하면 지운다.</b> {@link PasswordPolicy#MAX_LOGIN_FAILURES}회째
     * 실패에서 계정을 잠근다(A-1). 잠긴 계정은 비밀번호가 맞아도 통과시키지 않는다 —
     * 맞으면 열어주면 잠금이 의미가 없다.
     */
    @Transactional
    public TokenPair login(String loginId, String rawPassword) {
        Account account = accountRepository.findByLoginId(loginId)
                // 계정 없음과 비밀번호 틀림을 구분해서 알려주지 않는다 — 계정 존재 여부가 새어나간다
                .orElseThrow(() -> {
                    // 없는 계정도 카운트한다. 안 세면 "실패해도 안 잠기는 아이디 = 없는 아이디"가 된다
                    loginFailureRecorder.record(loginId, null);
                    return new BusinessException(ErrorCode.INVALID_CREDENTIALS);
                });

        // ★ 비밀번호 검사보다 먼저 본다. 뒤에 두면 잠긴 계정에 대해서도 비밀번호 정오답이
        //   응답으로 갈려 나가 무차별 대입의 판정 수단이 된다.
        if (account.isLocked()) {
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
        }

        if (account.getPasswordHash() == null
                || !passwordEncoder.matches(rawPassword, account.getPasswordHash())) {
            // ★ 별도 트랜잭션으로 나간다 — 여기서 잠그면 아래 예외가 그 잠금까지 롤백시킨다
            //   (LoginFailureRecorder 참고). 실제로 처음엔 그렇게 짰다가 잡았다.
            loginFailureRecorder.record(loginId, account.getId());
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        verifyLoginAllowed(account);

        loginAttemptStore.clear(loginId);
        account.recordLogin(Instant.now(clock));
        return issue(account);
    }

    /**
     * 비밀번호 변경. 임시 비밀번호 강제 변경도 이 경로를 쓴다.
     *
     * <p><b>새 토큰을 함께 돌려준다</b> — {@code mustChangePassword}가 Access Token 클레임에
     * 실려 있어서, 토큰을 갱신하지 않으면 비밀번호를 바꿨는데도 남은 1시간 동안 계속 막힌다.
     *
     * <p>기존 Refresh Token은 발급 과정에서 자동으로 회전된다. 이전 Access Token은
     * 남지만 새 토큰과 권한이 같아 실익이 없다 — 비밀번호 변경을 "모든 기기 로그아웃"으로
     * 만들려면 별도 요구사항이 필요하다(현재 없음).
     */
    @Transactional
    public TokenPair changePassword(Long accountId, String currentPassword, String newPassword) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));

        if (account.getPasswordHash() == null
                || !passwordEncoder.matches(currentPassword, account.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        // 임시 비밀번호를 그대로 다시 설정하면 강제 변경이 무의미해진다
        if (passwordEncoder.matches(newPassword, account.getPasswordHash())) {
            throw new BusinessException(ErrorCode.PASSWORD_SAME_AS_BEFORE);
        }
        PasswordPolicy.validate(newPassword);

        account.changePassword(passwordEncoder.encode(newPassword), Instant.now(clock));
        return issue(account);
    }


    /**
     * Refresh Token으로 재발급.
     *
     * <p>역할·지점은 <b>DB에서 다시 읽는다</b> — 권한이 회수됐는데 옛 토큰의 값을 그대로
     * 복사하면 회수가 무의미해진다.
     */
    @Transactional
    public TokenPair refresh(String refreshToken) {
        Long accountId;
        try {
            accountId = jwtProvider.parseRefreshSubject(refreshToken);
        } catch (JwtAuthenticationException e) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }

        // 저장된 것과 다르면 이미 회전됐거나 탈취된 토큰이다
        if (!refreshTokenStore.matches(accountId, refreshToken)) {
            log.warn("저장되지 않은 Refresh Token 사용 시도: accountId={}", accountId);
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TOKEN));
        verifyLoginAllowed(account);

        return issue(account);
    }

    /**
     * 로그아웃.
     *
     * <p>Refresh Token을 지우는 것만으로는 부족하다 — 이미 발급된 Access Token이 만료될
     * 때까지 살아 있으므로 블랙리스트에 올려 즉시 막는다.
     */
    @Transactional
    public void logout(Long accountId, String accessToken) {
        refreshTokenStore.delete(accountId);
        if (accessToken != null) {
            // 남은 수명만큼만 들고 있으면 된다 — 그 뒤엔 어차피 만료로 거부된다
            tokenBlacklist.add(accessToken, accessTtl);
        }
    }

    /** 발급 + Refresh 회전(이전 토큰은 이 시점부터 무효). */
    private TokenPair issue(Account account) {
        AuthPrincipal principal = toPrincipal(account);
        String access = jwtProvider.issueAccessToken(principal);
        String refresh = jwtProvider.issueRefreshToken(account.getId());
        refreshTokenStore.save(account.getId(), refresh, refreshTtl);
        return new TokenPair(access, refresh, account.isMustChangePassword());
    }

    private void verifyLoginAllowed(Account account) {
        // 재발급 경로에도 걸어야 한다 — 잠긴 뒤에도 들고 있던 Refresh로 계속 갱신되면
        // 잠금이 "새 로그인만 막는" 반쪽이 된다.
        if (account.isLocked()) {
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
        }
        if (account.getStatus() == AccountStatus.PENDING) {
            // 학생 가입 승인 대기. 안내 문구가 달라야 해서 별도 코드로 구분한다.
            throw new BusinessException(ErrorCode.SIGNUP_PENDING);
        }
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE);
        }
    }

    private AuthPrincipal toPrincipal(Account account) {
        Set<String> roleNames = accountRoleRepository.findRoleNamesByAccountId(account.getId());
        List<Role> roles = roleNames.stream().map(Role::from).toList();
        // 전 지점 접근은 SUPER_ADMIN만. permission.academy_scope='ALL'이 실제로 채워지면
        // 그 데이터로 판단하도록 바꿀 것 — 지금은 매트릭스가 미수령이다(Role 참고).
        boolean allAcademy = roles.contains(Role.SUPER_ADMIN);
        return AuthPrincipal.of(account.getId(), account.getAccountType().name(),
                resolveAcademyId(account), roles, allAcademy, account.isMustChangePassword());
    }

    /**
     * 소속 지점. 학부모는 자녀를 따라가므로 지점이 없다 — null이면 지점 필터가 필요한
     * 조회에서 걸러야 한다.
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
}
