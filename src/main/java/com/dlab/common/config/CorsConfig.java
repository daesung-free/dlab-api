package com.dlab.common.config;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * CORS 설정.
 *
 * <h2>어디에 적용되나</h2>
 * <b>{@code /api/**} 구획에만 적용된다.</b> Spring Security 체인이 세 개인데
 * {@code apiFilterChain}만 {@code .cors(...)}를 켠다(CorsConfig 빈이 있어도 켜지 않은
 * 체인에는 적용되지 않는다).
 *
 * <p><b>DSA 호환 구획({@code /auth/**}, {@code /kiosk/**})에는 일부러 걸지 않는다.</b>
 * 키오스크는 브라우저가 아니라 CORS 자체가 무의미하고, 열어두면 노출 표면만 늘어난다.
 *
 * <h2>왜 origin을 설정으로 빼나</h2>
 * 운영 도메인이 아직 없고(구매 전), 로컬 개발 포트는 프론트 개발 서버가 정한다.
 * 코드에 박으면 도메인이 정해질 때 <b>재배포</b>가 필요해진다 —
 * 서버 {@code .env}에 {@code CORS_ALLOWED_ORIGINS} 한 줄을 넣고 재기동하면 되게 한다.
 *
 * <h2>왜 allowedOriginPatterns 인가</h2>
 * {@code allowedOrigins}는 정확히 일치하는 문자열만 받는다. 그런데 Vite는 5173이 이미
 * 쓰이면 5174로 올라가고, 그때마다 서버 설정을 고쳐야 한다. 패턴이면
 * {@code http://localhost:*} 하나로 끝난다.
 *
 * <h2>왜 allowCredentials 는 false 인가</h2>
 * 인증을 쿠키가 아니라 {@code Authorization: Bearer} 헤더로 한다. 쿠키를 안 쓰므로
 * 켤 이유가 없고, <b>켜면 브라우저가 와일드카드 origin을 거부</b>해서 위 패턴 방식과
 * 같이 쓰기 까다로워진다. 나중에 쿠키 기반으로 바꾸면 그때 함께 검토할 것.
 */
@Slf4j
@Configuration
public class CorsConfig {

    /**
     * 기본값은 로컬 개발용이다. 운영에서는 {@code CORS_ALLOWED_ORIGINS} 환경변수로 덮는다.
     * (application-prod.yml이 기본값 없이 다시 선언하므로, 운영에서 값을 안 주면 비어 있게 된다)
     */
    private final List<String> allowedOrigins;

    public CorsConfig(@Value("${cors.allowed-origins:}") List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        if (allowedOrigins.isEmpty()) {
            // 조용히 전부 차단되면 프론트에서는 원인 불명의 CORS 오류로만 보인다.
            // 기동 로그에 남겨서 "설정을 안 넣었다"는 것이 드러나게 한다.
            log.warn("CORS 허용 origin이 비어 있습니다. 브라우저에서 오는 /api 요청은 전부 차단됩니다. "
                    + "CORS_ALLOWED_ORIGINS 환경변수를 확인하세요.");
        } else {
            log.info("CORS 허용 origin: {}", allowedOrigins);
        }

        config.setAllowedOriginPatterns(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // Authorization·Content-Type 등을 일일이 나열하면 헤더 하나 추가할 때마다 여기가 막힌다.
        config.setAllowedHeaders(List.of("*"));
        // 쿠키를 쓰지 않는다(위 주석 참고).
        config.setAllowCredentials(false);
        // preflight 결과를 1시간 캐시한다. 없으면 요청마다 OPTIONS가 한 번씩 더 나간다.
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // ★ /** 가 아니라 /api/** 다. 이 소스는 CORS를 켠 체인에서만 쓰이지만,
        //   경로까지 좁혀두면 나중에 다른 체인이 실수로 .cors()를 켜도 범위가 새지 않는다.
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
