package com.dlab.api.student;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.meal.entity.CancelPath;
import com.dlab.domain.meal.entity.MealOrder;
import com.dlab.domain.meal.entity.MealOrderItem;
import com.dlab.domain.meal.entity.MealType;
import com.dlab.domain.payment.entity.Billing;
import com.dlab.domain.payment.entity.BillingType;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.EnrollmentStatus;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.service.EnrollmentStatusFollowUp;
import com.dlab.domain.user.service.StudentStatusService;
import jakarta.persistence.EntityManager;
import java.time.Clock;
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
 * 재원 종료 후속처리 — 급식·수납 (P1-04).
 *
 * <p>지키려는 것 — <b>퇴원생 급식이 계속 나가지 않을 것</b>,
 * <b>이미 먹은 급식은 건드리지 않을 것</b>, <b>미납은 지우지 말고 알릴 것</b>,
 * <b>휴원은 아무것도 정리하지 않을 것</b>.
 */
@SpringBootTest
@Transactional
class EnrollmentFollowUpTest {

    @Autowired StudentStatusService studentStatusService;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy academy;
    StudentEnrollment enrollment;
    AuthPrincipal admin;
    LocalDate today;

    @BeforeEach
    void setUp() {
        today = LocalDate.now(clock);

        academy = new Academy("FU01", "후속처리지점", LocalTime.of(9, 0));
        em.persist(academy);

        Student student = new Student("FUSTU001", "정리학생", "010-6000-0001");
        em.persist(student);
        enrollment = new StudentEnrollment(student, academy, (short) today.getYear(),
                "2026-0001", null, GradeType.N_SU);
        em.persist(enrollment);
        em.flush();

        admin = AuthPrincipal.of(1L, "EMPLOYEE", academy.getId(),
                List.of(Role.BRANCH_ADMIN), false);
    }

    // ── 픽스처 ───────────────────────────────────────────

    /** 지난달·이번달 급식을 넣는다. 어제·오늘·내일 세 건. */
    private MealOrder mealOrderAround() {
        MealOrder order = new MealOrder(enrollment, YearMonth.from(today));
        order.addItem(today.minusDays(1), MealType.LUNCH);
        order.addItem(today, MealType.LUNCH);
        order.addItem(today.plusDays(1), MealType.LUNCH);
        em.persist(order);
        em.flush();
        return order;
    }

    private Billing unpaid(String name, int amount) {
        Billing billing = new Billing(enrollment, name, BillingType.TUITION, amount, 0,
                today.plusDays(7));
        em.persist(billing);
        em.flush();
        return billing;
    }

    private List<EnrollmentStatusFollowUp.Note> change(EnrollmentStatus to) {
        var result = studentStatusService.changeStatus(
                enrollment.getId(), to, "테스트", admin);
        em.flush();
        return result.followUps();
    }

    // ── 급식 ────────────────────────────────────────────

    @Test
    @DisplayName("★ 퇴원하면 남은 급식 신청이 취소된다 — 안 그러면 퇴원생 급식이 계속 결제된다")
    void withdrawalCancelsFutureMeals() {
        MealOrder order = mealOrderAround();

        List<EnrollmentStatusFollowUp.Note> notes = change(EnrollmentStatus.WITHDRAWN);
        em.clear();

        MealOrder reloaded = em.find(MealOrder.class, order.getId());
        assertThat(reloaded.activeItems())
                .extracting(MealOrderItem::getMealDate)
                .containsExactly(today.minusDays(1));   // 지난 것만 남는다

        assertThat(notes).anySatisfy(n -> {
            assertThat(n.area()).isEqualTo("급식");
            assertThat(n.message()).contains("2건");
            // 결제가 붙기 전이라 환불은 사람이 판단한다
            assertThat(n.blocking()).isTrue();
        });
    }

