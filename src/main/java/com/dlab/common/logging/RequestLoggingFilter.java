package com.dlab.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청 1건당 한 줄 로그 + 추적 ID.
 *
 * <p><b>본문(body)은 로깅하지 않는다.</b> 성적은 민감정보라 로그에 남기면 안 되고(NF-10),
 * 학생·학부모 개인정보와 비밀번호·토큰도 마찬가지다. 본문을 찍으려면 경로별 마스킹이
 * 필요한데, 마스킹 규칙을 하나라도 빠뜨리면 그때부터 계속 새어나간다 —
 * 애초에 안 찍는 쪽이 안전하다.
 *
 * <p><b>{@code /kiosk/**}를 특히 주시해야 한다.</b> 키오스크 백엔드가 관대한 폴백을 갖고 있어
 * (급식 조회 실패 시 전부 허용) 우리 장애가 그쪽 화면에서는 안 보인다.
 * 여기 남는 에러율·응답시간이 사실상 유일한 감지 수단이다(CLAUDE.md §3).
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLoggingFilter extends OncePerRequestFilter {

    public static final String TRACE_ID = "traceId";
    private static final String TRACE_HEADER = "X-Trace-Id";

    /** 이 시간을 넘으면 경고로 올린다. 키오스크 클라이언트 타임아웃이 5초라 그보다 낮게 잡는다. */
    private static final long SLOW_THRESHOLD_MS = 3_000;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String traceId = resolveTraceId(request);
        MDC.put(TRACE_ID, traceId);
        response.setHeader(TRACE_HEADER, traceId);

        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long tookMs = (System.nanoTime() - start) / 1_000_000;
            write(request, response, tookMs);
            // 스레드가 재사용되므로 반드시 지운다. 안 지우면 다음 요청에 남의 traceId가 붙는다.
            MDC.remove(TRACE_ID);
        }
    }

    private void write(HttpServletRequest request, HttpServletResponse response, long tookMs) {
        int status = response.getStatus();
        String method = request.getMethod();
        String path = request.getRequestURI();
        String query = request.getQueryString();
        String uri = query == null ? path : path + "?" + query;

        if (status >= 500) {
            log.error("{} {} -> {} ({}ms)", method, uri, status, tookMs);
        } else if (status >= 400 || tookMs >= SLOW_THRESHOLD_MS) {
            // 느린 요청은 아직 실패가 아니지만 곧 타임아웃이 된다 — 같은 수준으로 본다
            log.warn("{} {} -> {} ({}ms)", method, uri, status, tookMs);
        } else {
            log.info("{} {} -> {} ({}ms)", method, uri, status, tookMs);
        }
    }

    /**
     * 클라이언트가 보낸 추적 ID가 있으면 이어받는다.
     * 앱·웹이 자기 로그와 서버 로그를 대조할 수 있어야 장애 추적이 된다.
     */
    private String resolveTraceId(HttpServletRequest request) {
        String given = request.getHeader(TRACE_HEADER);
        if (given != null && !given.isBlank() && given.length() <= 64) {
            return given;
        }
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** 헬스체크·문서 경로는 로그를 채우기만 한다. */
    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs");
    }
}
