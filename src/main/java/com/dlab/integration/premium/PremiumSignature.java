package com.dlab.integration.premium;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 더프리미엄(대성전산) REST API 서명.
 *
 * <h2>★★ 서명한 문자열과 실제 요청 URL 이 바이트 단위로 같아야 한다</h2>
 * 연동 정의서가 못 박은 항목이다 — <i>"파라미터를 dict/Map 으로 넘기면 순서가 바뀌거나
 * 인코딩이 달라져 서명이 깨집니다. <b>URL 문자열을 먼저 만들고, 그 문자열로 서명하고,
 * 그 문자열로 요청하세요.</b>"</i>
 *
 * <p>그래서 이 클래스는 <b>완성된 경로 문자열만 받는다.</b> 파라미터를 받아 조립해 주면
 * 쓰는 쪽이 {@code RestClient.uri()} 빌더를 함께 쓰게 되고, 빌더가 인코딩을 바꾸는 순간
 * <b>401 이 나는데 원인이 서명인지 키인지 시계인지 구분되지 않는다.</b>
 *
 * <h2>서명 대상</h2>
 * <pre>
 * {timestamp}:{METHOD}:{경로+쿼리}
 * 1755590400:GET:/api/students/report?examdt=20260417&amp;schoolId=99700&amp;...
 * </pre>
 * <ul>
 *   <li><b>timestamp 는 UTC epoch 「초」</b>다 — 밀리초를 넣으면 전부 거부된다</li>
 *   <li><b>METHOD 는 대문자</b></li>
 *   <li><b>호스트·포트를 넣지 않는다</b> — {@code /api} 부터다</li>
 * </ul>
 *
 * <h2>⚠️ 매 요청마다 새로 만든다</h2>
 * 정의서: <i>"캐싱하면 5분 뒤 전부 401 입니다."</i> 서버 시계와 <b>±5분</b>을 넘으면
 * 거부되므로, 서명을 재사용하면 그 창이 지나는 순간 전부 실패한다.
 *
 * <p>⚠️ 반대로 <b>응답을 우리 DB 에 적재하는 것은 막는 게 아니다</b> — 서명값 재사용
 * 금지일 뿐이다. 반대로 읽으면 화면 진입마다 재조회하는 구조를 만들게 된다.
 */
public final class PremiumSignature {

    private static final String ALGORITHM = "HmacSHA256";

    private PremiumSignature() {
    }

    /**
     * 서명 헤더 세 개를 만든다.
     *
     * @param pathWithQuery <b>실제로 요청에 쓸 것과 완전히 같은</b> 경로+쿼리.
     *                      {@code /api} 로 시작해야 한다
     */
    public static Headers sign(String secretKey, String method, String pathWithQuery,
                               Clock clock) {
        if (pathWithQuery == null || !pathWithQuery.startsWith("/api")) {
            // 호스트를 포함해 넘기는 실수가 가장 흔하다. 그러면 서명만 조용히 어긋난다
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "서명 대상 경로는 /api 로 시작해야 합니다 — 호스트를 넣지 마세요.");
        }
        String timestamp = String.valueOf(clock.instant().getEpochSecond());
        String message = timestamp + ":" + method.toUpperCase() + ":" + pathWithQuery;
        return new Headers(timestamp, hmacHex(secretKey, message));
    }

    static String hmacHex(String secretKey, String message) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC 서명 생성 실패", e);
        }
    }

    /**
     * @param timestamp {@code X-TIMESTAMP}
     * @param signature {@code X-SIGNATURE}. 소문자 16진수
     */
    public record Headers(String timestamp, String signature) {
    }
}
