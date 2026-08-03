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
        return new SearchScope(toShort(year), principal.academyScopeFilter());
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
