package com.dlab.api.admin.meal;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.meal.entity.CancelPath;
import com.dlab.domain.meal.entity.MealOrder;
import com.dlab.domain.meal.entity.MealOrderStatus;
import com.dlab.domain.meal.entity.MealType;
import com.dlab.domain.meal.service.MealBillingService;
import com.dlab.domain.meal.service.MealOrderListEnricher;
import com.dlab.domain.meal.service.MealOrderService;
import com.dlab.domain.payment.entity.Billing;
import com.dlab.domain.payment.entity.PaymentMethod;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.ClassAssignment;
import com.dlab.domain.user.entity.ClassMaster;
import com.dlab.domain.user.entity.ClassType;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 급식 관리 화면(F-4.5) "결제·취소 내역" 목록이 <b>화면이 그리는 값을 실제로 내리는지</b>.
 *
 * <p>지키려는 것은 둘 — <b>반·금액·결제수단·주문번호가 채워진다</b>(청구가 붙은 주문과
 * 안 붙은 주문 둘 다), 그리고 <b>쿼리 수가 주문 수에 비례하지 않는다</b>.
 */
@SpringBootTest
@Transactional
class AdminMealOrderListTest {

    @Autowired MealOrderService mealOrderService;
    @Autowired MealBillingService mealBillingService;
    @Autowired MealOrderListEnricher enricher;
    @Autowired EntityManager em;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    static final short YEAR = 2096;
    static final YearMonth MONTH = YearMonth.of(YEAR, 3);
    /** 쿼리 수 비교용 — 주문이 한 건뿐인 달. */
    static final YearMonth LONE_MONTH = YearMonth.of(YEAR, 4);

    Academy academy;
    ClassMaster classMaster;
    AuthPrincipal admin;
    int seq;

    @BeforeEach
    void setUp() {
        academy = new Academy("ML1", "급식목록테스트", LocalTime.of(9, 0));
        em.persist(academy);

        classMaster = new ClassMaster(academy, YEAR, "A반", ClassType.FIXED, null);
        em.persist(classMaster);

        admin = AuthPrincipal.of(1L, "EMPLOYEE", null, List.of(Role.SUPER_ADMIN), true);
    }

    /** @param assignClass 반 배정 여부 — 미배정 학생도 목록에 나와야 한다 */
    private StudentEnrollment enrollment(boolean assignClass) {
        seq++;
        Student student = new Student("ML-%04d".formatted(seq), "학생%d".formatted(seq),
                "010-0000-%04d".formatted(seq));
        em.persist(student);
        StudentEnrollment enrollment = new StudentEnrollment(student, academy, YEAR,
                "2096-%04d".formatted(seq), null, GradeType.N_SU);
        em.persist(enrollment);
        if (assignClass) {
            em.persist(new ClassAssignment(academy, enrollment, classMaster, ClassType.FIXED));
        }
        return enrollment;
    }

    private MealOrder order(StudentEnrollment enrollment, YearMonth month, int... days) {
        MealOrder order = new MealOrder(enrollment, month);
        for (int d : days) {
            order.addItem(month.atDay(d), MealType.LUNCH, 7_700);
        }
        em.persist(order);
        em.flush();
        return order;
    }

    private List<AdminMealController.OrderResponse> list(YearMonth month) {
        em.flush();
        em.clear();
        List<MealOrder> orders = mealOrderService.findByMonth(academy.getId(), month);
        MealOrderListEnricher.Extras extras = enricher.of(orders);
        return orders.stream().map(o -> AdminMealController.OrderResponse.from(o, extras)).toList();
    }

    // ── 화면이 그리는 값 ───────────────────────────────────────

    @Test
    @DisplayName("★ 청구가 붙은 주문은 반·금액·청구액·결제수단·주문번호가 전부 채워진다")
    void billedOrderCarriesEveryColumn() {
        MealOrder o = order(enrollment(true), MONTH, 2, 3, 4);
        Billing billing = mealBillingService.issue(admin, o.getId(), null);
        billing.addPayment(billing.getBilledAmount(), PaymentMethod.CARD, Instant.now());

        AdminMealController.OrderResponse row = list(MONTH).get(0);

        assertThat(row.className()).isEqualTo("A반");
        assertThat(row.studentNo()).isEqualTo("2096-0001");
        assertThat(row.amount()).isEqualTo(23_100);            // 7,700 × 3
        assertThat(row.billedAmount()).isEqualTo(23_100);
        assertThat(row.paymentMethods()).containsExactly("CARD");
        assertThat(row.billingId()).isEqualTo(billing.getId());
        assertThat(row.status()).isEqualTo(MealOrderStatus.ISSUED);
        assertThat(row.orderNo()).isEqualTo("M9603-%06d".formatted(o.getId()));
        assertThat(row.items()).hasSize(3);
        assertThat(row.items().get(0).unitPrice()).isEqualTo(7_700);
    }

