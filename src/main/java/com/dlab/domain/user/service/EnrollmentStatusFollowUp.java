package com.dlab.domain.user.service;

import com.dlab.domain.user.entity.EnrollmentStatus;
import com.dlab.domain.user.entity.StudentEnrollment;
import java.time.LocalDate;

/**
 * 상태 전이 후속처리 (P1-04).
 *
 * <p>실행가이드가 <i>"상태 전이 시 후속처리(배정 해제·급식 중단·앱 계정 비활성)를
 * 같은 트랜잭션으로"</i>를 요구한다. 그런데 <b>급식·좌석 도메인은 아직 없다.</b>
 * 지금 처리 가능한 것만 서비스에 직접 박아두면, 나중에 그 도메인을 만드는 사람이
 * "여기도 손대야 한다"는 걸 알아채야만 후속처리가 추가된다 — 알아채지 못하면
 * 퇴원생 급식이 계속 결제된다.
 *
 * <p>그래서 <b>후속처리를 확장점으로 뒤집었다.</b> 각 도메인이 자기 구현체를 빈으로
 * 등록하면 이 서비스는 수정 없이 호출한다. 새 도메인은 자기 패키지 안에서만 작업하면 된다.
 *
 * <p>구현체는 <b>{@link StudentStatusService}의 트랜잭션 안에서</b> 실행된다.
 * 예외를 던지면 상태 변경 자체가 롤백된다 — 후속처리가 반만 되는 것보다
 * 아예 안 되는 쪽이 낫기 때문이다. 실패해도 진행해야 하는 처리(알림 발송 등)라면
 * 여기가 아니라 이벤트로 뺄 것.
 */
public interface EnrollmentStatusFollowUp {

    void apply(Change change);

    /**
     * @param from          이전 상태
     * @param to            새 상태
     * @param effectiveDate 효력 발생일. 처리일과 다를 수 있다
     */
    record Change(
            StudentEnrollment enrollment,
            EnrollmentStatus from,
            EnrollmentStatus to,
            LocalDate effectiveDate
    ) {

        /** 등록이 끝났는가 — 배정 해제·급식 중단의 기준. */
        public boolean isTerminating() {
            return to.isTerminal();
        }

        /** 재원으로 돌아왔는가(휴원 복귀 또는 착오 정정). */
        public boolean isReturningToActive() {
            return to == EnrollmentStatus.ENROLLED;
        }
    }
}
