package com.dlab.api.homepage.rest;

import com.dlab.common.config.HomepageProperties;
import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 홈페이지 고정 키 확인 — <b>본문을 읽기 전에</b> 막는다.
 *
 * <h2>왜 컨트롤러 안에서 하지 않는가</h2>
 * {@code @Valid @RequestBody} 검증이 <b>메서드 본문보다 먼저</b> 돈다. 그래서 키 확인을
 * 메서드 안에 두면, 본문이 비어 있거나 틀린 요청은 <b>키가 없어도 400</b>이 나갔다 —
 * 붙이는 쪽은 <b>인증 문제를 필수값 문제로 오해</b>한다. 실제로 실서버에서 그렇게 나왔다.
 *
 * <p>인터셉터는 바인딩 전에 돌아 <b>키가 없으면 무조건 401</b>이다. 그리고 경로 전체에
 * 한 번만 걸리므로, 엔드포인트를 더 만들 때 <b>키 확인을 빠뜨릴 여지가 없다</b> —
 * 메서드마다 호출하는 방식은 새 메서드에서 잊으면 그대로 열린다.
 */
@Component
@RequiredArgsConstructor
public class HomepageApiKeyInterceptor implements HandlerInterceptor {

    private final HomepageProperties properties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        // CORS 사전 요청에는 헤더가 실리지 않는다 — 막으면 브라우저 호출이 통째로 깨진다
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String expected = properties.getApiKey();
        if (expected == null || expected.isBlank()) {
            // ★ 키가 설정돼 있지 않으면 전부 거부한다. 조용히 통과시키면 키를 안 넣은
            //   서버에서 지원자 정보가 무인증으로 열린다
            throw new BusinessException(ErrorCode.UNAUTHORIZED,
                    "홈페이지 연동 키가 설정되지 않았습니다.");
        }

        String presented = presentedKey(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (presented == null || !expected.equals(presented)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return true;
    }

    private static String presentedKey(String authorization) {
        if (authorization == null) {
            return null;
        }
        String token = authorization.replaceFirst("(?i)^Bearer\\s+", "").trim();
        return token.isEmpty() ? null : token;
    }
}
