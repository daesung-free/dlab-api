package com.dlab.domain.share.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
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
 * <h2>★★ 발급은 멱등하다 — 같은 대상이면 같은 토큰이 나온다</h2>
 * 누를 때마다 새 토큰이 나오면 <b>이미 보낸 알림톡의 링크가 죽는다.</b> 알림톡은 보낸 뒤
 * 본문을 바꿀 수 없어서, 10월 등록비 안내를 1,000명에게 보낸 뒤 직원이 그 공지의 「링크」
 * 버튼을 한 번 더 누르면 <b>나간 1,000통이 전부 「없는 링크」</b>가 된다. 학부모는 그걸
 * 보고 전화한다 — 없애려던 전화가 그대로 돌아온다.
 *
 * <p>그래서 <b>살아 있는 토큰이 있으면 그것을 그대로 돌려주고</b>, 없거나 만료됐을 때만
 * 새로 만든다. 제약({@code uq_public_share_target})이 대상당 살아 있는 행을 하나로 묶는다.
 *
 * <h2>⚠️ 발급 버튼이 무효화를 겸하지 않는다</h2>
 * 직원이 <b>주소를 다시 보려고</b> 누른 것과 <b>링크를 끊으려고</b> 누른 것은 구분되지
 * 않는다. 그래서 이 경로는 <b>유효한 링크를 절대 끊지 않는다</b> — 철회·강제 재발급이
 * 필요해지면 별도 경로로 둔다. 만료된 토큰을 치우는 것은 이미 죽은 링크를 정리하는
 * 것이라 여기 포함된다.
 */
@Slf4j
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

    /** 이미 있는 토큰을 재사용할 때 기한을 어떻게 할지. */
    public enum ExpiryOnReuse {
        /**
         * 처음 발급할 때 정한 기한을 그대로 둔다 — 대기자 응답 기한이 그렇다.
         * 누를 때마다 7일이 다시 붙으면 <b>기한이 사실상 없어진다.</b>
         */
        KEEP,
        /**
         * 넘긴 기한으로 옮긴다 — 안내문이 그렇다. 공지의 게시 종료를 따라가므로
         * 직원이 종료를 늘렸으면 <b>같은 토큰의 기한만</b> 옮긴다.
         */
        SYNC
    }

    /**
     * 발급 — <b>살아 있는 토큰이 있으면 그것을 돌려준다.</b>
     *
     * <p>★★ 멱등이어야 하는 이유는 클래스 주석에 있다(이미 보낸 알림톡의 링크가 죽는다).
     *
     * <p>⚠️ <b>동시에 두 번 눌렀을 때는 제약이 심판이다.</b> 사전 조회만으로는 막히지
     * 않는다 — 두 요청이 모두 「없음」을 읽고 둘 다 넣는다. 진 쪽은 이 요청만 실패하고,
     * <b>다시 누르면 이긴 쪽의 토큰이 그대로 돌아온다.</b> 새 트랜잭션 재시도를 붙이지
     * 않은 이유는 발급이 사람의 버튼 클릭이고 재시도 비용이 「한 번 더 누르기」인데,
     * 재시도를 넣으면 <b>호출부의 권한 검사까지 트랜잭션 밖으로 끌어내야</b> 하기 때문이다.
     *
     * @param expiresAt {@code null} 이면 무기한. <b>호출부가 서버 규칙으로 계산해 넘긴다</b> —
     *                  요청 본문에서 받은 값을 그대로 넘기지 말 것
     */
    @Transactional
    public PublicShareToken issueOrReuse(ShareTargetType targetType, Long targetId,
                                         Academy academy, short year, Instant expiresAt,
                                         ExpiryOnReuse onReuse) {
        Instant now = Instant.now(clock);
        Optional<PublicShareToken> existing =
                repository.findByTargetTypeAndTargetIdAndDeletedFalse(targetType, targetId);

        if (existing.isPresent()) {
            PublicShareToken token = existing.get();
            if (token.live(now)) {
                if (onReuse == ExpiryOnReuse.SYNC) {
                    token.changeExpiresAt(expiresAt);
                }
                return token;
            }
            // 만료된 토큰이다. 제약이 「살아 있는 행 하나」라 치워야 새 행이 들어간다 —
            // 이미 죽은 링크를 정리하는 것이라 유효한 링크를 끊는 일이 아니다
            token.markDeleted();
            repository.saveAndFlush(token);
        }

        try {
            return repository.saveAndFlush(new PublicShareToken(
                    targetType, targetId, newToken(), academy, year, expiresAt));
        } catch (DataIntegrityViolationException e) {
            if (!isTargetConflict(e)) {
                throw e;
            }
            // 동시에 두 번 눌렀고 우리가 졌다. 토큰은 하나만 생겼으므로 다시 누르면 된다 —
            // 이 트랜잭션은 이미 rollback-only 라 여기서 더 읽어 돌려줄 수 없다
            log.info("공유 토큰 발급 충돌: {}:{}", targetType, targetId);
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "링크 발급이 겹쳤습니다. 다시 시도해 주세요.");
        }
    }

    /**
     * 이 무결성 위반이 <b>대상당 토큰 하나</b> 제약인가.
     *
     * <p>제약 이름으로 가른다 — 예외 종류로는 못 가린다(유니크·CHECK·FK 가 모두 같은
     * 예외다). 다른 위반을 여기서 삼키면 엉뚱한 문구가 나가고 원인을 찾을 수 없다.
     */
    private boolean isTargetConflict(DataIntegrityViolationException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && message.contains("uq_public_share_target")) {
                return true;
            }
        }
        return false;
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
