package com.dlab.domain.appconfig.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.appconfig.entity.NotificationPreference;
import com.dlab.domain.appconfig.entity.Platform;
import com.dlab.domain.appconfig.entity.PushToken;
import com.dlab.domain.appconfig.repository.NotificationPreferenceRepository;
import com.dlab.domain.appconfig.repository.PushTokenRepository;
import com.dlab.domain.notification.entity.NotificationEvent;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 알림 수신 설정 · FCM 토큰 (A-21).
 *
 * <p><b>발송은 아직 못 한다</b> — Firebase 프로젝트·APNs 키(E-7)와 알림톡 발신프로필(E-5)이
 * 없다. 다만 <b>수신 동의와 토큰 저장은 지금 해둬야</b> E-7이 풀렸을 때 바로 보낼 수 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppNotificationSettingService {

    /**
     * 수신 거부할 수 없는 알림.
     *
     * <p>미등원은 <b>학생이 학원에 오지 않았다는 통지</b>라 학부모가 끌 수 있으면 안 된다.
     * 알림톡으로 격상한 것도 같은 이유이고, SMS 폴백이 폐기(E-5)돼 대체 수단도 없다.
     */
    private static final Set<NotificationEvent> MANDATORY =
            EnumSet.of(NotificationEvent.MISSING_ATTENDANCE);

    private final NotificationPreferenceRepository preferenceRepository;
    private final PushTokenRepository pushTokenRepository;
    private final AccountRepository accountRepository;
    private final Clock clock;

    /**
     * @param mandatory 수신 거부 불가 여부. 앱이 토글을 비활성으로 그려야 한다 —
     *                  누를 수 있는데 서버가 거절하면 사용자는 고장으로 본다
     */
    public record PreferenceView(NotificationEvent event, boolean enabled, boolean mandatory) {
    }

    /**
     * 유형별 수신 설정 전체.
     *
     * <p><b>행이 없으면 수신(on)</b>이므로 전 이벤트를 나열하고 저장된 값만 덮어씌운다 —
     * 저장된 것만 돌려주면 앱이 목록을 못 그린다.
     */
    @Transactional(readOnly = true)
    public List<PreferenceView> preferences(Long accountId) {
        Map<NotificationEvent, Boolean> saved = preferenceRepository.findByAccountId(accountId)
                .stream()
                .collect(Collectors.toMap(NotificationPreference::getEventCode,
                        NotificationPreference::isEnabled));

        return java.util.Arrays.stream(NotificationEvent.values())
                .map(event -> new PreferenceView(
                        event,
                        saved.getOrDefault(event, true),
                        MANDATORY.contains(event)))
                .toList();
    }

    @Transactional
    public void changePreference(Long accountId, NotificationEvent event, boolean enabled) {
        if (!enabled && MANDATORY.contains(event)) {
            throw new BusinessException(ErrorCode.REQUIRED_NOTIFICATION_CANNOT_BE_DISABLED);
        }
        preferenceRepository.findByAccountIdAndEventCode(accountId, event)
                .ifPresentOrElse(
                        preference -> preference.change(enabled),
                        () -> preferenceRepository.save(new NotificationPreference(
                                requireAccount(accountId), event, enabled)));
    }

    /**
     * 이 계정이 해당 알림을 받는가 — 발송 직전에 물어볼 자리.
     *
     * <p>필수 알림은 설정과 무관하게 항상 {@code true}다.
     */
    @Transactional(readOnly = true)
    public boolean isEnabled(Long accountId, NotificationEvent event) {
        if (MANDATORY.contains(event)) {
            return true;
        }
        return !preferenceRepository.existsByAccountIdAndEventCodeAndEnabledFalse(accountId, event);
    }

    /**
     * FCM 토큰 등록·갱신.
     *
     * <p><b>★ 같은 토큰이 다른 계정으로 올라올 수 있다.</b> 기기 하나를 두 사람이 쓰면
     * (로그아웃 후 다른 계정 로그인) 같은 토큰이 재등록된다. 계정별로 쌓기만 하면
     * <b>이전 사용자에게 계속 알림이 가고</b>, 학부모 계정이라면 남의 자녀 출결이 뜬다.
     * 그래서 토큰 기준으로 찾아 <b>소유 계정을 갈아끼운다.</b>
     */
    @Transactional
    public PushToken registerToken(Long accountId, String token, Platform platform) {
        Instant now = Instant.now(clock);
        return pushTokenRepository.findByTokenAndDeletedFalse(token)
                .map(existing -> {
                    if (!existing.getAccount().getId().equals(accountId)) {
                        log.info("FCM 토큰 소유 계정 변경: {} → {}",
                                existing.getAccount().getId(), accountId);
                    }
                    existing.refresh(requireAccount(accountId), platform, now);
                    return existing;
                })
                .orElseGet(() -> pushTokenRepository.save(
                        new PushToken(requireAccount(accountId), token, platform, now)));
    }

    /**
     * 토큰 해제 (로그아웃·앱 삭제).
     *
     * <p>물리 삭제하지 않는다 — 만료 단말 목록(F-4.12-2)에서 "언제부터 안 쓰였나"를 봐야 한다.
     */
    @Transactional
    public void removeToken(String token) {
        pushTokenRepository.findByTokenAndDeletedFalse(token)
                .ifPresent(PushToken::markDeleted);
    }

    /** 발송 대상 단말. 한 계정에 기기 여러 대가 붙을 수 있다. */
    @Transactional(readOnly = true)
    public List<PushToken> tokensOf(Long accountId) {
        return pushTokenRepository.findByAccountIdAndDeletedFalse(accountId);
    }

    private Account requireAccount(Long accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
    }
}
