package com.dlab.common.security;

import com.dlab.common.exception.ErrorCode;
import com.dlab.common.response.ApiResponse;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Security 설정.
 *
 * <p><b>★ 체인을 경로별로 나눈다.</b> 이 서버는 인증 체계가 두 개다 —
 * 앱·웹은 JWT, DSA 호환 구획(키오스크)은 MD5 기반 자체 토큰이다.
 *
 * <p><b>모든 {@code SecurityFilterChain}은 반드시 {@code securityMatcher}로 자기 경로를
 * 한정하고 {@code @Order}를 붙일 것.</b> 한정 없는 체인이 하나라도 있으면 그게 전체 요청을
 * 먹어버려 다른 체인이 아예 동작하지 않는다. DSA 호환 구획 담당자도 같은 규칙으로
 * 자기 체인을 별도 파일에 추가하면 이 파일을 건드릴 필요가 없다.
 */
@Configuration
@RequiredArgsConstructor
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    private final JwtTokenProvider tokenProvider;
    private final TokenBlacklist tokenBlacklist;
    private final ObjectMapper objectMapper;

    /**
     * 앱·웹 API 체인.
     *
     * <p>DSA 호환 구획(/auth/**, /kiosk/**)은 여기 걸리지 않는다 — 그쪽은 담당자가
     * 별도 체인으로 등록한다.
     */
    @Bean
    @Order(100)
    public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/v1/**")
                .csrf(csrf -> csrf.disable())
                // 토큰 기반이라 세션을 만들지 않는다
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .authorizeHttpRequests(auth -> auth
                        // 로그인·재발급은 토큰이 없는 상태로 들어온다
                        .requestMatchers("/api/v1/app/auth/**", "/api/v1/admin/auth/**").permitAll()
                        // 나머지는 인증 필수. role 체크는 컨트롤러에서 @PreAuthorize로 건다
                        // — 여기서 경로별로 role을 나열하면 경로가 바뀔 때마다 두 곳을 고쳐야 한다.
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthenticationFilter(tokenProvider, tokenBlacklist),
                        UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> write(res, ErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler((req, res, e) -> write(res, ErrorCode.FORBIDDEN)));

        return http.build();
    }

    /** 인증 실패·권한 부족도 공통 응답 형식으로 내보낸다(CLAUDE.md §7). */
    private void write(jakarta.servlet.http.HttpServletResponse response, ErrorCode errorCode)
            throws java.io.IOException {
        response.setStatus(errorCode.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.fail(errorCode));
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
