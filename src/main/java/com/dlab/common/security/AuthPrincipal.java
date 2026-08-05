package com.dlab.common.security;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * 인증된 요청 주체. JWT 클레임에서 복원되며 DB를 다시 조회하지 않는다.
 *
 * <p><b>{@code academyId}를 반드시 들고 다닌다.</b> 지점 필터링이 CLAUDE.md §7 필수 규칙인데,
 * 이 값이 없으면 서비스 레이어마다 계정을 다시 조회해야 하고 한 곳만 빠뜨려도
 * 다른 지점 데이터가 새어나간다.
 *
 * @param accountId   account.id
 * @param accountType STUDENT / PARENT / EMPLOYEE / TEACHER
 * @param academyId   소속 지점. 전 지점 권한자는 null일 수 있다
 * @param roles       부여된 역할
 * @param allAcademy  전 지점 접근 가능 여부(permission.academy_scope='ALL')
 * @param mustChangePassword 임시 비밀번호 상태(A-1). true면 비밀번호 변경 외 API가 막힌다
 */
public record AuthPrincipal(
        Long accountId,
        String accountType,
        Long academyId,
        Set<Role> roles,
        boolean allAcademy,
        boolean mustChangePassword
) implements UserDetails {

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream()
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority(r.authority()))
                .toList();
    }

    /** JWT 인증이라 비밀번호를 들고 있지 않다. */
    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return String.valueOf(accountId);
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }

    /**
     * 해당 지점에 접근 가능한지. 전 지점 권한이 없으면 소속 지점만 허용한다.
     * 조회·수정 양쪽에서 이걸 통과시키지 않으면 지점 격리가 뚫린다.
     */
    public boolean canAccessAcademy(Long targetAcademyId) {
        if (allAcademy) {
            return true;
        }
        return academyId != null && academyId.equals(targetAcademyId);
    }

    /** 조회 시 강제할 지점 필터. 전 지점 권한자는 null(=필터 없음). */
    public Long academyScopeFilter() {
        return allAcademy ? null : academyId;
    }

    /** 임시 비밀번호 상태가 아닌 일반 주체. */
    public static AuthPrincipal of(Long accountId, String accountType, Long academyId,
                                   List<Role> roles, boolean allAcademy) {
        return of(accountId, accountType, academyId, roles, allAcademy, false);
    }

    public static AuthPrincipal of(Long accountId, String accountType, Long academyId,
                                   List<Role> roles, boolean allAcademy,
                                   boolean mustChangePassword) {
        return new AuthPrincipal(accountId, accountType, academyId, Set.copyOf(roles),
                allAcademy, mustChangePassword);
    }
}
