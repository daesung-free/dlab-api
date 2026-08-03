package com.dlab.common.security;

import com.dlab.domain.user.entity.AccountType;

import java.util.Set;

/**
 * 인증된 요청의 주체. JWT 클레임에서 복원되며 DB를 다시 읽지 않는다.
 *
 * @param accountId  계정 PK
 * @param accountType 학생/학부모/직원/선생님
 * @param academyId  소속 지점. 전 지점 권한이면 null일 수 있다
 * @param roles      RBAC 5단계 role 이름 (SUPER_ADMIN / BRANCH_ADMIN / TEACHER / STAFF / READONLY)
 */
public record AuthPrincipal(
        Long accountId,
        AccountType accountType,
        Long academyId,
        Set<String> roles
) {

    public AuthPrincipal {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    /**
     * 전 지점 조회가 가능한가.
     * 지점 필터링을 빠뜨리면 다른 지점 데이터가 새므로(CLAUDE.md §7),
     * 조회 서비스는 이 값을 반드시 확인해야 한다.
     */
    public boolean canAccessAllAcademies() {
        return roles.contains("SUPER_ADMIN");
    }

    /** 이 주체가 해당 지점 데이터를 볼 수 있는가. */
    public boolean canAccess(Long targetAcademyId) {
        if (canAccessAllAcademies()) {
            return true;
        }
        return academyId != null && academyId.equals(targetAcademyId);
    }
}
