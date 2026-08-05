package com.dlab.domain.user.repository;

import com.dlab.common.search.SearchPredicates;
import com.dlab.common.search.SearchScope;
import com.dlab.common.search.SearchSupport;
import com.dlab.domain.user.entity.*;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

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

    private final JPAQueryFactory queryFactory;

    /**
     * @param scope      지점·연도 범위. 인증 주체에서만 나온다 — 요청 파라미터로 받지 않는다
     * @param keyword    이름·학번 통합 검색어
     * @param grade      학년(고2/고3/N수생)
     * @param track      계열
     * @param status     재원 상태
     * @param classId    반. null이면 전체(미배정 포함)
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
                        keywordMatches(keyword),
                        SearchPredicates.eq(e.grade, grade),
                        SearchPredicates.eq(e.track, track),
                        SearchPredicates.eq(e.enrollmentStatus, status),
                        assignedToClass(classId))
                .orderBy(e.studentNo.asc())
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
                        keywordMatches(keyword),
                        SearchPredicates.eq(e.grade, grade),
                        SearchPredicates.eq(e.track, track),
                        SearchPredicates.eq(e.enrollmentStatus, status),
                        assignedToClass(classId))
                .fetchOne());
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
