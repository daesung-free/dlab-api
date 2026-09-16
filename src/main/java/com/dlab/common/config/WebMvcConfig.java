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

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(passwordChangeRequiredInterceptor)
                .addPathPatterns("/api/**");
        // 계정별 메뉴 노출. 관리자 웹만 대상이다 — 앱에는 메뉴 설정 개념이 없다
        registry.addInterceptor(menuAccessInterceptor)
                .addPathPatterns("/api/v1/admin/**");
    }
}
