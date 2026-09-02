package com.dlab.domain.payment.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.payment.entity.*;
import com.dlab.domain.payment.repository.BillingRepository;
import com.dlab.domain.payment.repository.RetroactiveChargeRepository;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 할인 소급 재결제 (0820 규정 · 판정 2026-08-26 확정).
 *
 * <h2>환불이 아니라 징수다</h2>
 * 퇴원할 때 그동안 받은 할인을 <b>정상가로 다시 받는다.</b>
 *
 * <h2>★ 판정 — 퇴원하는 달이 할인 기간 안이냐 밖이냐</h2>
 * <ul>
 *   <li>퇴원한 달 청구가 <b>할인을 받았으면</b> → 할인 기간 안 → <b>이전 할인분 전부 소급</b></li>
 *   <li>퇴원한 달 청구가 <b>정상가면</b> → 할인이 끝난 뒤 → <b>소급 없음</b></li>
 * </ul>
 * 규정 예시가 그대로 재현된다 — <i>"3~5월 할인 + 6월 정상가 연장 → 6월 퇴원 시 소급 없음"</i>,
 * <i>"3~5월 할인 → 5월 퇴원 시 전부 정상가 재결제"</i>.
 *
 * <p><b>별도 "할인 기간" 테이블을 두지 않는다.</b> 청구가 이미 그 사실을 들고 있고,
 * 기간을 따로 관리하면 청구와 어긋났을 때 <b>어느 쪽이 진실인지 알 수 없다.</b>
 *
 * <h2>★ 퇴원한 달은 대상이 아니다</h2>
 * 그 달은 {@link RefundCalculator}가 <b>"납부액 − 정가 × 차감비율"</b>로 이미 계산하고,
 * 할인받았으면 결과가 음수(=추가 징수)로 나온다.
 * 여기에 또 넣으면 <b>같은 달을 두 번 받는다.</b>
 *
 * <h2>독서실비는 대상이 아니다</h2>
 * 규정상 <b>할인이 없어서</b> 되받을 것도 없다. 교습비 항목만 본다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetroactiveChargeService {

    private final BillingRepository billingRepository;
    private final RetroactiveChargeRepository chargeRepository;
    private final StudentEnrollmentRepository enrollmentRepository;

    /**
     * 퇴원 시 소급 대상을 계산한다. <b>청구를 만들지는 않는다</b> —
     * 데스크가 금액을 확인하고 진행 여부를 정할 수 있어야 한다.
     */
    @Transactional(readOnly = true)
    public Preview preview(AuthPrincipal me, Long enrollmentId, LocalDate withdrawalDate) {
        StudentEnrollment enrollment = requireEnrollment(me, enrollmentId);
        return calculate(enrollment, withdrawalDate);
    }

    /**
     * 소급 청구 발행.
     *
     * <p>대상이 없으면 <b>청구를 만들지 않고 빈 결과를 돌려준다</b> — 0원짜리 청구가
     * 목록에 쌓이면 미납자 화면이 지저분해지고, 데스크가 "낸 건가?" 하고 다시 본다.
     */
    @Transactional
    public Result charge(AuthPrincipal me, Long enrollmentId, LocalDate withdrawalDate,
                         LocalDate dueDate) {

        StudentEnrollment enrollment = requireEnrollment(me, enrollmentId);
        Preview preview = calculate(enrollment, withdrawalDate);

        if (!preview.hasCharge()) {
            log.info("소급 재결제 대상 없음: enrollmentId={}, 퇴원일={}, 사유={}",
                    enrollmentId, withdrawalDate, preview.reason());
            return new Result(null, List.of(), preview.reason());
        }

        Billing charge = billingRepository.save(new Billing(
                enrollment, "할인 소급 재결제 (%d건)".formatted(preview.targets().size()),
                BillingType.TUITION, preview.totalAmount(), 0, dueDate));
        // ★ 이용 월을 비운다. 특정 달 이용분이 아니라 여러 달을 되받는 것이라
        //   달을 지정하면 그 달 교습비 청구와 중복으로 잡힌다
        charge.addItem(BillingItemType.TUITION, preview.totalAmount(), 0);

        List<RetroactiveCharge> rows = preview.targets().stream()
                .map(t -> chargeRepository.save(
                        new RetroactiveCharge(charge, t.billing(), t.supplied(), t.paid())))
                .toList();

        log.info("소급 재결제 발행: enrollmentId={}, 퇴원일={}, {}건, 금액={}",
                enrollmentId, withdrawalDate, rows.size(), preview.totalAmount());
        return new Result(charge, rows, preview.reason());
    }

    // ─────────────────────────────────────────── 판정

    private Preview calculate(StudentEnrollment enrollment, LocalDate withdrawalDate) {
        YearMonth withdrawalMonth = YearMonth.from(withdrawalDate);

        List<Billing> tuitions = billingRepository.findByEnrollment(enrollment.getId()).stream()
                .filter(b -> b.getBillingType() == BillingType.TUITION)
                .filter(b -> b.getServiceYear() != null && b.getServiceMonth() != null)
                .sorted(Comparator.comparing(Billing::getServiceYear)
                        .thenComparing(Billing::getServiceMonth))
                .toList();

        Billing atWithdrawal = tuitions.stream()
                .filter(b -> monthOf(b).equals(withdrawalMonth))
                .findFirst()
                .orElse(null);

        if (atWithdrawal == null) {
            // 퇴원한 달 청구가 없으면 할인 기간 안인지 밖인지 판정할 근거가 없다.
            // 임의로 소급하면 안 받아야 할 돈을 받는다 — 사람이 확인해야 한다
            return Preview.none("퇴원한 달의 교습비 청구가 없어 판정할 수 없습니다.");
        }
        if (!isDiscounted(atWithdrawal)) {
            // 규정: 할인이 끝나고 정상가로 다니다 퇴원 → 소급 없음
            return Preview.none("퇴원한 달을 정상가로 결제해 소급 대상이 아닙니다.");
        }

        // ★ 퇴원한 달보다 앞선 달 중 할인받은 것만. 퇴원한 달은 환불 계산이 처리한다
        List<Billing> candidates = tuitions.stream()
                .filter(b -> monthOf(b).isBefore(withdrawalMonth))
                .filter(RetroactiveChargeService::isDiscounted)
                .toList();

        Set<Long> alreadyCharged = Set.copyOf(chargeRepository.findAlreadyChargedSourceIds(
                candidates.stream().map(Billing::getId).toList()));

        List<Target> targets = new ArrayList<>();
        for (Billing billing : candidates) {
            if (alreadyCharged.contains(billing.getId())) {
                // 두 번 소급되면 학생이 두 번 낸다. 조용히 건너뛰지 않고 로그를 남긴다
                log.warn("이미 소급된 청구라 건너뜀: billingId={}, {}년 {}월",
                        billing.getId(), billing.getServiceYear(), billing.getServiceMonth());
                continue;
            }
            BillingItem tuition = tuitionItemOf(billing);
            targets.add(new Target(billing, tuition.getSuppliedAmount(),
                    tuition.getBilledAmount()));
        }

        if (targets.isEmpty()) {
            return Preview.none("소급할 이전 할인 내역이 없습니다.");
        }
        return new Preview(targets, "퇴원한 달이 할인 기간 안이라 이전 할인분을 소급합니다.");
    }

    /**
     * 할인받은 청구인가.
     *
     * <p><b>교습비 항목만 본다.</b> 독서실비는 규정상 할인이 없고, 청구 전체의
     * {@code discountAmount}를 보면 항목이 없는 옛 청구에서 판정이 흔들린다.
     */
    private static boolean isDiscounted(Billing billing) {
        return billing.activeItems().stream()
                .filter(i -> i.getItemType() == BillingItemType.TUITION)
                .anyMatch(BillingItem::isDiscounted);
    }

    private static BillingItem tuitionItemOf(Billing billing) {
        return billing.activeItems().stream()
                .filter(i -> i.getItemType() == BillingItemType.TUITION)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
                        "교습비 항목이 없는 청구입니다: billingId=" + billing.getId()));
    }

    private static YearMonth monthOf(Billing billing) {
        return YearMonth.of(billing.getServiceYear(), billing.getServiceMonth());
    }

    private StudentEnrollment requireEnrollment(AuthPrincipal me, Long enrollmentId) {
        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));
        if (!me.canAccessAcademy(enrollment.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return enrollment;
    }

    /** 소급 대상 한 건. */
    public record Target(Billing billing, int supplied, int paid) {

        public int chargeAmount() {
            return Math.max(0, supplied - paid);
        }
    }

    /**
     * 계산 결과. <b>대상이 없을 때도 이유를 담는다</b> —
     * 화면에 "소급 없음"만 뜨면 데스크가 맞는지 확인할 방법이 없다.
     */
    public record Preview(List<Target> targets, String reason) {

        static Preview none(String reason) {
            return new Preview(List.of(), reason);
        }

        public boolean hasCharge() {
            return totalAmount() > 0;
        }

        public int totalAmount() {
            return targets.stream().mapToInt(Target::chargeAmount).sum();
        }
    }

    /** @param billing 발행된 소급 청구. 대상이 없으면 {@code null} */
    public record Result(Billing billing, List<RetroactiveCharge> rows, String reason) {

        public int totalAmount() {
            return rows.stream().mapToInt(RetroactiveCharge::getChargeAmount).sum();
        }
    }
}
