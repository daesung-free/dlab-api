package com.dlab.domain.user.repository;

import com.dlab.common.search.SearchPredicates;
import com.dlab.common.search.SearchScope;
import com.dlab.common.search.SearchSupport;
import com.dlab.domain.user.entity.*;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.PathBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 학생 검색 (QueryDSL 동적 조건).
 *
 * <p>요구사항 F-4.1-1은 12개 조건 검색을 요구한다. 조건을 {@code if}로 쌓으면 감당이 안 되므로
 * {@link SearchPredicates}가 null을 무시하는 성질을 이용해 {@code where(...)}에 나열한다.
 *
 * <p><b>검색 대상은 등록 건({@link StudentEnrollment})이다.</b> "올해 재원생 목록"이 기본이라
 * 사람({@link Student}) 기준으로 뽑으면 졸업생·과거 기수가 섞인다.
 */
@Repository
@RequiredArgsConstructor
public class StudentSearchRepository {

    /**
     * <b>정렬 허용 필드(화이트리스트).</b> 여기 없는 필드는 {@link SearchSupport#toOrders}가
     * 조용히 무시한다 — 오류가 아니다.
     *
     * <p>화이트리스트인 이유는 두 가지다. ①클라이언트가 보낸 문자열을 그대로 경로로 만들면
     * 매핑되지 않은 이름에서 예외가 나 검색 화면 전체가 죽는다. ②인덱스가 없는 임의 컬럼으로
     * 정렬하면 재원생 전체를 정렬하느라 목록이 급격히 느려진다.
     *
     * <p><b>반(class)은 넣을 수 없다.</b> 반 배정이 {@code class_assignment} 별도 테이블이라
     * {@link PathBuilder}로 닿는 경로가 없다(검색 조건도 서브쿼리로 건다). 반 정렬이 필요해지면
     * 조인을 추가하고 별도 {@code OrderSpecifier}를 만들어야 한다.
     */
    public static final List<String> SORTABLE = List.of(
            "studentNo",      // 학번 — 기본 정렬이자 tie-breaker
            "student.name",   // 이름 (연관 경로. 아래 별칭으로 "name"도 받는다)
            "grade",          // 학년
            "track",          // 계열
            "enrollmentStatus", // 재원 상태
            "admissionDate");   // 입학일

    /**
     * 화면이 쓰기 편한 이름 → 실제 엔티티 경로. 이름은 사람({@code Student}) 쪽에 있어
     * 경로가 {@code student.name}인데, 화면에서 {@code sort=name}으로 보내는 게 자연스럽다.
     */
    private static final Map<String, String> SORT_ALIASES = Map.of("name", "student.name");

    /** 기본 정렬이자 tie-breaker. 이 값이 바뀌면 지금 화면의 기본 순서가 달라진다. */
    private static final String TIE_BREAKER = "studentNo";

    /**
     * {@code toOrders}에 넘길 경로 루트. 별칭은 쿼리에서 쓰는
     * {@code QStudentEnrollment.studentEnrollment}와 <b>같아야</b> 한다 — 다르면
     * 정렬절이 조회 대상과 무관한 별칭을 가리킨다.
     */
    private static final PathBuilder<StudentEnrollment> SORT_PATH =
            new PathBuilder<>(StudentEnrollment.class, "studentEnrollment");

    private final JPAQueryFactory queryFactory;

