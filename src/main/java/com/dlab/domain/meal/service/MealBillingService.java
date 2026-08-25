package com.dlab.domain.meal.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.meal.entity.MealOrder;
import com.dlab.domain.meal.repository.MealOrderRepository;
import com.dlab.domain.payment.entity.Billing;
import com.dlab.domain.payment.entity.BillingType;
import com.dlab.domain.payment.repository.BillingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * 급식 청구 발행 (F-4.5 · F-4.8-1).
 *
 * <h2>교습비와 다르다 — 항목을 쪼개지 않는다</h2>
 * 교습비는 교습비/독서실비로 나눠야 환불 계산이 되지만, 급식은 <b>날짜·끼니 단위 취소</b>라
 * 구간·일할 환불을 타지 않는다({@code BillingItemType.MEAL}의 {@code RefundMethod.NONE}).
 * 청구는 한 줄이고 상세는 주문 항목이 이미 들고 있다.
 *
 * <h2>★ 금액은 주문에 박힌 단가에서 나온다</h2>
 * 마스터({@code meal_policy.unit_price})를 다시 읽지 않는다. 단가를 올리는 순간
 * <b>과거 주문 청구액이 소급해서 바뀐다.</b> 주문 항목의 스냅샷이 진실이다.
 *
 * <h2>발행 후 취소</h2>
 * 급식은 청구를 낸 뒤에도 취소된다. 그때 <b>청구액과 실제 이용액이 어긋나고</b>
 * 그 차액이 환불 대상이다({@link MealOrder#refundableAmount()}).
 * ⚠️ 실제 환불(PG 취소)은 아직 없다 — 지금은 "얼마가 대상인가"까지다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MealBillingService {

    private final MealOrderRepository orderRepository;
    private final BillingRepository billingRepository;

    /**
     * 주문 한 건을 청구로 발행한다.
     *
     * <p>취소된 끼니는 자동으로 빠진다 — 살아 있는 항목만 센다.
     */
    @Transactional
    public Billing issue(AuthPrincipal me, Long orderId, LocalDate dueDate) {
        MealOrder order = requireOrder(me, orderId);

        if (order.isBilled()) {
            throw new BusinessException(ErrorCode.BILLING_ALREADY_ISSUED,
                    "이미 청구가 발행된 급식 주문입니다.");
        }
        if (order.activeItems().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "신청 내역이 없어 청구할 수 없습니다.");
        }
        // ★ 단가가 안 박힌 주문은 금액을 모른다. 0원으로 청구하면 학생이 공짜로 먹고
        //   나중에 아무도 못 찾는다 — 업체·단가부터 등록해야 한다
        if (order.activeItems().stream().anyMatch(i -> i.getUnitPrice() == null)) {
            throw new BusinessException(ErrorCode.MEAL_UNIT_PRICE_NOT_REGISTERED);
        }

        YearMonth month = order.month();
        Billing billing = billingRepository.save(new Billing(
                order.getEnrollment(),
                "%d년 %d월 급식비".formatted(month.getYear(), month.getMonthValue()),
                BillingType.MEAL, order.totalAmount(), 0, dueDate));
        billing.assignServicePeriod((short) month.getYear(), month.getMonthValue());
        order.assignBilling(billing);

        log.info("급식 청구: orderId={}, {}, 끼니={}건, 금액={}",
                orderId, month, order.activeItems().size(), order.totalAmount());
        return billing;
    }

    /**
     * 그 달 지점 전체를 한 번에 발행.
     *
     * <p>급식은 <b>월 단위 일괄 결제</b>라(A-9) 건별로 누르는 화면이 아니다.
     * 이미 발행됐거나 내역이 없는 주문은 <b>조용히 건너뛴다</b> — 한 건 때문에
     * 전체가 멈추면 데스크가 어디까지 됐는지 모른다.
     *
     * @return 새로 발행된 청구 목록
     */
    @Transactional
    public List<Billing> issueMonth(AuthPrincipal me, Long academyId, YearMonth month,
                                    LocalDate dueDate) {
        if (!me.canAccessAcademy(academyId)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }

        List<MealOrder> orders = orderRepository.findByAcademyAndMonth(academyId, month.atDay(1));
        List<Billing> issued = new java.util.ArrayList<>();
        int skipped = 0;

        for (MealOrder order : orders) {
            if (order.isBilled() || order.activeItems().isEmpty()
                    || order.activeItems().stream().anyMatch(i -> i.getUnitPrice() == null)) {
                skipped++;
                continue;
            }
            issued.add(issue(me, order.getId(), dueDate));
        }

        log.info("급식 일괄 청구: academyId={}, {}, 발행={}건, 건너뜀={}건",
                academyId, month, issued.size(), skipped);
        return issued;
    }

    /**
     * 발행 후 취소로 생긴 <b>환불 대상</b> 목록.
     *
     * <p>급식 취소는 앱(D-n 이내)과 데스크(제한 없음) 두 경로로 들어온다.
     * 어느 쪽이든 청구가 이미 나갔으면 차액이 남는다.
     *
     * <p>⚠️ 클라이언트가 <i>"환불은 제한없이 가능하도록"</i>을 요청했으나 앱에서도 열지는
     * 확인 대기 중이다. 여기는 <b>경로와 무관하게 금액만</b> 집계한다.
     */
    @Transactional(readOnly = true)
    public List<Refundable> refundables(AuthPrincipal me, Long academyId, YearMonth month) {
        if (!me.canAccessAcademy(academyId)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return orderRepository.findByAcademyAndMonth(academyId, month.atDay(1)).stream()
                .filter(MealOrder::isBilled)
                .filter(o -> o.refundableAmount() > 0)
                .map(o -> new Refundable(o, o.refundableAmount()))
                .toList();
    }

    private MealOrder requireOrder(AuthPrincipal me, Long orderId) {
        MealOrder order = orderRepository.findById(orderId)
                .filter(o -> !o.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.MEAL_ORDER_NOT_FOUND));
        if (!me.canAccessAcademy(order.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return order;
    }

    /** @param amount 청구액 − 현재 이용액. 취소된 끼니만큼이다 */
    public record Refundable(MealOrder order, int amount) {
    }
}
