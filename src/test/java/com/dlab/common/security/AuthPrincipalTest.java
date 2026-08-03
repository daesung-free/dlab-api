package com.dlab.common.security;

import com.dlab.domain.user.entity.AccountType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 지점 필터링 판정 — 빠뜨리면 다른 지점 데이터가 샌다(CLAUDE.md §7). */
class AuthPrincipalTest {

    private AuthPrincipal of(Long academyId, String... roles) {
        return new AuthPrincipal(1L, AccountType.EMPLOYEE, academyId, Set.of(roles));
    }

    @Test
    @DisplayName("자기 지점은 볼 수 있다")
    void ownAcademy() {
        assertThat(of(10L, "STAFF").canAccess(10L)).isTrue();
    }

    @Test
    @DisplayName("다른 지점은 못 본다")
    void otherAcademy() {
        assertThat(of(10L, "STAFF").canAccess(20L)).isFalse();
    }

    @Test
    @DisplayName("SUPER_ADMIN만 전 지점을 본다")
    void superAdminSeesAll() {
        assertThat(of(10L, "SUPER_ADMIN").canAccess(20L)).isTrue();
        assertThat(of(10L, "BRANCH_ADMIN").canAccess(20L)).isFalse();
    }

    @Test
    @DisplayName("소속 지점이 없으면(학부모 등) 어떤 지점도 못 본다")
    void noAcademyMeansNoAccess() {
        assertThat(of(null, "READONLY").canAccess(10L)).isFalse();
    }
}
