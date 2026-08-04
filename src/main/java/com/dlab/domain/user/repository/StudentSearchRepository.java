package com.dlab.domain.user.repository;

import static com.dlab.domain.user.entity.QClassAssignment.classAssignment;
import static com.dlab.domain.user.entity.QStudent.student;
import static com.dlab.domain.user.entity.QStudentEnrollment.studentEnrollment;

import com.dlab.common.search.SearchPredicates;
import com.dlab.common.search.SearchScope;
import com.dlab.common.search.SearchSupport;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.PathBuilder;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/**
 * 학생 통합 검색 (F-4.1-1).
 *
 * <p><b>등록 건(enrollment)을 기준으로 조회한다.</b> 학생(사람)이 아니다 —
 * 학번·재원상태·반·좌석이 전부 등록 건에 붙어 있고, 삼수로 재등록하면 같은 사람에
 * 행이 여러 개 생기기 때문이다. 사람 기준으로 조회하면 어느 해 학번인지가 사라진다.
 */
@Repository
@RequiredArgsConstructor
public class StudentSearchRepository {

    /** 정렬 허용 필드. 여기 없는 값은 무시된다 — 임의 컬럼 정렬로 인덱스를 못 타는 걸 막는다. */
    private static final List<String> SORTABLE =
            List.of("studentNo", "admissionDate", "enrollmentStatus", "grade", "track", "id");

    private final JPAQueryFactory queryFactory;

    public Page<StudentEnrollment> search(SearchScope scope,
                                          StudentSearchCondition condition,
                                          Pageable pageable) {
        List<StudentEnrollment> content = SearchSupport.applyPaging(baseQuery(scope, condition), pageable)
                .orderBy(SearchSupport.toOrders(
                        new PathBuilder<>(StudentEnrollment.class, "studentEnrollment"),
                        pageable.getSort(), SORTABLE))
                .fetch();

        return SearchSupport.page(content, pageable, () -> queryFactory
                .select(studentEnrollment.count())
                .from(studentEnrollment)
                .join(studentEnrollment.student, student)
                .where(where(scope, condition))
                .fetchOne());
    }

    /** Export용. 페이징 없이 전건을 가져온다 — 목록 엑셀 다운로드가 화면 페이지에 갇히면 안 된다. */
    public List<StudentEnrollment> searchAll(SearchScope scope, StudentSearchCondition condition) {
        return baseQuery(scope, condition)
                .orderBy(studentEnrollment.studentNo.asc())
                .fetch();
    }

    private JPAQuery<StudentEnrollment> baseQuery(SearchScope scope, StudentSearchCondition condition) {
        return queryFactory
                .selectFrom(studentEnrollment)
                // 이름·전화번호 조건과 Export가 학생 정보를 쓰므로 항상 함께 가져온다(N+1 방지)
                .join(studentEnrollment.student, student).fetchJoin()
                .where(where(scope, condition));
    }

    private BooleanExpression where(SearchScope scope, StudentSearchCondition condition) {
        StudentSearchCondition c = condition == null ? StudentSearchCondition.empty() : condition;

        return SearchPredicates.and(
                // 지점·연도. 목록 조회는 예외 없이 이걸 먼저 건다.
                SearchPredicates.scope(studentEnrollment.academy.id, studentEnrollment.year, scope),
                // 조건에 연도가 따로 들어오면 그것도 적용(스코프 연도와 함께 걸린다)
                SearchPredicates.yearEq(studentEnrollment.year,
                        c.year() == null ? null : c.year().shortValue()),
                studentEnrollment.deleted.isFalse(),

                keyword(c.keyword()),
                SearchPredicates.contains(student.name, c.name()),
                SearchPredicates.contains(studentEnrollment.studentNo, c.studentNo()),
                SearchPredicates.contains(student.phone, c.phone()),
                SearchPredicates.eq(studentEnrollment.grade, c.grade()),
                SearchPredicates.eq(studentEnrollment.track, c.track()),
                SearchPredicates.in(studentEnrollment.enrollmentStatus, c.statuses()),
                SearchPredicates.contains(student.schoolName, c.schoolName()),
                SearchPredicates.eq(student.gender, c.gender()),
                SearchPredicates.between(studentEnrollment.admissionDate,
                        c.admittedFrom(), c.admittedTo()),
                classAssigned(c.classId()),
                rfidIssued(c.hasRfid()));
    }

    /**
     * 통합 검색 — 이름·학번·전화번호를 한 번에 본다.
     * DSA 실사에서 확인된 사용 패턴이 "검색창 하나에 아무거나 친다"였다.
     */
    private BooleanExpression keyword(String keyword) {
        return SearchPredicates.or(
                SearchPredicates.contains(student.name, keyword),
                SearchPredicates.contains(studentEnrollment.studentNo, keyword),
                SearchPredicates.contains(student.phone, keyword));
    }

    /**
     * 반 배정. 배정은 이력 테이블이라 <b>현재 배정({@code active})만</b> 본다 —
     * 빠뜨리면 작년 반으로도 검색된다.
     */
    private BooleanExpression classAssigned(Long classId) {
        if (classId == null) {
            return null;
        }
        return studentEnrollment.id.in(
                queryFactory.select(classAssignment.enrollment.id)
                        .from(classAssignment)
                        .where(classAssignment.classMaster.id.eq(classId),
                                classAssignment.active.isTrue(),
                                classAssignment.deleted.isFalse()));
    }

    /** 카드 미발급자 추출 — 일괄 발급 전에 대상을 뽑는 운영 흐름이 있다. */
    private BooleanExpression rfidIssued(Boolean hasRfid) {
        if (hasRfid == null) {
            return null;
        }
        return hasRfid ? studentEnrollment.rfidNo.isNotNull() : studentEnrollment.rfidNo.isNull();
    }

}
