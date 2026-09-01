package com.dlab.domain.meal.service;

import com.dlab.domain.meal.entity.MealOrder;
import com.dlab.domain.payment.entity.PaymentTransaction;
import com.dlab.domain.payment.repository.PaymentTransactionRepository;
import com.dlab.domain.user.entity.ClassAssignment;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 급식 주문 목록에 <b>다른 테이블에 있는 표시값</b>(반 이름·결제수단)을 붙인다.
 *
 * <h2>★ 존재 이유는 N+1 방지 하나다</h2>
 * 급식 관리(F-4.5) "결제·취소 내역" 탭은 <b>그 달 신청자 전원</b>이 대상이다.
 * 반은 등록 건에, 결제수단은 청구에 붙어 있어 응답을 만들면서 행마다 조회하면
 * <b>쿼리가 주문 수만큼 나간다.</b>
 *
 * <p>그래서 <b>ID를 모아 IN 조회 2번</b>으로 끝내고 {@code Map}으로 붙인다 —
 * 주문이 10건이든 500건이든 쿼리 수가 같다. 학생 목록·키오스크 학생 목록
 * ({@code KioskStudentQueryService.studentList})이 쓰는 방식을 그대로 따랐다.
 * 주문 자체·끼니 항목·청구는 목록 쿼리에서 fetch join으로 이미 함께 온다.
 *
 * <p>반 배정 조회를 {@code ClassAssignmentRepository}에 두지 않은 것은
 * <b>급식 화면 전용 배치 조회</b>라서다 — 반 도메인의 단건 조회와 성격이 다르고,
 * 여기 두면 이 화면의 쿼리 수가 이 파일 하나만 봐도 드러난다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MealOrderListEnricher {

    private final PaymentTransactionRepository paymentTransactionRepository;

    @PersistenceContext
    private EntityManager em;

    /**
     * 주문 목록에 붙일 표시값을 <b>한 번에</b> 모은다.
     *
     * <p>목록이 비면 조회하지 않는다 — 빈 {@code IN ()}은 DB에 따라 문법 오류가 되거나
     * 전건을 훑는다.
     */
    public Extras of(List<MealOrder> orders) {
        if (orders == null || orders.isEmpty()) {
            return Extras.empty();
        }

        List<Long> enrollmentIds = orders.stream()
                .map(o -> o.getEnrollment().getId()).distinct().toList();

        Map<Long, String> classNames = new HashMap<>();
        em.createQuery("""
                        SELECT ca FROM ClassAssignment ca
                        JOIN FETCH ca.classMaster cm
                        WHERE ca.enrollment.id IN :ids
                          AND ca.classType = com.dlab.domain.user.entity.ClassType.FIXED
                          AND ca.active = true
                          AND ca.deleted = false
                        """, ClassAssignment.class)
                .setParameter("ids", enrollmentIds)
                .getResultList()
                .forEach(ca -> classNames.put(
                        ca.getEnrollment().getId(), ca.getClassMaster().getName()));

        // 청구가 안 붙은 주문은 결제수단을 물을 대상이 아니다 — ID 목록에서 빠진다
        List<Long> billingIds = orders.stream()
                .filter(MealOrder::isBilled)
                .map(o -> o.getBilling().getId())
                .distinct().toList();

        Map<Long, List<String>> methodsByBilling = new HashMap<>();
        if (!billingIds.isEmpty()) {
            for (PaymentTransaction t : paymentTransactionRepository
                    .findActiveByBillingIds(billingIds)) {
                List<String> methods = methodsByBilling
                        .computeIfAbsent(t.getBilling().getId(), k -> new ArrayList<>());
                String name = t.getMethod().name();
                // 분납이면 같은 수단이 여러 번 나온다 — 화면에 "카드, 카드"로 찍히면 안 된다
                if (!methods.contains(name)) {
                    methods.add(name);
                }
            }
        }

        return new Extras(classNames, methodsByBilling);
    }

    /**
     * 표시값 모음.
     *
     * <p>배정이 없는 학생은 <b>키가 아예 없다</b> — 미배정과 "반 이름이 빈 문자열"을
     * 구분하려면 조회 결과에 없는 것이 맞다.
     */
    public record Extras(Map<Long, String> classNames,
                         Map<Long, List<String>> paymentMethodsByBilling) {

        public static Extras empty() {
            return new Extras(Map.of(), Map.of());
        }

        /** 반 이름. 미배정이면 {@code null}. */
        public String className(MealOrder order) {
            return classNames.get(order.getEnrollment().getId());
        }

        /**
         * 결제수단.
         *
         * <p><b>청구 전이거나 아직 안 받았으면 빈 목록</b>이다 — {@code null}을 내리면
         * 화면이 매번 방어해야 한다. 분납이면 여러 개가 나온다.
         */
        public List<String> paymentMethods(MealOrder order) {
            if (!order.isBilled()) {
                return List.of();
            }
            return paymentMethodsByBilling.getOrDefault(order.getBilling().getId(), List.of());
        }
    }
}
