package com.dlab.api.meal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.meal.entity.CancelPath;
import com.dlab.domain.meal.entity.MealOrder;
import com.dlab.domain.meal.entity.MealOrderStatus;
import com.dlab.domain.meal.entity.MealType;
import com.dlab.domain.meal.service.MealBillingService;
import com.dlab.domain.payment.entity.Billing;
import com.dlab.domain.payment.entity.BillingType;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 급식 청구 발행 (F-4.5 · F-4.8-1).
 *
 * <p>지키려는 것은 셋 — <b>금액은 주문에 박힌 단가에서 나온다</b>,
 * <b>취소된 끼니는 빠진다</b>, <b>발행 후 취소는 환불 대상으로 남는다</b>.
 */
@SpringBootTest
@Transactional
class MealBillingTest {

    @Autowired MealBillingService mealBillingService;
    @Autowired EntityManager em;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    static final short YEAR = 2097;
    static final YearMonth MONTH = YearMonth.of(YEAR, 3);

    Academy academy;
    StudentEnrollment enrollment;
    AuthPrincipal admin;

    @BeforeEach
    void setUp() {
        academy = new Academy("M1", "급식청구테스트", LocalTime.of(9, 0));
        em.persist(academy);

        Student student = new Student("MB-0001", "김급식", "010-0000-0000");
        em.persist(student);
        enrollment = new StudentEnrollment(student, academy, YEAR, "2097-0001", null,
                GradeType.N_SU);
        em.persist(enrollment);
        em.flush();

        admin = AuthPrincipal.of(1L, "EMPLOYEE", null, List.of(Role.SUPER_ADMIN), true);
    }

    private MealOrder order(int... days) {
        MealOrder order = new MealOrder(enrollment, MONTH);
        for (int d : days) {
            order.addItem(LocalDate.of(YEAR, 3, d), MealType.LUNCH, 7700);
        }
        em.persist(order);
        em.flush();
        return order;
    }

    // ─────────────────────────────────────────── 발행

    @Test
    @DisplayName("★ 금액은 주문에 박힌 단가에서 나온다 — 마스터를 다시 읽지 않는다")
    void amountComesFromSnapshot() {
        MealOrder o = order(2, 3, 4);

        Billing billing = mealBillingService.issue(admin, o.getId(), null);

        assertThat(billing.getBilledAmount()).isEqualTo(23_100);   // 7,700 × 3
        assertThat(billing.getBillingType()).isEqualTo(BillingType.MEAL);
        assertThat(billing.getServiceMonth()).isEqualTo((short) 3);
    }

    @Test
    @DisplayName("★ 교습비와 달리 항목을 쪼개지 않는다 — 급식은 구간·일할 환불을 안 탄다")
    void mealBillingHasNoItems() {
        MealOrder o = order(2, 3);

        Billing billing = mealBillingService.issue(admin, o.getId(), null);

        assertThat(billing.activeItems()).isEmpty();
        // 항목이 없어도 합계 검증은 통과해야 한다
        assertThat(billing.itemsMatchBilledAmount()).isTrue();
    }

    @Test
    @DisplayName("발행하면 주문이 ISSUED로 올라가고 청구가 연결된다")
    void orderIsLinkedAndIssued() {
        MealOrder o = order(2);

        Billing billing = mealBillingService.issue(admin, o.getId(), null);

        assertThat(o.getStatus()).isEqualTo(MealOrderStatus.ISSUED);
        assertThat(o.isBilled()).isTrue();
        assertThat(o.getBilling().getId()).isEqualTo(billing.getId());
    }

    @Test
    @DisplayName("발행 전에 취소된 끼니는 애초에 안 들어간다")
    void canceledBeforeIssueIsExcluded() {
        MealOrder o = order(2, 3, 4);
        o.activeItems().get(0).cancel(Instant.now(), CancelPath.APP);
        em.flush();

        Billing billing = mealBillingService.issue(admin, o.getId(), null);

        assertThat(billing.getBilledAmount()).isEqualTo(15_400);   // 7,700 × 2
    }