    /**
     * @param scope      지점·연도 범위. 인증 주체에서만 나온다 — 요청 파라미터로 받지 않는다
     * @param keyword    이름·학번 통합 검색어
     * @param grade      학년(고2/고3/N수생)
     * @param track      계열
     * @param status     재원 상태
     * @param classId    반. null이면 전체(미배정 포함)
     * @param pageable   페이징 + 정렬. 정렬 가능 필드는 {@link #SORTABLE} 참고 —
     *                   목록에 없는 필드는 무시되고 기본 정렬(학번 오름차순)로 떨어진다
     */
    public Page<StudentEnrollment> search(SearchScope scope, String keyword, GradeType grade,
                                          TrackType track, EnrollmentStatus status,
                                          Long classId, Pageable pageable) {
        QStudentEnrollment e = QStudentEnrollment.studentEnrollment;
        QStudent s = QStudent.student;

        var content = queryFactory
                .selectFrom(e)
                .join(e.student, s).fetchJoin()
                .join(e.academy).fetchJoin()
                .where(
                        SearchPredicates.scope(e.academy.id, e.year, scope),
                        e.deleted.isFalse(),
                        // 과거 기수 행이 섞이지 않게 현재 등록 건만
                        e.current.isTrue(),
                        // ★ 직원은 학생 명단에 나오지 않는다. 직원 목록은 별도 화면이다
                        e.grade.ne(GradeType.STAFF),
                        keywordMatches(keyword),
                        SearchPredicates.eq(e.grade, grade),
                        SearchPredicates.eq(e.track, track),
                        SearchPredicates.eq(e.enrollmentStatus, status),
                        assignedToClass(classId))
                .orderBy(toOrders(pageable.getSort()))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        return SearchSupport.page(content, pageable, () -> queryFactory
                .select(e.count())
                .from(e)
                .join(e.student, s)
                .where(
                        SearchPredicates.scope(e.academy.id, e.year, scope),
                        e.deleted.isFalse(),
                        e.current.isTrue(),
                        e.grade.ne(GradeType.STAFF),
                        keywordMatches(keyword),
                        SearchPredicates.eq(e.grade, grade),
                        SearchPredicates.eq(e.track, track),
                        SearchPredicates.eq(e.enrollmentStatus, status),
                        assignedToClass(classId))
                .fetchOne());
    }

    /**
     * 요청 정렬을 QueryDSL 정렬로 옮긴다.
     *
     * <p>두 가지를 지킨다.
     * <ul>
     *   <li><b>정렬이 없거나 전부 허용 목록 밖이면 학번 오름차순</b> — 기존 동작 그대로다.
     *       기본값을 바꾸면 지금 쓰고 있는 화면의 순서가 갑자기 달라진다.</li>
     *   <li><b>마지막에 학번을 덧붙인다(tie-breaker).</b> 학년처럼 값이 겹치는 컬럼 하나로만
     *       정렬하면 동점 구간의 순서를 DB가 보장하지 않아, 페이지를 넘길 때 같은 학생이
     *       두 번 보이거나 아예 빠진다.</li>
     * </ul>
     */
    private OrderSpecifier<?>[] toOrders(Sort sort) {
        Sort resolved = applyAliases(sort);
        List<OrderSpecifier<?>> orders =
                new ArrayList<>(Arrays.asList(SearchSupport.toOrders(SORT_PATH, resolved, SORTABLE)));

        boolean alreadySortedByStudentNo = resolved.stream()
                .anyMatch(order -> TIE_BREAKER.equals(order.getProperty()));
        if (!alreadySortedByStudentNo) {
            orders.add(QStudentEnrollment.studentEnrollment.studentNo.asc());
        }
        return orders.toArray(new OrderSpecifier<?>[0]);
    }

    /** {@code sort=name} 같은 화면 친화적 이름을 실제 경로로 바꾼다. 그 외는 그대로 둔다. */
    private Sort applyAliases(Sort sort) {
        if (sort == null || sort.isUnsorted()) {
            return Sort.unsorted();
        }
        return Sort.by(sort.stream()
                .map(order -> order.withProperty(
                        SORT_ALIASES.getOrDefault(order.getProperty(), order.getProperty())))
                .toList());
    }

    /** 이름 또는 학번. 운영에서 둘을 구분해 입력하지 않으므로 한 칸으로 받는다. */
    private BooleanExpression keywordMatches(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        QStudentEnrollment e = QStudentEnrollment.studentEnrollment;
        return e.student.name.containsIgnoreCase(keyword)
                .or(e.studentNo.containsIgnoreCase(keyword));
    }

    /** 반 배정은 별도 테이블이라 서브쿼리로 건다. 미배정 학생도 검색에 걸려야 한다. */
    private BooleanExpression assignedToClass(Long classId) {
        if (classId == null) {
            return null;
        }
        QStudentEnrollment e = QStudentEnrollment.studentEnrollment;
        QClassAssignment a = QClassAssignment.classAssignment;
        return com.querydsl.jpa.JPAExpressions
                .selectOne()
                .from(a)
                .where(a.enrollment.eq(e), a.classMaster.id.eq(classId), a.active.isTrue())
                .exists();
    }
}
