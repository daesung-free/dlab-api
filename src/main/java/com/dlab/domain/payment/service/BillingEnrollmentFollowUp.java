package com.dlab.domain.payment.service;

import com.dlab.domain.payment.entity.Billing;
import com.dlab.domain.payment.entity.BillingStatus;
import com.dlab.domain.payment.repository.BillingRepository;
import com.dlab.domain.user.entity.EnrollmentStatus;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.service.EnrollmentStatusFollowUp;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 퇴원 시 미납 확인.
 *
 * <h2>★ 아무것도 바꾸지 않는다</h2>
 * 미납 청구를 <b>취소하지도, 상태를 옮기지도 않는다.</b> 퇴원했다고 낼 돈이 없어지는 게
 * 아니라서, 자동으로 정리하면 <b>받을 돈이 장부에서 사라진다.</b>
 *
 * <p>실행가이드가 요구하는 "미납 채권 이관"의 <b>이관은 아직 못 한다</b> —
 * 채권 관리 도메인이 없고, 어디로 어떤 형태로 넘길지도 정해지지 않았다.
 * 대신 <b>남아 있다는 사실을 퇴원 처리한 사람에게 그 자리에서 알린다.</b>
 * 로그로만 남기면 화면을 닫는 순간 아무도 모른다.
 *
 * <p>채권 도메인이 생기면 이 빈이 이관 지점이 된다 — 지금 임시 테이블을 만들어 흉내 내면
 * 그때 이중 처리가 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BillingEnrollmentFollowUp implements EnrollmentStatusFollowUp {

    private final BillingRepository billingRepository;

    @Override
    public List<Note> onEnrollmentEnded(StudentEnrollment enrollment, EnrollmentStatus to,
                                        Instant at) {
        List<Billing> unpaid = billingRepository.findByEnrollment(enrollment.getId()).stream()
                .filter(BillingEnrollmentFollowUp::isUnpaid)
                .toList();

        if (unpaid.isEmpty()) {
            return List.of();
        }
        int amount = unpaid.stream().mapToInt(Billing::getBilledAmount).sum();
        log.warn("퇴원 처리했으나 미납이 남음: enrollmentId={}, 건수={}, 금액={}",
                enrollment.getId(), unpaid.size(), amount);

        return List.of(Note.actionRequired("수납",
                "미납 %d건 %,d원이 남아 있습니다. 채권 처리는 자동으로 넘어가지 않습니다."
                        .formatted(unpaid.size(), amount)));
    }

    /**
     * 아직 받지 못한 청구인가.
     *
     * <p>가상계좌 발급분({@code ISSUED})과 기한 만료분({@code EXPIRED})도 미납이다 —
     * {@code PENDING}만 세면 <b>발급까지 갔다가 안 낸 건이 통째로 빠진다.</b>
     */
    private static boolean isUnpaid(Billing billing) {
        BillingStatus status = billing.getStatus();
        return status == BillingStatus.PENDING
                || status == BillingStatus.ISSUED
                || status == BillingStatus.EXPIRED;
    }
}
