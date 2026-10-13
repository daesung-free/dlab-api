package com.dlab.common.config;

import com.dlab.common.security.PasswordChangeRequiredInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 인터셉터 등록.
 *
 * <p><b>{@code /api/**}에만 건다.</b> DSA 호환 구획({@code /auth/**}, {@code /kiosk/**})은
 * 응답 형식이 {@code {code, message, data}}라 {@link com.dlab.common.exception.BusinessException}이
 * 그대로 나가면 키오스크가 파싱하지 못한다(CLAUDE.md §7).
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final PasswordChangeRequiredInterceptor passwordChangeRequiredInterceptor;
    private final com.dlab.common.security.MenuAccessInterceptor menuAccessInterceptor;
    private final com.dlab.api.homepage.rest.HomepageApiKeyInterceptor homepageApiKeyInterceptor;

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(passwordChangeRequiredInterceptor)
                .addPathPatterns("/api/**");
        // 계정별 메뉴 노출. 관리자 웹만 대상이다 — 앱에는 메뉴 설정 개념이 없다
        registry.addInterceptor(menuAccessInterceptor)
                .addPathPatterns("/api/v1/admin/**");
        // ★ 홈페이지 고정 키. 본문 바인딩보다 먼저 돌아야 한다 — 메서드 안에서 확인하면
        //   본문이 틀린 요청이 키 없이도 400 을 받아, 붙이는 쪽이 인증 문제를 필수값 문제로 읽는다
        registry.addInterceptor(homepageApiKeyInterceptor)
                .addPathPatterns("/api/v1/homepage/**");
        // 감사 로그 변경 내역은 스레드에 담겨 오간다. 스레드 풀이라 요청이 끝날 때 비우지 않으면
        // 저장이 롤백된 경우 그 찌꺼기를 다음 요청이 물려받는다
        registry.addInterceptor(new org.springframework.web.servlet.HandlerInterceptor() {
            @Override
            public void afterCompletion(@NonNull jakarta.servlet.http.HttpServletRequest request,
                                        @NonNull jakarta.servlet.http.HttpServletResponse response,
                                        @NonNull Object handler, Exception ex) {
                com.dlab.domain.audit.AuditChangeInterceptor.clear();
            }
        });
    }
}
