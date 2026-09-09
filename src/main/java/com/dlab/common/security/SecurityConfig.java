package com.dlab.common.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 보안 설정. <b>필터 체인을 두 개로 나눈다</b> — 인증 체계가 서로 완전히 다르기 때문이다.
 *
 * <table>
 *   <tr><th>구획</th><th>경로</th><th>인증</th><th>응답</th></tr>
 *   <tr><td>DSA 호환</td><td>{@code /auth/**}, {@code /kiosk/**}</td>
 *       <td>본문 {@code token} 필드 + MD5(yyyyMMdd+secret)</td>
 *       <td>{@code {code, message, data}}</td></tr>
 *   <tr><td>앱·관리자</td><td>{@code /api/v1/**}</td>
 *       <td>{@code Authorization: Bearer} JWT</td>
 *       <td>{@code ApiResponse}</td></tr>
 * </table>
 *
 * <p>DSA 호환 구획을 Spring Security로 인증하면 안 된다 — 키오스크는 헤더가 아니라
 * <b>본문</b>에 토큰을 싣고, 실패 응답도 401이 아니라 {@code code:910}이라야 한다.
 * 그래서 이 체인은 통과만 시키고 검증은 해당 컨트롤러·인터셉터가 직접 한다.
 *
 * <p>순서가 중요하다. {@code @Order(1)} 체인이 먼저 매칭되므로 DSA 구획을 위에 둔다.
 */
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtProvider jwtProvider;
    private final TokenBlacklist tokenBlacklist;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    /**
     * DSA 호환 구획. 상대(키오스크·홈페이지)가 경로를 하드코딩하고 있어 {@code /api/v1}
     * prefix를 붙일 수 없다 (CLAUDE.md §5 경로 규칙의 명시적 예외).
     *
     * <p>{@code /auth2}·{@code /dlab}은 홈페이지 입학예약이다 — 키오스크와 <b>토큰이
     * 서로 통하지 않는다.</b> 검증은 각 구획의 서비스가 본문 token으로 직접 한다.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain dsaCompatFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/auth/**", "/kiosk/**", "/auth2/**", "/dlab/**")
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                // 인증은 이 구획 전용 로직이 본문 token으로 직접 수행한다.
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    /** 앱·관리자 웹 구획. */
    @Bean
    @Order(2)
    public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/api/**")
                // ★ 이 체인에서만 CORS를 켠다. 허용 origin은 CorsConfig가 설정에서 읽는다.
                //   이 줄이 없으면 preflight(OPTIONS)를 permitAll로 통과시켜도
                //   Access-Control-* 응답 헤더가 붙지 않아 브라우저가 그대로 차단한다 —
                //   "설정했는데 왜 안 되지"로 헤매기 쉬운 지점이다.
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .authorizeHttpRequests(auth -> auth
                        // 로그인·토큰재발급은 인증 전에 호출된다
                        .requestMatchers("/api/v1/app/auth/**").permitAll()
                        .requestMatchers("/api/v1/admin/auth/**").permitAll()
                        // 가입은 토큰이 생기기 전에 호출된다(휴대폰 인증 → 가입)
                        .requestMatchers("/api/v1/app/signup/**").permitAll()
                        // ★ 앱 부팅 첫 호출. 점검 중이거나 강제 업데이트가 필요한지는
                        //   로그인 전에 판단해야 하고, 로그인 API 자체가 점검으로 막혔을 수도 있다
                        .requestMatchers("/api/v1/app/app-config").permitAll()
                        // 외부 시스템 수신(키오스크 제외)은 자체 서명검증을 한다
                        .requestMatchers("/api/v1/webhook/**").permitAll()
                        // ★ 키오스크 신규 구획. 우리 JWT가 아니라 DSA 호환 토큰으로 인증한다 —
                        //   키오스크가 이미 그 토큰을 갖고 있고, JWT를 쓰게 하면 키오스크에
                        //   로그인 흐름을 새로 만들어야 한다. 검증은 컨트롤러가
                        //   DsaTokenService.resolveAcademyId로 직접 하므로 무인증이 아니다
                        .requestMatchers("/api/v1/kiosk/**").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthenticationFilter(jwtProvider, tokenBlacklist),
                        UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .build();
    }

    /**
     * 그 외 경로(액추에이터·정적자원·문서). 기본 잠금을 풀어두지 않으면
     * springdoc 붙일 때 문서 페이지까지 막힌다.
     *
     * <h2>★ 액추에이터는 예외다 — health만 연다</h2>
     * 이 체인이 {@code permitAll}이라 <b>액추에이터가 그대로 인터넷에 노출된다.</b>
     * 노출 목록({@code management.endpoints.web.exposure.include})으로도 막고 있지만,
     * 설정 한 줄이 바뀌면 그대로 뚫리는 구조라 <b>여기서도 막는다</b> — 설정과 코드
     * 양쪽이 같이 틀려야 열리게 한다.
     *
     * <p>{@code /actuator/health/kiosk}는 열어야 한다. 키오스크는 우리 장애를 알려주지
     * 않으므로(그쪽 폴백이 가린다) <b>바깥 모니터가 주기적으로 찔러야</b> 하는데,
     * 인증을 걸면 그 모니터에 자격증명을 심어야 한다.
     */
    @Bean
    @Order(3)
    public SecurityFilterChain defaultFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .authorizeHttpRequests(auth -> auth
                        // 외부 모니터가 찔러볼 창구. 상세 노출 범위는 프로필이 정한다
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // env·beans·heapdump 등. 열리면 DB 접속정보까지 나간다
                        .requestMatchers("/actuator/**").denyAll()
                        .anyRequest().permitAll())
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
