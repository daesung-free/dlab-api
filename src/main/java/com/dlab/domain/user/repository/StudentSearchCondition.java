package com.dlab.domain.user.repository;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.user.entity.EnrollmentStatus;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.TrackType;

import java.time.LocalDate;

/**
 * 학생 검색 조건 묶음 (F-4.1-1).
 *
 * <p><b>조건을 인자로 늘어놓지 않고 한 덩어리로 받는다.</b> 요구사항이 12개 조건을 요구해
 * 인자로 두면 호출부가 {@code null, null, null}로 채워지고, 조건이 하나 늘 때마다
 * 검색·Export·컨트롤러 시그니처가 함께 흔들린다.
 *
 * <p>모든 필드가 {@code null}을 허용한다 — {@code null}은 "그 조건 없음"이다.
 * {@link com.dlab.common.search.SearchPredicates}가 null을 그대로 무시하므로
 * 호출부에서 {@code if}로 감쌀 필요가 없다.
 *
 * <h2>★ 미배정 조건은 "화면 안에서 거르기"를 대체한다</h2>
 * 반 배정 화면의 본체가 <b>미배정 학생 목록</b>인데 서버 조건이 없어서, 프론트가 받아온
 * 한 페이지 안에서 반 이름이 빈 학생만 걸러 쓰고 있었다. 그건 <b>그 페이지의 미배정</b>이지
 * 전체 명단이 아니고, 총계도 페이징도 맞지 않는다. 장학생 명단 탭도 같은 문제였다.
 * 그래서 반·좌석·사물함·장학을 전부 서버 조건으로 연다.
 *
 * <h2>★ 모순되는 조합은 400으로 되돌린다</h2>
 * "3반이면서 반이 없는 학생"은 결과가 0건인 게 정답처럼 보이지만, 그렇게 두면
 * 화면이 조건을 잘못 조합한 채 <b>"해당 학생이 없다"로 읽고 조용히 넘어간다.</b>
 * 조건 자체가 성립하지 않는 조합은 빈 목록이 아니라 오류다 —
 * {@link ErrorCode#CONFLICTING_SEARCH_CONDITION}.
 *
 * @param keyword          이름·학번 통합 검색어
 * @param grade            학년(고2/고3/N수생)
 * @param track            계열
 * @param status           재원 상태
 * @param classId          반. null이면 전체(미배정 포함)
 * @param teacherId        담임(담당선생님). 반을 통해 걸린다 — 담임은 학생이 아니라 반에 붙는다
 * @param schoolName       출신학교. 부분 일치다("서울고"로 "서울고등학교"가 걸려야 한다)
 * @param admittedFrom     등원일(입학일) 시작. 한쪽만 넣어도 동작한다
 * @param admittedTo       등원일(입학일) 끝
 * @param unassignedClass  고정반 배정 여부. {@code true}=미배정만, {@code false}=배정된 학생만.
 *                         {@code classId}·{@code teacherId}와 <b>동시에 true면 모순</b>이라 오류다
 * @param unassignedSeat   좌석 배정 여부. {@code true}=미배정만, {@code false}=배정된 학생만
 * @param unassignedLocker 사물함 배정 여부. {@code true}=미배정만, {@code false}=배정된 학생만
 * @param hasScholarship   장학 보유 여부. {@code true}=장학생만, {@code false}=장학 없는 학생만
 * @param scholarshipType  장학 종류. 그 종류를 가진 학생만.
 *                         {@code hasScholarship=false}와 <b>동시에 오면 모순</b>이라 오류다
 */
public record StudentSearchCondition(
        String keyword,
        GradeType grade,
        TrackType track,
        EnrollmentStatus status,
        Long classId,
        Long teacherId,
        String schoolName,
        LocalDate admittedFrom,
        LocalDate admittedTo,
        Boolean unassignedClass,
        Boolean unassignedSeat,
        Boolean unassignedLocker,
        Boolean hasScholarship,
        String scholarshipType
) {

    public StudentSearchCondition {
        if (Boolean.TRUE.equals(unassignedClass) && (classId != null || teacherId != null)) {
            throw new BusinessException(ErrorCode.CONFLICTING_SEARCH_CONDITION);
        }
        if (Boolean.FALSE.equals(hasScholarship) && isPresent(scholarshipType)) {
            throw new BusinessException(ErrorCode.CONFLICTING_SEARCH_CONDITION);
        }
        scholarshipType = isPresent(scholarshipType) ? scholarshipType.trim() : null;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    /** 조건 없음. 전체 조회·테스트용. */
    public static StudentSearchCondition none() {
        return new StudentSearchCondition(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }
}