    @Test
    @DisplayName("★ 청구 전 주문도 이용액은 보인다 — 청구액·결제수단은 비어 있어야 한다")
    void unbilledOrderShowsAmountButNoBilling() {
        order(enrollment(true), MONTH, 2, 3);

        AdminMealController.OrderResponse row = list(MONTH).get(0);

        assertThat(row.amount()).isEqualTo(15_400);
        // ★ 0이 아니라 null이다 — "0원 청구"와 "아직 청구 안 함"은 다르다
        assertThat(row.billedAmount()).isNull();
        assertThat(row.billingId()).isNull();
        assertThat(row.paymentMethods()).isEmpty();
        assertThat(row.refundableAmount()).isZero();
        assertThat(row.status()).isEqualTo(MealOrderStatus.PENDING);
    }

    @Test
    @DisplayName("반 미배정 학생도 목록에서 빠지지 않는다 — 반 이름만 null이다")
    void unassignedStudentStillListed() {
        order(enrollment(false), MONTH, 2);

        AdminMealController.OrderResponse row = list(MONTH).get(0);

        assertThat(row.className()).isNull();
        assertThat(row.studentName()).isNotBlank();
    }

    @Test
    @DisplayName("★ 발행 후 취소하면 이용액만 줄고 청구액은 그대로다 — 그 차액이 환불 대상이다")
    void cancelAfterIssueKeepsBilledAmount() {
        MealOrder o = order(enrollment(true), MONTH, 2, 3, 4);
        mealBillingService.issue(admin, o.getId(), null);
        o.activeItems().get(0).cancel(Instant.now(), CancelPath.DESK);

        AdminMealController.OrderResponse row = list(MONTH).get(0);

        assertThat(row.amount()).isEqualTo(15_400);
        assertThat(row.billedAmount()).isEqualTo(23_100);
        assertThat(row.refundableAmount()).isEqualTo(7_700);
        assertThat(row.activeCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("분납이어도 결제수단이 중복으로 찍히지 않는다")
    void repeatedMethodIsListedOnce() {
        MealOrder o = order(enrollment(true), MONTH, 2, 3);
        Billing billing = mealBillingService.issue(admin, o.getId(), null);
        billing.addPayment(5_000, PaymentMethod.CARD, Instant.now());
        billing.addPayment(5_000, PaymentMethod.CARD, Instant.now());
        billing.addPayment(5_400, PaymentMethod.TRANSFER, Instant.now());

        assertThat(list(MONTH).get(0).paymentMethods())
                .containsExactlyInAnyOrder("CARD", "TRANSFER");
    }

    // ── N+1 ──────────────────────────────────────────────────

    @Test
    @DisplayName("★★ 쿼리 수가 주문 수에 비례하지 않는다 — 1건이든 5건이든 같다")
    void queryCountDoesNotGrowWithOrders() {
        for (int i = 0; i < 5; i++) {
            order(enrollment(true), MONTH, 2, 3);
        }
        order(enrollment(true), LONE_MONTH, 2);

        long many = countQueries(MONTH);
        long few = countQueries(LONE_MONTH);

        assertThat(many).isEqualTo(few);
        // 목록 1 + 반 1 + 결제 0(청구 없음) — 늘어도 한 자릿수여야 한다
        assertThat(many).isLessThanOrEqualTo(4);
    }

    private long countQueries(YearMonth month) {
        Statistics stats = em.getEntityManagerFactory()
                .unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        em.flush();
        em.clear();
        stats.clear();

        List<MealOrder> orders = mealOrderService.findByMonth(academy.getId(), month);
        MealOrderListEnricher.Extras extras = enricher.of(orders);
        orders.forEach(o -> AdminMealController.OrderResponse.from(o, extras));

        return stats.getPrepareStatementCount();
    }
}
