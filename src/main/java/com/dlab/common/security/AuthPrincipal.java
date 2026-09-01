package com.dlab.common.security;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
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

    /**
     * 요청한 지점을 검증해 확정한다. <b>지점을 고를 수 있는 화면은 전부 이걸 쓴다.</b>
     *
     * <h2>★ {@link #academyScopeFilter()}의 {@code null}은 "필터 없음"이지 "지점 모름"이 아니다</h2>
     * 전 지점 권한자(본사)에게 그 메서드는 <b>일부러</b> {@code null}을 준다 — 지점 조건을
     * 걸지 말라는 뜻이다. 그런데 이 {@code null}을 "지점을 알 수 없다"로 읽고 곧바로 400을
     * 던지면 <b>본사는 화면을 아예 열지 못한다.</b> 실제로 여러 서비스가 그렇게 되어 있었다.
     *
     * <p>그래서 순서가 이렇다:
     * <ol>
     *   <li>요청에 {@code academyId}가 있으면 그걸 쓴다 — 단 {@link #canAccessAcademy(Long)}로
     *       반드시 검사한다. 요청 값을 그대로 믿으면 지점 관리자가 남의 지점을 들여다볼 수 있다</li>
     *   <li>없으면 {@link #academyScopeFilter()}(= 내 지점)</li>
     *   <li>그래도 {@code null}이면 <b>본사가 지점을 안 고른 것</b>이다 — 여기서만 400이다</li>
     * </ol>
     *
     * @param requested 요청 파라미터의 지점. 비우면 내 지점이 쓰인다
     * @return 조회·수정에 쓸 지점. 절대 {@code null}이 아니다
     * @throws BusinessException 남의 지점이면 {@code OTHER_BRANCH_ACCESS_DENIED},
     *                           본사가 지점을 안 골랐으면 {@code INVALID_REQUEST}
     */
    public Long requireAcademyScope(Long requested) {
        Long resolved = resolveAcademyScope(requested);
        if (resolved == null) {
            // 전 지점 권한자가 지점을 안 골랐다. 전 지점을 한 번에 뿌리면 어느 지점 건인지
            // 구분 없이 승인·독촉 버튼이 눌린다
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "지점을 지정해야 합니다.");
        }
        return resolved;
    }

    /**
     * {@link #requireAcademyScope(Long)}와 같되 <b>{@code null}을 허용</b>한다 —
     * 전 지점 권한자가 지점을 안 고르면 "전 지점 합계"라는 뜻이 되는 화면(통계 등)용이다.
     *
     * <p>지점 관리자에게는 {@code null}이 나오지 않는다. 그쪽은 항상 자기 지점으로 고정된다.
     *
     * @param requested 요청 파라미터의 지점. 비우면 내 지점(본사는 전 지점)
     */
    public Long resolveAcademyScope(Long requested) {
        if (requested != null) {
            if (!canAccessAcademy(requested)) {
                throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
            }
            return requested;
        }
        return academyScopeFilter();
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
