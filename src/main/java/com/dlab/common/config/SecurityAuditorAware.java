package com.dlab.common.config;

import com.dlab.common.security.AuthPrincipal;
import java.util.Optional;
import org.springframework.data.domain.AuditorAware;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * {@code created_by} 자동 주입. 엔티티를 저장할 때 Spring Data가 "지금 누가 하고 있나"를
 * 여기에 묻는다.
 *
 * <p><b>서비스마다 수동으로 채우면 반드시 빠뜨린다.</b> 그리고 감사로그는 나중에 붙여도
 * 그 이전 기간을 복구할 수 없다 — "누가 이 학생 벌점을 지웠나"에 답할 수 없게 된다.
 *
 * <p><b>배치·스케줄러는 {@link #SYSTEM_ACCOUNT_ID}를 쓴다.</b> null로 두면
 * "배치가 했다"와 "그냥 빠뜨렸다"가 구분되지 않는다. 미등원 알림·결석 확정처럼
 * 사람 없이 도는 경로가 실제로 있다.
 */
@Component
public class SecurityAuditorAware implements AuditorAware<Long> {

    /**
     * 시스템(배치·스케줄러) 계정. 실제 계정 테이블에는 없는 예약값이다 —
     * {@code account.id}는 {@code BIGSERIAL}이라 1부터 시작해 0과 겹치지 않는다.
     */
    public static final Long SYSTEM_ACCOUNT_ID = 0L;

    @Override
    @NonNull
    public Optional<Long> getCurrentAuditor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.of(SYSTEM_ACCOUNT_ID);
        }
        if (authentication.getPrincipal() instanceof AuthPrincipal principal) {
            return Optional.of(principal.accountId());
        }
        // 익명 사용자(anonymousUser) 등 우리 주체가 아닌 경우.
        // 인증 없이 도는 경로(DSA 호환 구획·배치)가 여기로 온다.
        return Optional.of(SYSTEM_ACCOUNT_ID);
    }
}
