package com.dlab.domain.meal.service;

import com.dlab.domain.user.entity.EnrollmentStatus;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.service.EnrollmentStatusFollowUp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 퇴원 시 급식 정리.
 *
 * <p><b>이게 없으면 퇴원생 급식이 계속 결제된다.</b> 신청은 월 단위로 미리 넣어두는
 * 구조라, 상태만 바꾸고 두면 남은 달치가 그대로 살아 있다. 게다가 화면에는
 * 정상으로 보여서 <b>정산할 때까지 아무도 모른다.</b>
 *
 * <p>정리 기준일은 <b>오늘</b>이다. 퇴원 효력일(소급 처리)이 아니라 오늘로 잡는 이유는,
 * 소급이면 <b>이미 먹은 급식까지 취소</b>돼 식수 정산과 어긋나기 때문이다.
 */
@Component
@RequiredArgsConstructor
public class MealEnrollmentFollowUp implements EnrollmentStatusFollowUp {

    private final MealOrderService mealOrderService;
    private final Clock clock;

    @Override
    public List<Note> onEnrollmentEnded(StudentEnrollment enrollment, EnrollmentStatus to,
                                        Instant at) {
        int canceled = mealOrderService.cancelByWithdrawal(
                enrollment.getId(), LocalDate.now(clock));

        if (canceled == 0) {
            return List.of();
        }
        // 결제가 붙기 전이라 환불은 사람이 판단한다. 조용히 지나가면 학생만 손해다
        return List.of(Note.actionRequired("급식",
                "남은 급식 신청 %d건을 취소했습니다. 결제분이 있으면 환불 처리가 필요합니다."
                        .formatted(canceled)));
    }
}
