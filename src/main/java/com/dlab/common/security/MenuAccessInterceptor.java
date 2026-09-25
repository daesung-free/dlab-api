package com.dlab.common.security;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.menu.service.MenuAccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 계정별 메뉴 노출 설정을 서버에서도 강제한다 (0914 확정).
 *
 * <h2>★ 화면에서 감추는 것만으로는 부족하다</h2>
 * 메뉴를 숨겨도 <b>주소를 직접 치면 그대로 열린다.</b> 확정 사항이 *"계정별 설정이 서버
 * 판단의 기준이 되어야 한다"* 인 이유다.
 *
 * <h2>좁히기만 한다</h2>
 * 역할 검사({@code @PreAuthorize})가 먼저 있고 이건 그 위에 한 겹 더 좁힐 뿐이다.
 * 메뉴를 줬다고 역할에 없는 권한이 생기지는 않는다.
 *
 * <h2>왜 인터셉터인가</h2>
 * 필터에서 던진 예외는 {@code GlobalExceptionHandler} 가 잡지 못해 500 이 나간다.
 */
@Component
@RequiredArgsConstructor
public class MenuAccessInterceptor implements HandlerInterceptor {

    private final MenuAccessService menuAccessService;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof AuthPrincipal principal)) {
            return true;
        }
        if (menuAccessService.allows(principal.accountId(), request.getRequestURI())) {
            return true;
        }
        throw new BusinessException(ErrorCode.FORBIDDEN, "접근이 허용되지 않은 메뉴입니다.");
    }
}
