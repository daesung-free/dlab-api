package com.dlab.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Access Token을 읽어 SecurityContext에 주체를 심는다.
 *
 * <p>토큰이 없거나 유효하지 않아도 여기서 예외를 던지지 않는다 — 인증이 필요한 경로인지는
 * {@code SecurityConfig}가 판단하고, 거부는 EntryPoint가 처리한다. 여기서 막으면
 * permitAll 경로까지 401이 난다.
 *
 * <p>이 필터는 <b>{@code /api/v1/**} 체인에만</b> 붙는다. DSA 호환 구획(/auth/**, /kiosk/**)은
 * MD5 기반 자체 토큰을 쓰므로 JWT와 무관하다.
 */
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;
    private final TokenBlacklist blacklist;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = resolveToken(request);

        if (token != null && !blacklist.contains(token)) {
            AuthPrincipal principal = tokenProvider.parseAccessToken(token);
            if (principal != null) {
                SecurityContextHolder.getContext().setAuthentication(toAuthentication(principal));
            }
        }

        chain.doFilter(request, response);
    }

    private UsernamePasswordAuthenticationToken toAuthentication(AuthPrincipal principal) {
        List<SimpleGrantedAuthority> authorities = principal.roles().stream()
                // Spring Security의 hasRole()은 ROLE_ 접두사를 전제한다
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (StringUtils.hasText(header) && header.startsWith(PREFIX)) {
            return header.substring(PREFIX.length());
        }
        return null;
    }
}
