package com.dlab.domain.user.service;

import com.dlab.domain.user.entity.EnrollmentStatus;
import com.dlab.domain.user.entity.StudentEnrollment;
import java.time.Instant;
import java.util.List;

/**
 * 재원 종료(퇴원·제적·수료) 후속처리 확장 지점.
 *
 * <p><b>왜 인터페이스인가.</b> 실행가이드는 배정 해제·급식 중단·앱 계정 비활성을
 * <b>같은 트랜잭션</b>으로 요구하는데, 후속처리가 필요한 도메인은 계속 늘어난다.
 * {@code StudentStatusService}에 직접 박으면 <b>새 도메인을 만드는 사람이 이 파일을
 * 알아채야만</b> 추가되고, 못 알아채면 조용히 빠진다 — 실제로 급식이 그랬다.
 * 퇴원생 급식이 계속 결제되는데 상태 화면은 정상으로 보인다.
 *
 * <p>각 도메인은 <b>자기 패키지에 빈 하나만</b> 등록하면 된다. 등록만 하면 자동으로 불린다.
 *
 * <p><b>휴원은 대상이 아니다.</b> 돌아올 학생의 자리·신청을 정리하면 복귀 때 다시
 * 만들어야 하고, 그 사이 다른 학생이 자리를 가져간다.
 */
public interface EnrollmentStatusFollowUp {

    /**
     * 정리 수행.
     *
     * <p>호출자의 트랜잭션 안에서 실행된다 — <b>여기서 예외를 던지면 상태 변경 자체가
     * 되돌아간다.</b> 정리에 실패해도 퇴원은 되어야 하는 종류라면 예외 대신
     * {@link Note}로 남길 것.
     *
     * @param to 무엇으로 끝났는가. 퇴원·제적은 환불 대상이고 수료는 아니다
     * @return 관리자 화면에 표시할 결과. 없으면 빈 목록
     */
    List<Note> onEnrollmentEnded(StudentEnrollment enrollment, EnrollmentStatus to, Instant at);

    /**
     * 후속처리 결과 한 줄.
     *
     * <p><b>화면에 그대로 띄우기 위한 것이다.</b> 로그로만 남기면 퇴원 처리한 사람은
     * "급식이 몇 건 취소됐는지"도, "미납이 남았는지"도 모른 채 화면을 닫는다.
     *
     * @param area    도메인 이름. 화면이 묶어서 보여준다
     * @param message 사람이 읽는 문장
     * @param blocking 사람이 이어서 처리해야 하는 일인가. 미납 채권처럼
     *                 <b>시스템이 끝내지 못하는 것</b>에 표시한다
     */
    record Note(String area, String message, boolean blocking) {

        public static Note done(String area, String message) {
            return new Note(area, message, false);
        }

        /** 사람이 이어서 처리해야 하는 것. 화면이 눈에 띄게 표시한다. */
        public static Note actionRequired(String area, String message) {
            return new Note(area, message, true);
        }
    }
}
