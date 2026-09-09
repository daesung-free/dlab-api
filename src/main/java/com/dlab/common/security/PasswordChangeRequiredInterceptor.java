package com.dlab.common.security;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * 임시 비밀번호 상태에서 비밀번호 변경 외 모든 API를 막는다
 * (앱 요구사항 A-1 "임시 비밀번호 최초 로그인 시 변경 강제").
 *
 * <p><b>왜 필터가 아니라 인터셉터인가</b> — 필터에서 예외를 던지면
 * {@code ExceptionTranslationFilter}보다 앞이라 {@link com.dlab.common.exception.GlobalExceptionHandler}가
 * 잡지 못하고 500이 나간다. 인터셉터는 DispatcherServlet 안이라 {@link BusinessException}이
 * 그대로 {@code ApiResponse} 실패 형식으로 변환된다.
 *
 * <p><b>앱이 알아서 라우팅하게 두지 않는 이유</b> — 클라이언트만 믿으면 앱을 우회한
 * 직접 호출로 임시 비밀번호 상태에서 모든 API를 쓸 수 있다. "강제"는 서버가 보장해야 한다.
 */
@Component
public class PasswordChangeRequiredInterceptor implements HandlerInterceptor {

    /**
     * 임시 비밀번호 상태에서도 허용되는 경로.
     *
     * <p>비밀번호를 바꾸려면 <b>지금 쓰는 비밀번호로 로그인한 상태</b>여야 하므로 변경 API는
     * 열려 있어야 하고, 잘못 들어온 사용자가 빠져나갈 수 있게 로그아웃도 열어둔다.
     */
    private static final Set<String> ALLOWED = Set.of(
            "/api/v1/app/auth/password",
            "/api/v1/app/auth/logout",
            "/api/v1/admin/auth/password",
            "/api/v1/admin/auth/logout",
            // ★ 비밀번호 변경 화면에도 헤더가 있다. 막으면 그 화면에서
            //   "누가 로그인했는지"를 못 읽어 빈 이름으로 뜬다
            "/api/v1/admin/auth/me"
    );

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        // 프리플라이트는 인증 헤더를 싣지 않는다
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof AuthPrincipal principal)
                || !principal.mustChangePassword()) {
            return true;
        }
        if (ALLOWED.contains(request.getRequestURI())) {
            return true;
        }
        throw new BusinessException(ErrorCode.PASSWORD_CHANGE_REQUIRED);
    }
}
