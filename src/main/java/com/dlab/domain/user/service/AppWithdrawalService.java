package com.dlab.domain.user.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.RefreshTokenStore;
import com.dlab.common.security.TokenBlacklist;
import com.dlab.domain.appconfig.repository.NotificationPreferenceRepository;
import com.dlab.domain.appconfig.repository.PushTokenRepository;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.AccountType;
import com.dlab.domain.user.repository.AccountRepository;
import com.dlab.domain.user.repository.StudentGuardianLinkRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

/**
 * 앱 회원 탈퇴 (App Store 5.1.1(v) · Google Play 필수 요건).
 *
 * <h2>왜 급한가</h2>
 * <b>가입이 있는 앱은 앱 안에서 계정 삭제를 요청할 수 있어야 한다.</b> 없으면 심사에서
 * 반려된다 — 앱이 이미 {@code DELETE /app/me} 를 부르고 있는데 서버에 그 경로가 없었다.
 *
 * <h2>★ 「지금 끊는 것」과 「나중에 지우는 것」을 나눈다</h2>
 * 스토어가 요구하는 것은 <b>삭제를 요청할 수 있는 경로</b>이고, 법·계약상 보존 의무가 있는
 * 기록은 유지할 수 있다. 그래서 탈퇴하면 <b>즉시</b>
 * <ul>
 *   <li>로그인이 영구 차단되고(들고 있던 토큰도 그 자리에서 무효)</li>
 *   <li><b>앱이 수집한 것</b> — 푸시 토큰·알림 수신 설정 — 이 지워지고</li>
 *   <li>학부모면 <b>자녀 연결이 끊긴다</b></li>
 * </ul>
 * 반면 <b>원생 기록(출결·수납·상벌점)은 남긴다.</b> 학원이 계약·학원법 근거로 보유하는
 * 것이고, 같이 지우면 <b>지점 정산과 과거 통계가 소급해서 바뀐다.</b>
 * 보관기간(퇴원 후 1년) 경과분 파기는 <b>정책 확정 후 배치</b>로 붙인다 — 그 답을 기다리면
 * 스토어 제출이 막히므로 여기서 묶지 않는다.
 *
 * <h2>★ 학부모 탈퇴는 자녀 연결을 끊는다</h2>
 * 연결을 남기면 <b>탈퇴한 사람이 승인자이자 알림 수신처로 남는다</b> — 학생이 방화벽·사유를
 * 신청하면 아무도 받지 않는 승인 요청이 10분씩 대기한다. 끊으면 지점 정책·담임 경로로
 * 흘러가고, 다시 연결하려면 <b>새로 가입해 학생 고유ID를 넣으면 된다</b>(원래 그 흐름이다).
 *
 * <h2>직원·강사는 이 경로를 쓸 수 없다</h2>
 * 관리자 계정 해지는 인사 절차라 <b>본인이 누르는 일이 아니다.</b> 직원 계정은
 * {@code AdminStaffController} 쪽에서 관리자가 처리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppWithdrawalService {

    private final AccountRepository accountRepository;
    private final PushTokenRepository pushTokenRepository;
    private final NotificationPreferenceRepository preferenceRepository;
    private final StudentGuardianLinkRepository guardianLinkRepository;
    private final RefreshTokenStore refreshTokenStore;
    private final TokenBlacklist tokenBlacklist;
    private final Clock clock;

    @Value("${jwt.access-token-ttl:30m}")
    private Duration accessTtl;

    /**
     * 탈퇴.
     *
     * <p><b>멱등하다.</b> 이미 탈퇴한 계정이면 아무 일도 하지 않고 그대로 돌려준다 —
     * 앱이 재시도하거나 두 번 누를 수 있고, 두 번째가 실패하면 화면은 "탈퇴가 안 됐다"로
     * 보이는데 실제로는 이미 된 상태다.
     *
     * @param accessToken 지금 들고 있는 토큰. 블랙리스트에 넣어 <b>그 자리에서</b> 막는다.
     *                    없으면 만료까지 살아 있어 탈퇴 후에도 조회가 된다
     * @return 무엇을 지웠는지 — 화면이 안내 문구를 고르는 근거다
     */
    @Transactional
    public Result withdraw(Long accountId, String reason, String accessToken) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));

        if (account.getAccountType() != AccountType.STUDENT
                && account.getAccountType() != AccountType.PARENT) {
            // 직원·강사 계정 해지는 인사 절차다. 본인이 누르는 경로를 열면 되돌리기 어렵다
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "직원 계정은 앱에서 탈퇴할 수 없습니다. 관리자에게 문의해 주세요.");
        }
        if (account.isWithdrawnByOwner()) {
            return new Result(true, 0, 0, 0);
        }

        Instant now = Instant.now(clock);
        account.withdrawByOwner(now, blankToNull(reason));

        // 앱이 수집한 것부터 지운다 — 탈퇴한 사람에게 알림이 가는 것이 가장 먼저 보인다
        int tokens = removePushTokens(accountId);
        int preferences = clearPreferences(accountId);
        int children = unlinkChildren(account);

        // 들고 있던 토큰을 즉시 무효화한다. Refresh 를 안 지우면 갱신으로 계속 들어온다
        refreshTokenStore.delete(accountId);
        if (accessToken != null) {
            tokenBlacklist.add(accessToken, accessTtl);
        }

        log.info("앱 회원 탈퇴: accountId={}, 유형={}, 푸시토큰={}건, 알림설정={}건, 자녀연결={}건",
                accountId, account.getAccountType(), tokens, preferences, children);

        return new Result(false, tokens, preferences, children);
    }

    private int removePushTokens(Long accountId) {
        var tokens = pushTokenRepository.findByAccountIdAndDeletedFalse(accountId);
        tokens.forEach(com.dlab.domain.appconfig.entity.PushToken::markDeleted);
        return tokens.size();
    }

    /**
     * 알림 수신 설정을 지운다.
     *
     * <p>끄는 것이 아니라 <b>행을 지운다.</b> 꺼둔 상태로 남기면 재가입했을 때 예전 선택이
     * 따라붙는데, 그건 <b>다른 사람일 수도 있는</b> 계정이다(같은 번호를 다른 사람이 쓴다).
     */
    private int clearPreferences(Long accountId) {
        var preferences = preferenceRepository.findByAccountId(accountId);
        preferenceRepository.deleteAll(preferences);
        return preferences.size();
    }

    /** 학부모면 자녀 연결을 끊는다. 학생 계정은 끊을 것이 없다(연결의 주체가 학부모다). */
    private int unlinkChildren(Account account) {
        if (account.getAccountType() != AccountType.PARENT || account.getGuardian() == null) {
            return 0;
        }
        var links = guardianLinkRepository.findChildrenOf(account.getGuardian().getId());
        guardianLinkRepository.deleteAll(links);
        return links.size();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * @param alreadyWithdrawn 이미 탈퇴한 계정이었는가. 화면이 "이미 처리됐습니다"를 고른다
     * @param unlinkedChildren 끊은 자녀 연결 수. 학부모 탈퇴에만 0보다 크다
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "WithdrawalResult")
    public record Result(boolean alreadyWithdrawn, int removedPushTokens,
                         int clearedPreferences, int unlinkedChildren) {
    }
}
