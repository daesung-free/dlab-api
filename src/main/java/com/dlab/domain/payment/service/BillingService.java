package com.dlab.domain.payment.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.payment.entity.Billing;
import com.dlab.domain.payment.entity.BillingStatus;
import com.dlab.domain.payment.entity.BillingType;
import com.dlab.domain.payment.entity.PaymentMethod;
import com.dlab.domain.payment.entity.PaymentTransaction;
import com.dlab.domain.payment.repository.BillingRepository;
import com.dlab.domain.payment.repository.PaymentTransactionRepository;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 청구·수납 (F-4.8-1).
 *
 * <h2>범위가 좁다 — 키오스크 3.29를 채우는 데 필요한 만큼이다</h2>
 * 수납현황 화면(통계·미납자 알림·엑셀)과 청구기준 관리(F-4.10-5)는 아직이다.
 * 막는 것들: <b>E-3</b>(PG 가맹점정보·API 스펙) · <b>I-25</b>(현금영수증·납입증명서) ·
 * <b>I-26</b>(환불 일할계산 산식) · 할인 정책 미확정.
 *
 * <h2>PG는 없다</h2>
 * 수납은 <b>수기 기록</b>이다. 결제가 붙으면 {@code PaymentTransaction}에
 * {@code pgTid}를 채우고 상태 전이만 이어 붙이면 된다 — 구조는 안 바뀐다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingService {

    private final BillingRepository billingRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<Billing> findByAcademy(AuthPrincipal me, Long academyId, short year) {
        Long resolved = me.requireAcademyScope(academyId);
        return billingRepository.findByAcademyAndYear(resolved, year);
    }

    /** 단건 조회. 수납 기록 뒤 갱신된 값을 돌려줄 때 쓴다. */
    @Transactional(readOnly = true)
    public Billing findByStudentBilling(AuthPrincipal me, Long billingId) {
        return requireBilling(me, billingId);
    }

    @Transactional(readOnly = true)
    public List<Billing> findByStudent(AuthPrincipal me, Long enrollmentId) {
        requireEnrollment(me, enrollmentId);
        return billingRepository.findByEnrollment(enrollmentId);
    }

    /**
     * 청구 생성.
     *
     * <p>할인은 값으로 받는다 — <b>할인 정책(장학 연동)이 미확정</b>이라 여기서 산출하지
     * 않는다. 화면 주석도 <i>"할인 정책이 빠져서 정가가 그대로 결제로 넘어간다"</i>고
     * 짚어놨다. 정책이 확정되면 이 앞단에 계산기가 붙는다.
     */
    @Transactional
    public Billing create(AuthPrincipal me, Long enrollmentId, String name,
                          BillingType type, int suppliedAmount, int discountAmount,
                          LocalDate dueDate) {
        return create(me, enrollmentId, name, type, suppliedAmount, discountAmount, dueDate, false);
    }

    /**
     * @param allowDuplicate 같은 이름으로 또 청구한다. <b>기본은 막는다</b> — 데스크가 저장을
     *                       두 번 누르면 같은 특강이 두 건 잡혀 미납이 두 배가 되고 그대로
     *                       독촉이 나간다. 재수강처럼 <b>정말 두 번 받는 경우</b>에만 켠다
     */
    @Transactional
    public Billing create(AuthPrincipal me, Long enrollmentId, String name,
                          BillingType type, int suppliedAmount, int discountAmount,
                          LocalDate dueDate, boolean allowDuplicate) {

        StudentEnrollment enrollment = requireEnrollment(me, enrollmentId);
        if (!allowDuplicate && billingRepository.existsSameNamed(enrollmentId, type, name)) {
            throw new BusinessException(ErrorCode.BILLING_ALREADY_ISSUED,
                    "'%s' 청구가 이미 있습니다. 다시 청구하려면 중복 허용을 체크하세요."
                            .formatted(name));
        }
        if (suppliedAmount < 0 || discountAmount < 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "금액은 0 이상이어야 합니다.");
        }
        if (discountAmount > suppliedAmount) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "할인이 정가보다 큽니다.");
        }

        Billing billing = billingRepository.save(new Billing(
                enrollment, name, type, suppliedAmount, discountAmount, dueDate));

        log.info("청구 생성: enrollmentId={}, 항목={}, 정가={}, 할인={}, 청구액={}",
                enrollmentId, name, suppliedAmount, discountAmount, billing.getBilledAmount());
        return billing;
    }

    /**
     * 수납 기록.
     *
     * <p><b>분납을 허용한다</b> — 청구 1건에 여러 건이 붙는다. 완납되면 상태가
     * {@code PAID}로 올라가고 미납자 목록에서 빠진다.
     *
     * <p>초과 입금은 막지 않는다 — 실무에서 실제로 생기고, 막으면 데스크가
     * 기록 자체를 안 남긴다. 미납액은 음수로 내려가지 않게 0에서 멈춘다.
     */
    @Transactional
    public PaymentTransaction pay(AuthPrincipal me, Long billingId, int amount,
                                  PaymentMethod method) {
        Billing billing = requireBilling(me, billingId);
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "수납액은 0보다 커야 합니다.");
        }
        if (billing.getStatus() == BillingStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "취소된 청구입니다.");
        }

        PaymentTransaction tx = billing.addPayment(amount, method, Instant.now(clock));
        log.info("수납 기록: billingId={}, 금액={}, 수단={}, 미납={}",
                billingId, amount, method, billing.unpaidAmount());
        return tx;
    }

    /**
     * 수납 취소.
     *
     * <p>거래를 지우지 않고 취소 표시만 한다 — 수납 이력이 사라지면 정산 추적이 끊긴다.
     * 취소하면 청구가 다시 미납으로 내려간다.
     */
    @Transactional
    public void cancelPayment(AuthPrincipal me, Long transactionId) {
        PaymentTransaction tx = transactionRepository.findById(transactionId)
                .filter(t -> !t.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
                        "수납 내역을 찾을 수 없습니다."));
        requireBilling(me, tx.getBilling().getId());

        tx.cancel(Instant.now(clock));
        tx.getBilling().refreshStatus();
    }

    /** 청구 취소. 환불 산출은 하지 않는다 — I-26(일할계산) 미확정이다. */
    @Transactional
    public void cancel(AuthPrincipal me, Long billingId) {
        requireBilling(me, billingId).cancel();
    }

    private Billing requireBilling(AuthPrincipal me, Long billingId) {
        Billing billing = billingRepository.findById(billingId)
                .filter(b -> !b.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
                        "청구를 찾을 수 없습니다."));
        if (!me.canAccessAcademy(billing.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return billing;
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
}
