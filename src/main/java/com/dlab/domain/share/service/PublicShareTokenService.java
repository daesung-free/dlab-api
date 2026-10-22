package com.dlab.domain.share.service;

import com.dlab.domain.share.entity.PublicShareToken;
import com.dlab.domain.share.entity.ShareTargetType;
import com.dlab.domain.share.repository.PublicShareTokenRepository;
import com.dlab.domain.user.entity.Academy;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공유 토큰 발급·조회.
 *
 * <h2>★ 토큰은 추측 불가한 랜덤이다</h2>
 * {@link SecureRandom} 32바이트를 URL 안전 Base64 로 쓴다. <b>순번·학생 id 를 쓰면
 * 1 부터 세어 올리는 것만으로 남의 페이지가 열린다</b> — 안내문은 금액이 적혀 있고
 * 대기자 페이지는 학생 이름이 보인다.
 *
 * <p>⚠️ {@code Random} 이 아니라 {@code SecureRandom} 이다. 일반 난수는 값 몇 개를 보면
 * 다음 값이 계산되므로, 자기 링크 하나로 남의 링크를 만들 수 있게 된다.
 *
 * <h2>★ 기한은 서버가 정한다</h2>
 * 발급 요청에서 받지 않는다 — 화면마다 다른 기한을 보내면 <b>「왜 이 링크만 일찍
 * 닫혔나」를 추적할 수 없다.</b> 기본값은 여기 있다.
 *
 * <h2>★ 재발급이 이전 링크를 죽이지 않는다</h2>
 * 발송이 두 번 나갈 수 있고(재전송), 학부모는 <b>먼저 받은 알림톡을 열 수 있다.</b>
 * 이전 토큰을 폐기하면 그쪽이 「없는 링크」가 되어 전화가 온다. 같은 대상을 가리키는
 * 토큰이 여러 개 유효해도 보는 내용은 같다 — 1회 고정은 <b>토큰이 아니라 대상 쪽</b>에서
 * 지킨다.
 */
@Service
@RequiredArgsConstructor
public class PublicShareTokenService {

    /**
     * 대기자 응답 기한 기본값.
     *
     * <p>7일이다. 알림톡을 받고 집에서 상의할 시간은 줘야 하고, 그보다 길면 <b>기수가
     * 시작된 뒤에 응답이 들어온다.</b> 값을 바꾸려면 여기만 고친다 —
     * 요청 파라미터로 열면 추적이 불가능해진다.
     */
    public static final Duration WAITLIST_TTL = Duration.ofDays(7);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final PublicShareTokenRepository repository;
    private final Clock clock;

    /**
     * 발급.
     *
     * @param expiresAt {@code null} 이면 무기한. <b>호출부가 서버 규칙으로 계산해 넘긴다</b> —
     *                  요청 본문에서 받은 값을 그대로 넘기지 말 것
     */
    @Transactional
    public PublicShareToken issue(ShareTargetType targetType, Long targetId,
                                  Academy academy, short year, Instant expiresAt) {
        return repository.save(new PublicShareToken(
                targetType, targetId, newToken(), academy, year, expiresAt));
    }

    /**
     * 토큰으로 찾는다. <b>없거나 만료면 비어 있다.</b>
     *
     * <p>★ 호출부는 이게 비었을 때 <b>HTTP 200 + {@code available:false}</b> 로 내려야 한다.
     * 404 로 내리면 토큰을 두드려 보는 쪽에 「이건 있다/없다」 힌트가 된다 — 도어락
     * 공개 경로가 같은 이유로 거절도 200 이다.
     */
    @Transactional(readOnly = true)
    public Optional<PublicShareToken> resolve(String token, ShareTargetType targetType) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Instant now = Instant.now(clock);
        return repository.findByTokenAndTargetType(token, targetType)
                .filter(t -> t.live(now));
    }

    /**
     * 32바이트 랜덤.
     *
     * <p>중복을 한 번 확인한다 — 겹칠 확률은 사실상 0 이지만, 겹치면 <b>유니크 제약으로
     * 발급이 실패</b>해 알림톡 발송이 막힌다. 확인 한 번이 그보다 싸다.
     */
    private String newToken() {
        for (int attempt = 0; attempt < 5; attempt++) {
            byte[] bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            String candidate = ENCODER.encodeToString(bytes);
            if (!repository.existsByToken(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("공유 토큰 생성에 실패했습니다.");
    }
}
