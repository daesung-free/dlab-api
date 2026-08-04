package com.dlab.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * {@code created_by} 자동 주입. <b>비어 있으면 "누가 이 학생 벌점을 지웠나"에 답할 수 없다</b> —
 * 보안심사 직결 항목이고 나중에 붙여도 과거는 복구되지 않는다.
 */
class SecurityAuditorAwareTest {

    private final SecurityAuditorAware auditorAware = new SecurityAuditorAware();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("로그인한 사용자의 계정 id가 주입된다")
    void usesAuthenticatedAccountId() {
        AuthPrincipal principal = AuthPrincipal.of(
                42L, "EMPLOYEE", 7L, List.of(Role.BRANCH_ADMIN), false);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        principal, null, principal.getAuthorities()));

        assertThat(auditorAware.getCurrentAuditor()).contains(42L);
    }

    @Test
    @DisplayName("★ 인증이 없으면 시스템 계정 — null로 두면 '배치가 함'과 '빠뜨림'이 구분 안 된다")
    void unauthenticatedUsesSystemAccount() {
        assertThat(auditorAware.getCurrentAuditor())
                .contains(SecurityAuditorAware.SYSTEM_ACCOUNT_ID);
    }

    @Test
    @DisplayName("익명 사용자도 시스템 계정으로 기록된다 — DSA 호환 구획이 여기로 온다")
    void anonymousUsesSystemAccount() {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThat(auditorAware.getCurrentAuditor())
                .contains(SecurityAuditorAware.SYSTEM_ACCOUNT_ID);
    }

    @Test
    @DisplayName("시스템 계정 id는 실제 계정과 겹치지 않는다 — account.id는 BIGSERIAL이라 1부터 시작")
    void systemIdDoesNotCollide() {
        assertThat(SecurityAuditorAware.SYSTEM_ACCOUNT_ID).isZero();
    }
}
