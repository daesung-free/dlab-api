package com.dlab.common.search;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.ComparableExpression;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.core.types.dsl.SimpleExpression;
import com.querydsl.core.types.dsl.StringExpression;
import java.util.Collection;

/**
 * 동적 검색 조건 헬퍼. <b>값이 없으면 {@code null}을 돌려주고, QueryDSL은 null 조건을 무시한다</b> —
 * 이 성질 덕에 {@code where(a, b, c)}에 그냥 나열하면 들어온 조건만 자동으로 적용된다.
 *
 * <pre>{@code
 * queryFactory.selectFrom(student)
 *     .where(
 *         SearchPredicates.scope(student.academyId, student.year, scope),
 *         SearchPredicates.contains(student.name, keyword),   // keyword가 null이면 조건 없음
 *         SearchPredicates.eq(student.grade, grade))
 * }</pre>
 *
 * <p><b>{@code and()}로 이어 붙이지 말 것.</b> 앞 조건이 null이면 NPE가 나고,
 * 방어하려고 if를 겹치면 조건이 12개인 학생 검색에서 감당이 안 된다.
 */
public final class SearchPredicates {

    private SearchPredicates() {
    }

    /**
     * 지점·연도 공통 범위. <b>목록 조회는 예외 없이 이걸 먼저 건다.</b>
     * 전 지점 권한자면 지점 조건이 빠지고, 아니면 소속 지점으로 강제된다.
     *
     * <p>{@code academyIdPath}는 연관관계라 보통 {@code entity.academy.id}다.
     * {@code yearPath}는 전 테이블이 {@code SMALLINT}라 {@code Short}로 들어온다.
     */
    public static BooleanExpression scope(NumberExpression<Long> academyIdPath,
                                          NumberExpression<Short> yearPath,
                                          SearchScope scope) {
        if (scope == null) {
            return null;
        }
        return and(academyScope(academyIdPath, scope), yearEq(yearPath, scope.year()));
    }

    /** 연도 컬럼이 없는 테이블(지점 마스터 등)용. */
    public static BooleanExpression academyScope(NumberExpression<Long> academyIdPath,
                                                 SearchScope scope) {
        if (scope == null || scope.academyId() == null) {
            return null;
        }
        return academyIdPath.eq(scope.academyId());
    }

    /** 연도 일치. 컬럼이 {@code SMALLINT}(=short)라 별도로 둔다. */
    public static BooleanExpression yearEq(NumberExpression<Short> yearPath, Short year) {
        return year == null ? null : yearPath.eq(year);
    }

    public static <T> BooleanExpression eq(SimpleExpression<T> path, T value) {
        return value == null ? null : path.eq(value);
    }

    /** 부분 일치. 빈 문자열은 "조건 없음"으로 본다 — 화면에서 지운 검색어가 전건 조회를 막는다. */
    public static BooleanExpression contains(StringExpression path, String value) {
        return isBlank(value) ? null : path.containsIgnoreCase(value.trim());
    }

    public static BooleanExpression startsWith(StringExpression path, String value) {
        return isBlank(value) ? null : path.startsWithIgnoreCase(value.trim());
    }

    public static <T> BooleanExpression in(SimpleExpression<T> path, Collection<T> values) {
        return values == null || values.isEmpty() ? null : path.in(values);
    }

    /**
     * 한쪽만 들어와도 동작한다(이후·이전 조회). 둘 다 없으면 조건 없음.
     *
     * <p>{@code Comparable<? super T>}인 이유: {@code LocalDate}는
     * {@code Comparable<LocalDate>}가 아니라 {@code Comparable<ChronoLocalDate>}를 구현한다.
     * {@code T extends Comparable<T>}로 두면 날짜 범위 검색이 전부 컴파일되지 않는다.
     */
    public static <T extends Comparable<? super T>> BooleanExpression between(
            ComparableExpression<T> path, T from, T to) {
        if (from == null && to == null) {
            return null;
        }
        if (from == null) {
            return path.loe(to);
        }
        if (to == null) {
            return path.goe(from);
        }
        return path.between(from, to);
    }

    /** {@code null}을 건너뛰고 AND로 묶는다. 전부 null이면 null. */
    public static BooleanExpression and(BooleanExpression... expressions) {
        BooleanExpression result = null;
        for (BooleanExpression expression : expressions) {
            if (expression == null) {
                continue;
            }
            result = result == null ? expression : result.and(expression);
        }
        return result;
    }

    /** {@code null}을 건너뛰고 OR로 묶는다. 통합검색(이름·학번·전화번호)에 쓴다. */
    public static BooleanExpression or(BooleanExpression... expressions) {
        BooleanExpression result = null;
        for (BooleanExpression expression : expressions) {
            if (expression == null) {
                continue;
            }
            result = result == null ? expression : result.or(expression);
        }
        return result;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
