package com.dlab.common.search;

import com.dlab.common.security.AuthPrincipal;

/**
 * 모든 목록 조회에 강제로 붙는 공통 범위.
 *
 * <p><b>{@code academyId}는 클라이언트가 지정할 수 없다.</b> 반드시 인증 주체에서 뽑는다 —
 * 요청 파라미터로 받으면 값을 바꿔 보내는 것만으로 다른 지점 데이터가 새어나간다
 * (CLAUDE.md §7 "지점 필터링 필수").
 *
 * <p>{@code null}이면 전 지점 조회다. 상위 관리자만 그렇게 나온다
 * ({@link AuthPrincipal#academyScopeFilter()}).
 *
 * @param year      조회 연도. 1년 코호트라 대부분의 조회가 연도로 갈린다.
 *                  전 테이블이 {@code SMALLINT}라 {@code Short}로 둔다
 * @param academyId 지점 스코프. null이면 전 지점
 */
public record SearchScope(Short year, Long academyId) {

    /** 인증 주체에서 지점 스코프를 뽑는다. 컨트롤러는 이 팩토리만 쓸 것. */
    public static SearchScope of(AuthPrincipal principal, Integer year) {
        return of(principal, year, null);
    }

    /**
     * 지점을 골라서 좁힌다.
     *
     * <p><b>넓히지는 못한다.</b> 요청 값을 {@link AuthPrincipal#resolveAcademyScope(Long)}가
     * 검증하므로, 지점 관리자가 다른 지점을 넣으면 거절된다 — "클라이언트가 지정할 수 없다"는
     * 규칙의 목적은 <b>남의 지점을 못 보게 하는 것</b>이지 자기 범위 안에서 고르는 것까지
     * 막는 게 아니다.
     *
     * <p>이게 없으면 <b>본사 계정은 지점을 고를 수단이 없어</b> 전 지점 학생이 한꺼번에 나온다.
     * 반 배정처럼 지점이 정해져야 성립하는 화면이 그대로 막힌다.
     *
     * @param requestedAcademyId 비우면 종전과 같다 — 지점 관리자는 자기 지점, 본사는 전 지점
     */
    public static SearchScope of(AuthPrincipal principal, Integer year, Long requestedAcademyId) {
        return new SearchScope(toShort(year), principal.resolveAcademyScope(requestedAcademyId));
    }

    /** 배치·스케줄러처럼 인증 주체가 없는 경로용. 지점을 명시적으로 넘긴다. */
    public static SearchScope system(Integer year, Long academyId) {
        return new SearchScope(toShort(year), academyId);
    }

    private static Short toShort(Integer year) {
        return year == null ? null : year.shortValue();
    }

    public boolean isAllAcademy() {
        return academyId == null;
    }
}