    // ─────────────────────────────────────────── 발행 후 취소

    @Test
    @DisplayName("★★ 발행 후 취소된 만큼이 환불 대상으로 남는다")
    void canceledAfterIssueBecomesRefundable() {
        MealOrder o = order(2, 3, 4);
        mealBillingService.issue(admin, o.getId(), null);
        em.flush();

        // 청구가 나간 뒤 한 끼 취소 (데스크는 기간 제한이 없다)
        o.activeItems().get(0).cancel(Instant.now(), CancelPath.DESK);

        assertThat(o.refundableAmount()).isEqualTo(7_700);
        assertThat(mealBillingService.refundables(admin, academy.getId(), MONTH))
                .singleElement()
                .satisfies(r -> assertThat(r.amount()).isEqualTo(7_700));
    }

    @Test
    @DisplayName("청구 전 취소는 환불 대상이 아니다 — 받은 돈이 없다")
    void notRefundableBeforeIssue() {
        MealOrder o = order(2, 3);
        o.activeItems().get(0).cancel(Instant.now(), CancelPath.APP);

        assertThat(o.refundableAmount()).isZero();
        assertThat(mealBillingService.refundables(admin, academy.getId(), MONTH)).isEmpty();
    }

    // ─────────────────────────────────────────── 경계

    @Test
    @DisplayName("★ 두 번 발행할 수 없다 — 학생이 두 번 낸다")
    void rejectsDuplicateIssue() {
        MealOrder o = order(2);
        mealBillingService.issue(admin, o.getId(), null);
        em.flush();

        assertThatThrownBy(() -> mealBillingService.issue(admin, o.getId(), null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.BILLING_ALREADY_ISSUED);
    }

    @Test
    @DisplayName("★ 단가가 안 박힌 주문은 청구할 수 없다 — 0원으로 내보내면 아무도 못 찾는다")
    void rejectsOrderWithoutUnitPrice() {
        MealOrder o = new MealOrder(enrollment, MONTH);
        o.addItem(LocalDate.of(YEAR, 3, 2), MealType.LUNCH);   // 단가 없음
        em.persist(o);
        em.flush();

        assertThatThrownBy(() -> mealBillingService.issue(admin, o.getId(), null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode",
                        ErrorCode.MEAL_UNIT_PRICE_NOT_REGISTERED);
    }

    @Test
    @DisplayName("신청 내역이 없으면 청구하지 않는다")
    void rejectsEmptyOrder() {
        MealOrder o = new MealOrder(enrollment, MONTH);
        em.persist(o);
        em.flush();

        assertThatThrownBy(() -> mealBillingService.issue(admin, o.getId(), null))
                .isInstanceOf(BusinessException.class);
    }

    // ─────────────────────────────────────────── 일괄

    @Test
    @DisplayName("★ 일괄 발행은 문제 있는 주문을 건너뛴다 — 한 건 때문에 전체가 멈추면 안 된다")
    void batchSkipsProblemOrders() {
        order(2, 3);                                   // 정상
        MealOrder noPrice = new MealOrder(enrollment, MONTH);   // 단가 없음
        noPrice.addItem(LocalDate.of(YEAR, 3, 5), MealType.DINNER);
        em.persist(noPrice);
        em.flush();

        List<Billing> issued = mealBillingService.issueMonth(admin, academy.getId(), MONTH, null);

        assertThat(issued).hasSize(1);
        assertThat(issued.get(0).getBilledAmount()).isEqualTo(15_400);
        assertThat(noPrice.isBilled()).isFalse();
    }

    @Test
    @DisplayName("이미 발행된 주문은 일괄에서도 건너뛴다")
    void batchSkipsAlreadyIssued() {
        MealOrder o = order(2);
        mealBillingService.issue(admin, o.getId(), null);
        em.flush();

        assertThat(mealBillingService.issueMonth(admin, academy.getId(), MONTH, null)).isEmpty();
    }
}
