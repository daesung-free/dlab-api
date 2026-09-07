package com.dlab.domain.audit;

import com.dlab.common.config.SecurityAuditorAware;
import com.dlab.common.security.AuthPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * "지금 누가, 어디서" — 감사 로그에 붙일 주체 정보.
 *
 * <p><b>배치·스케줄러도 기록한다.</b> 시스템 계정({@code 0})으로 남기지 않으면
 * "배치가 한 것"과 "그냥 안 남은 것"이 구분되지 않는다({@code created_by}와 같은 규칙).
 */
public final class AuditContext {

    private AuditContext() {}

    public static Long actorId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthPrincipal principal) {
            return principal.accountId();
        }
        return SecurityAuditorAware.SYSTEM_ACCOUNT_ID;
    }

    /** 계정이 지워져도 "누가"가 남아야 해서 이름도 함께 박는다. */
    public static String actorName() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthPrincipal principal) {
            return principal.accountType();
        }
        return "SYSTEM";
    }

    /**
     * 요청 IP.
     *
     * <p>프록시 뒤에 있으면 {@code X-Forwarded-For}의 <b>맨 앞</b>이 실제 클라이언트다 —
     * 뒤쪽은 거쳐온 프록시라, 그걸 쓰면 전부 같은 주소로 찍힌다.
     *
     * <p>요청 밖(배치)에서는 비어 있다.
     */
    public static String actorIp() {
        if (!(RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        var request = attributes.getRequest();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