    @Test
    @DisplayName("★ 이미 먹은 급식은 취소하지 않는다 — 취소하면 식수 정산이 어긋난다")
    void pastMealsAreKept() {
        MealOrder order = mealOrderAround();

        change(EnrollmentStatus.EXPELLED);
        em.clear();

        MealOrderItem past = em.find(MealOrder.class, order.getId()).getItems().stream()
                .filter(i -> i.getMealDate().equals(today.minusDays(1)))
                .findFirst().orElseThrow();
        assertThat(past.getCanceledAt()).isNull();
    }

    @Test
    @DisplayName("★ 취소 경로가 WITHDRAWAL로 남는다 — 중단일과 환불 근거가 다르다")
    void cancelPathIsWithdrawal() {
        MealOrder order = mealOrderAround();

        change(EnrollmentStatus.WITHDRAWN);
        em.clear();

        assertThat(em.find(MealOrder.class, order.getId()).getItems())
                .filteredOn(i -> i.getCanceledAt() != null)
                .allSatisfy(i -> assertThat(i.getCancelPath()).isEqualTo(CancelPath.WITHDRAWAL));
    }

    @Test
    @DisplayName("★ 휴원은 급식을 정리하지 않는다 — 돌아올 학생이다")
    void leaveKeepsMeals() {
        MealOrder order = mealOrderAround();

        List<EnrollmentStatusFollowUp.Note> notes = change(EnrollmentStatus.LEAVE);
        em.clear();

        assertThat(em.find(MealOrder.class, order.getId()).activeItems()).hasSize(3);
        assertThat(notes).isEmpty();
    }

    @Test
    @DisplayName("수료도 정리 대상이다 — 다음 달 급식이 남아 있으면 안 된다")
    void graduationAlsoCancels() {
        MealOrder order = mealOrderAround();

        change(EnrollmentStatus.GRADUATED);
        em.clear();

        assertThat(em.find(MealOrder.class, order.getId()).activeItems()).hasSize(1);
    }

    // ── 수납 ────────────────────────────────────────────

    @Test
    @DisplayName("★ 미납은 지우지 않고 알린다 — 자동 정리하면 받을 돈이 장부에서 사라진다")
    void unpaidIsReportedNotRemoved() {
        Billing billing = unpaid("2026 학원비 3월", 450_000);

        List<EnrollmentStatusFollowUp.Note> notes = change(EnrollmentStatus.WITHDRAWN);
        em.clear();

        // 청구는 그대로 살아 있다
        Billing reloaded = em.find(Billing.class, billing.getId());
        assertThat(reloaded.isDeleted()).isFalse();
        assertThat(reloaded.getStatus()).isEqualTo(
                com.dlab.domain.payment.entity.BillingStatus.PENDING);

        assertThat(notes).anySatisfy(n -> {
            assertThat(n.area()).isEqualTo("수납");
            assertThat(n.message()).contains("1건").contains("450,000원");
            assertThat(n.blocking()).isTrue();
        });
    }

    @Test
    @DisplayName("미납이 없으면 수납 알림도 없다 — 매번 뜨면 아무도 안 본다")
    void noNoteWhenNothingUnpaid() {
        List<EnrollmentStatusFollowUp.Note> notes = change(EnrollmentStatus.WITHDRAWN);

        assertThat(notes).noneSatisfy(n -> assertThat(n.area()).isEqualTo("수납"));
    }

    @Test
    @DisplayName("★ 완납 건은 미납으로 세지 않는다")
    void paidIsNotCounted() {
        Billing billing = unpaid("2026 학원비 3월", 450_000);
        billing.addPayment(450_000, com.dlab.domain.payment.entity.PaymentMethod.CASH,
                java.time.Instant.now(clock));
        em.flush();

        List<EnrollmentStatusFollowUp.Note> notes = change(EnrollmentStatus.WITHDRAWN);

        assertThat(notes).noneSatisfy(n -> assertThat(n.area()).isEqualTo("수납"));
    }
}
