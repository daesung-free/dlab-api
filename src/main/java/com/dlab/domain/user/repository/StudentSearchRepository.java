package com.dlab.domain.user.repository;

import com.dlab.common.search.SearchPredicates;
import com.dlab.common.search.SearchScope;
import com.dlab.common.search.SearchSupport;
import com.dlab.domain.facility.entity.QSeatAssignment;
import com.dlab.domain.master.entity.QLockerMaster;
import com.dlab.domain.master.entity.QScholarship;
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
     * @param scope     지점·연도 범위. 인증 주체에서만 나온다 — 요청 파라미터로 받지 않는다
     * @param condition 나머지 검색 조건. 값이 없는 항목은 조건에서 자동으로 빠진다
     * @param pageable  페이징 + 정렬. 정렬 가능 필드는 {@link #SORTABLE} 참고 —
     *                  목록에 없는 필드는 무시되고 기본 정렬(학번 오름차순)로 떨어진다
     */
    public Page<StudentEnrollment> search(SearchScope scope, StudentSearchCondition condition,
                                          Pageable pageable) {
        QStudentEnrollment e = QStudentEnrollment.studentEnrollment;
        QStudent s = QStudent.student;

        var content = queryFactory
                .selectFrom(e)
                .join(e.student, s).fetchJoin()
                .join(e.academy).fetchJoin()
                .where(conditions(scope, condition))
                .orderBy(toOrders(pageable.getSort()))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        return SearchSupport.page(content, pageable, () -> queryFactory
                .select(e.count())
                .from(e)
                .join(e.student, s)
                .where(conditions(scope, condition))
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

    /**
     * 본문과 count가 <b>같은 조건</b>을 써야 한다 — 두 벌로 두면 조건이 늘 때 한쪽만 고쳐지고
     * 목록은 3건인데 총계가 5건인 상태가 조용히 생긴다.
     */
    private BooleanExpression[] conditions(SearchScope scope, StudentSearchCondition c) {
        QStudentEnrollment e = QStudentEnrollment.studentEnrollment;
        StudentSearchCondition cond = c == null ? StudentSearchCondition.none() : c;

        return new BooleanExpression[]{
                SearchPredicates.scope(e.academy.id, e.year, scope),
                e.deleted.isFalse(),
                // 과거 기수 행이 섞이지 않게 현재 등록 건만
                e.current.isTrue(),
                // ★ 직원은 학생 명단에 나오지 않는다. 직원 목록은 별도 화면이다
                e.grade.ne(GradeType.STAFF),
                keywordMatches(cond.keyword()),
                SearchPredicates.eq(e.grade, cond.grade()),
                SearchPredicates.eq(e.track, cond.track()),
                SearchPredicates.eq(e.enrollmentStatus, cond.status()),
                SearchPredicates.contains(e.student.schoolName, cond.schoolName()),
                SearchPredicates.between(e.admissionDate, cond.admittedFrom(), cond.admittedTo()),
                assignedToClass(cond.classId()),
                assignedToTeacher(cond.teacherId()),
                classAssigned(cond.unassignedClass()),
                seatAssigned(cond.unassignedSeat()),
                lockerAssigned(cond.unassignedLocker()),
                hasScholarship(cond.hasScholarship(), cond.scholarshipType())
        };
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

    /**
     * 담임 검색.
     *
     * <p><b>담임은 학생이 아니라 반에 붙는다</b>(CLAUDE.md §2 — 반배정 시 자동 연동).
     * 그래서 학생 컬럼으로는 걸 수 없고 배정 → 반 → 담임 순으로 타야 한다.
     * 고정반만 본다 — 이동수업반에는 담임 개념이 없다.
     */
    private BooleanExpression assignedToTeacher(Long teacherId) {
        if (teacherId == null) {
            return null;
        }
        QStudentEnrollment e = QStudentEnrollment.studentEnrollment;
        QClassAssignment a = QClassAssignment.classAssignment;
        return com.querydsl.jpa.JPAExpressions
                .selectOne()
                .from(a)
                .where(a.enrollment.eq(e),
                        a.classType.eq(ClassType.FIXED),
                        a.active.isTrue(),
                        a.classMaster.homeroomTeacher.id.eq(teacherId))
                .exists();
    }

    // ── 미배정 조건 ──
    //
    // ★ 전부 "exists 서브쿼리를 뒤집은 것"이다. 배정 여부가 학생 컬럼이 아니라 별도 테이블이라
    //   조인으로 걸면 배정이 없는 학생이 아예 결과에서 빠진다(inner join) — 그게 정확히
    //   찾으려는 대상이라 not exists가 유일한 방법이다.
    //
    // ★ "배정 중"의 정의가 테이블마다 다르다. 반은 active 플래그, 좌석은 released_at IS NULL,
    //   사물함은 컬럼 하나(assigned_enrollment_id)다. 하나로 통일하려 들지 말 것 —
    //   좌석만 이력 테이블이고 사물함은 현재값만 들고 있다(V1/V2 스키마).

    /**
     * 고정반 배정 여부.
     *
     * <p><b>{@code StudentListEnricher}와 같은 조건이어야 한다</b>(고정반 · active · 미삭제) —
     * 목록 응답의 {@code className}을 채우는 조회가 그것이라, 조건이 어긋나면
     * "반 이름은 비었는데 미배정 검색에는 안 걸리는" 학생이 생긴다.
     *
     * <p>이동수업반은 보지 않는다. 담임·승인 에스컬레이션이 전부 고정반에 붙어 있어
     * 반 배정 화면이 말하는 "반"이 고정반이다.
     */
    private BooleanExpression classAssigned(Boolean unassigned) {
        if (unassigned == null) {
            return null;
        }
        QStudentEnrollment e = QStudentEnrollment.studentEnrollment;
        QClassAssignment a = QClassAssignment.classAssignment;
        BooleanExpression exists = com.querydsl.jpa.JPAExpressions
                .selectOne()
                .from(a)
                .where(a.enrollment.eq(e),
                        a.classType.eq(ClassType.FIXED),
                        a.active.isTrue(),
                        a.deleted.isFalse())
                .exists();
        return unassigned ? exists.not() : exists;
    }

    /** 좌석 배정 여부. "배정 중"은 {@code released_at IS NULL}이다(V2 스키마 규약). */
    private BooleanExpression seatAssigned(Boolean unassigned) {
        if (unassigned == null) {
            return null;
        }
        QStudentEnrollment e = QStudentEnrollment.studentEnrollment;
        QSeatAssignment a = QSeatAssignment.seatAssignment;
        BooleanExpression exists = com.querydsl.jpa.JPAExpressions
                .selectOne()
                .from(a)
                .where(a.enrollment.eq(e), a.releasedAt.isNull(), a.deleted.isFalse())
                .exists();
        return unassigned ? exists.not() : exists;
    }

    /**
     * 사물함 배정 여부.
     *
     * <p>좌석과 달리 배정 이력 테이블이 없고 사물함 행이 현재 사용자를 직접 가리킨다 —
     * 그래서 방향이 반대다(사물함 → 학생).
     */
    private BooleanExpression lockerAssigned(Boolean unassigned) {
        if (unassigned == null) {
            return null;
        }
        QStudentEnrollment e = QStudentEnrollment.studentEnrollment;
        QLockerMaster l = QLockerMaster.lockerMaster;
        BooleanExpression exists = com.querydsl.jpa.JPAExpressions
                .selectOne()
                .from(l)
                .where(l.assignedEnrollment.eq(e), l.deleted.isFalse())
                .exists();
        return unassigned ? exists.not() : exists;
    }

    /**
     * 장학 보유 여부 / 종류.
     *
     * <p>둘을 한 메서드로 묶은 이유는 <b>같은 서브쿼리</b>이기 때문이다 — 종류가 들어오면
     * 그 종류로 좁힌 exists가 되고, 없으면 종류 무관 exists다. 따로 두면
     * {@code hasScholarship=true&scholarshipType=KICE_50}이 서브쿼리 두 개로 나간다.
     *
     * <p>종류만 오면 <b>"그 장학을 가진 학생"</b>으로 읽는다 — 종류를 지정해 놓고
     * 보유 여부를 또 보내야 한다면 화면이 불필요하게 두 값을 관리하게 된다.
     * ({@code hasScholarship=false} + 종류는 모순이라 조건 생성 시점에 막힌다.)
     */
    private BooleanExpression hasScholarship(Boolean has, String type) {
        if (has == null && type == null) {
            return null;
        }
        QStudentEnrollment e = QStudentEnrollment.studentEnrollment;
        QScholarship s = QScholarship.scholarship;
        BooleanExpression exists = com.querydsl.jpa.JPAExpressions
                .selectOne()
                .from(s)
                .where(s.enrollment.eq(e),
                        s.deleted.isFalse(),
                        SearchPredicates.eq(s.scholarshipType, type))
                .exists();
        return Boolean.FALSE.equals(has) ? exists.not() : exists;
    }
}
