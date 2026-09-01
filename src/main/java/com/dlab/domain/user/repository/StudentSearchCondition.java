package com.dlab.domain.user.repository;

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
 * @param keyword        이름·학번 통합 검색어
 * @param grade          학년(고2/고3/N수생)
 * @param track          계열
 * @param status         재원 상태
 * @param classId        반. null이면 전체(미배정 포함)
 * @param teacherId      담임(담당선생님). 반을 통해 걸린다 — 담임은 학생이 아니라 반에 붙는다
 * @param schoolName     출신학교. 부분 일치다("서울고"로 "서울고등학교"가 걸려야 한다)
 * @param admittedFrom   등원일(입학일) 시작. 한쪽만 넣어도 동작한다
 * @param admittedTo     등원일(입학일) 끝
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
        LocalDate admittedTo
) {

    /** 조건 없음. 전체 조회·테스트용. */
    public static StudentSearchCondition none() {
        return new StudentSearchCondition(null, null, null, null, null, null, null, null, null);
    }
}
