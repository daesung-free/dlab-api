package com.dlab.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code Authorization: Bearer <token>}을 읽어 SecurityContext를 채운다.
 *
 * <p>토큰이 없거나 깨졌으면 <b>여기서 401을 내지 않고 그냥 통과시킨다.</b>
 * 인증이 필요한지는 SecurityConfig의 경로 규칙이 판단하고, 거부 응답은
 * {@link RestAuthenticationEntryPoint}가 공통 포맷으로 만든다 — 두 군데서 401을
 * 만들면 응답 형태가 갈린다.
 *
 * <p><b>★ 블랙리스트를 여기서 대조한다.</b> 로그아웃은 Refresh를 지우고 Access를
 * {@link TokenBlacklist}에 올리는데, <b>읽는 쪽이 없으면 로그아웃해도 그 Access Token으로
 * 최대 1시간 동안 계속 호출된다</b>. 실제로 그 상태였다.
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtProvider jwtProvider;
    private final TokenBlacklist tokenBlacklist;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null) {
            // 로그아웃된 토큰은 서명·만료가 멀쩡해도 무효다. 파싱보다 먼저 걸러낸다.
            if (tokenBlacklist.contains(token)) {
                SecurityContextHolder.clearContext();
                request.setAttribute(JwtAuthenticationException.class.getName(),
                        new JwtAuthenticationException(JwtAuthenticationException.Reason.INVALID, null));
                chain.doFilter(request, response);
                return;
            }
            try {
                AuthPrincipal principal = jwtProvider.parse(token);
                // authenticated() 팩토리를 쓴다 — 3-arg 생성자는 Security 7에서 deprecated이고,
                // 자격증명 없이 인증완료 토큰을 만드는 의도가 이름에 드러난다.
                var authentication = UsernamePasswordAuthenticationToken.authenticated(
                        principal, null, principal.getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtAuthenticationException e) {
                // 만료는 흔한 정상 흐름이라 로그를 남기지 않는다. 위조만 남긴다.
                if (e.getReason() == JwtAuthenticationException.Reason.INVALID) {
                    log.warn("유효하지 않은 토큰 - {} {}", request.getMethod(), request.getRequestURI());
                }
                SecurityContextHolder.clearContext();
                request.setAttribute(JwtAuthenticationException.class.getName(), e);
            }
        }
        chain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header != null && header.startsWith(PREFIX)) {
            String value = header.substring(PREFIX.length()).trim();
            return value.isEmpty() ? null : value;
        }
        return null;
    }
}
