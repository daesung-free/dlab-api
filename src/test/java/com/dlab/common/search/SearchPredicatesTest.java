package com.dlab.common.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.user.entity.QStudentEnrollment;
import com.querydsl.core.types.dsl.BooleanExpression;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 동적 검색 조건. <b>값이 없으면 조건이 빠지는 것</b>이 이 모듈의 전부라
 * 그 성질을 실제 Q클래스로 확인한다.
 *
 * <p>여기가 깨지면 조건 하나가 조용히 무시되거나(전건 조회) 반대로 항상 걸려서
 * 결과가 비는데, 둘 다 런타임에는 티가 안 난다.
 */
class SearchPredicatesTest {

    private final QStudentEnrollment enrollment = QStudentEnrollment.studentEnrollment;

    @Test
    @DisplayName("값이 없으면 조건이 만들어지지 않는다 — QueryDSL이 null을 무시한다")
    void nullValueProducesNoPredicate() {
        assertThat(SearchPredicates.eq(enrollment.studentNo, null)).isNull();
        assertThat(SearchPredicates.contains(enrollment.studentNo, null)).isNull();
        assertThat(SearchPredicates.contains(enrollment.studentNo, "   ")).isNull();
        assertThat(SearchPredicates.in(enrollment.studentNo, List.of())).isNull();
        assertThat(SearchPredicates.between(enrollment.admissionDate, null, null)).isNull();
    }

    @Test
    @DisplayName("값이 있으면 조건이 만들어진다")
    void valueProducesPredicate() {
        assertThat(SearchPredicates.eq(enrollment.studentNo, "2026-0001")).isNotNull();
        assertThat(SearchPredicates.contains(enrollment.studentNo, "2026")).isNotNull();
    }

    @Test
    @DisplayName("범위는 한쪽만 들어와도 동작한다")
    void openEndedRange() {
        var from = SearchPredicates.between(enrollment.admissionDate,
                java.time.LocalDate.of(2026, 3, 1), null);
        var to = SearchPredicates.between(enrollment.admissionDate,
                null, java.time.LocalDate.of(2026, 12, 31));

        assertThat(from).isNotNull();
        assertThat(to).isNotNull();
        assertThat(from.toString()).contains(">=");
        assertThat(to.toString()).contains("<=");
    }

    @Test
    @DisplayName("and/or는 null을 건너뛴다 — 조건을 나열만 해도 되게 하려는 것")
    void nullSafeCombination() {
        BooleanExpression present = SearchPredicates.eq(enrollment.studentNo, "2026-0001");

        assertThat(SearchPredicates.and(null, present, null)).isEqualTo(present);
        assertThat(SearchPredicates.and(null, null)).isNull();
        assertThat(SearchPredicates.or(null, present)).isEqualTo(present);
    }

    @Test
    @DisplayName("★ 지점 권한자는 지점 조건이 강제로 붙는다")
    void branchScopeIsForced() {
        AuthPrincipal branchAdmin = AuthPrincipal.of(
                1L, "EMPLOYEE", 7L, List.of(Role.BRANCH_ADMIN), false);
        SearchScope scope = SearchScope.of(branchAdmin, 2026);

        BooleanExpression predicate = SearchPredicates.scope(
                enrollment.academy.id, enrollment.year, scope);

        assertThat(scope.academyId()).isEqualTo(7L);
        assertThat(predicate).isNotNull();
        // 경로가 연관관계라 studentEnrollment.academy.id 로 렌더된다
        assertThat(predicate.toString())
                .contains("academy.id")
                .contains("year");
    }

    @Test
    @DisplayName("★ 전 지점 권한자는 지점 조건이 빠지고 연도만 남는다")
    void allAcademyScopeDropsBranchFilter() {
        AuthPrincipal superAdmin = AuthPrincipal.of(
                1L, "EMPLOYEE", null, List.of(Role.SUPER_ADMIN), true);
        SearchScope scope = SearchScope.of(superAdmin, 2026);

        BooleanExpression predicate = SearchPredicates.scope(
                enrollment.academy.id, enrollment.year, scope);

        assertThat(scope.isAllAcademy()).isTrue();
        assertThat(predicate).isNotNull();
        assertThat(predicate.toString())
                .doesNotContain("academy.id")
                .contains("year");
    }

    @Test
    @DisplayName("지점 스코프는 클라이언트가 지정할 수 없다 — 인증 주체에서만 나온다")
    void scopeComesFromPrincipalOnly() {
        AuthPrincipal branchAdmin = AuthPrincipal.of(
                1L, "EMPLOYEE", 7L, List.of(Role.BRANCH_ADMIN), false);

        // 연도만 요청에서 받고, 지점은 주체가 결정한다
        SearchScope scope = SearchScope.of(branchAdmin, 2027);

        assertThat(scope.year()).isEqualTo((short) 2027);
        assertThat(scope.academyId()).isEqualTo(7L);
    }
}
