package com.dlab.domain.user.repository;

import com.dlab.domain.user.entity.EnrollmentStatus;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.TrackType;
import java.time.LocalDate;
import java.util.List;

/**
 * 학생 검색 조건 (F-4.1-1).
 *
 * <p><b>{@code academyId}는 여기 없다.</b> 지점 스코프는 요청 파라미터가 아니라
 * 인증 주체에서만 나온다({@code SearchScope}) — 파라미터로 받으면 값을 바꿔 보내는 것만으로
 * 다른 지점 학생이 조회된다.
 *
 * <p>모든 필드가 {@code null} 허용이다. 들어온 조건만 적용된다.
 *
 * @param keyword    통합 검색 — 이름·학번·전화번호를 한 번에 본다.
 *                   DSA 실사에서 확인된 사용 패턴이 "검색창 하나에 아무거나 친다"였다
 * @param statuses   재원 상태. 목록 필터가 "전체/재원생/퇴원생" 3종이라 복수 선택이 필요하다
 * @param hasRfid    카드 발급 여부. 미발급자를 추려 일괄 발급하는 운영 흐름이 있다
 *
 * <p>좌석 배정 여부 필터는 아직 없다 — {@code seat_assignment} 엔티티가 Phase 2 담당이라
 * 조회할 방법이 없다. 조건만 만들어두고 무시하면 사용자에게는 버그로 보이므로 뺐다.
 */
public record StudentSearchCondition(
        String keyword,
        Integer year,
        String name,
        String studentNo,
        String phone,
        GradeType grade,
        TrackType track,
        List<EnrollmentStatus> statuses,
        Long classId,
        String schoolName,
        String gender,
        LocalDate admittedFrom,
        LocalDate admittedTo,
        Boolean hasRfid
) {

    /** 조건 없이 전체 조회. 지점·연도는 SearchScope가 따로 강제한다. */
    public static StudentSearchCondition empty() {
        return new StudentSearchCondition(null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }
}
