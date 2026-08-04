package com.dlab.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 지점 격리. CLAUDE.md §7 "지점 필터링 필수"가 실제로 동작하는지 본다 —
 * 여기가 뚫리면 다른 지점 학생 정보가 그대로 새어나간다.
 */
class AuthPrincipalTest {

    private AuthPrincipal branchAdminOf(Long academyId) {
        return AuthPrincipal.of(1L, "EMPLOYEE", academyId, List.of(Role.BRANCH_ADMIN), false);
    }

    private AuthPrincipal superAdmin() {
        return AuthPrincipal.of(1L, "EMPLOYEE", null, List.of(Role.SUPER_ADMIN), true);
    }

    @Test
    @DisplayName("지점 관리자는 자기 지점만 접근한다")
    void branchScoped() {
        AuthPrincipal me = branchAdminOf(7L);

        assertThat(me.canAccessAcademy(7L)).isTrue();
        assertThat(me.canAccessAcademy(8L)).isFalse();
        assertThat(me.academyScopeFilter()).isEqualTo(7L);
    }

    @Test
    @DisplayName("전 지점 권한자는 어느 지점이든 접근하고 조회 필터가 걸리지 않는다")
    void allAcademyUnscoped() {
        AuthPrincipal me = superAdmin();

        assertThat(me.canAccessAcademy(7L)).isTrue();
        assertThat(me.canAccessAcademy(999L)).isTrue();
        assertThat(me.academyScopeFilter()).isNull();
    }

    @Test
    @DisplayName("소속 지점이 없는데 전 지점 권한도 없으면 아무 지점도 접근 못 한다")
    void noAcademyNoAccess() {
        AuthPrincipal me = AuthPrincipal.of(1L, "EMPLOYEE", null, List.of(Role.READONLY), false);

        assertThat(me.canAccessAcademy(7L)).isFalse();
        assertThat(me.canAccessAcademy(null)).isFalse();
    }

    @Test
    @DisplayName("역할은 ROLE_ 접두사가 붙은 권한으로 노출된다")
    void authorities() {
        AuthPrincipal me = branchAdminOf(7L);

        assertThat(me.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_BRANCH_ADMIN");
        assertThat(me.hasRole(Role.BRANCH_ADMIN)).isTrue();
        assertThat(me.hasRole(Role.SUPER_ADMIN)).isFalse();
    }
}
