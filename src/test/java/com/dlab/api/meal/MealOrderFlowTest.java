package com.dlab.api.meal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.meal.entity.CancelPath;
import com.dlab.domain.meal.entity.MealOrder;
import com.dlab.domain.meal.entity.MealOrderItem;
import com.dlab.domain.meal.entity.MealOrderStatus;
import com.dlab.domain.meal.entity.MealType;
import com.dlab.domain.meal.repository.MealOrderItemRepository;
import com.dlab.domain.meal.service.MealAdminService;
import com.dlab.domain.meal.service.MealOrderService;
import com.dlab.domain.meal.service.MealOrderService.MealSelection;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.DayOfWeek;
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
 * 급식 신청·취소 (F-4.5 · A-9).
 *
 * <p>결제는 없다 — 주문은 {@code PENDING}에서 움직이지 않는다.
 */
@SpringBootTest
@Transactional
class MealOrderFlowTest {

    @Autowired MealOrderService orderService;
    @Autowired MealAdminService adminService;
    @Autowired MealOrderItemRepository itemRepository;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    StudentEnrollment minji;
    AuthPrincipal admin;
    YearMonth month;
    LocalDate weekday1;
    LocalDate weekday2;

    @BeforeEach
    void setUp() {
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);

        Student student = new Student("DL-1", "김민지", "010-0000-0000");
        em.persist(student);
        minji = new StudentEnrollment(student, bundang, (short) 2026,
                "2026-0001", null, GradeType.HIGH3);
        em.persist(minji);
        em.flush();

        admin = AuthPrincipal.of(1L, "EMPLOYEE", bundang.getId(),
                List.of(Role.BRANCH_ADMIN), false);

        // 마감(3일)에 안 걸리도록 넉넉히 뒤인 달을 쓴다
        month = YearMonth.from(LocalDate.now(clock).plusMonths(2));
        weekday1 = firstWeekdays(0);
        weekday2 = firstWeekdays(1);

        // 접수기간을 연다 — 열지 않으면 신청 자체가 막힌다
        adminService.saveWindow(admin, bundang.getId(), month,
                LocalDate.now(clock).minusDays(1), LocalDate.now(clock).plusDays(10));
        em.flush();
    }

    /** 그 달의 평일만 골라 n번째. 주말은 급식 대상이 아니다. */
    private LocalDate firstWeekdays(int index) {
        return month.atDay(1).datesUntil(month.plusMonths(1).atDay(1))
                .filter(d -> d.getDayOfWeek() != DayOfWeek.SATURDAY
                        && d.getDayOfWeek() != DayOfWeek.SUNDAY)
                .toList().get(index);
    }

    private MealOrder apply(LocalDate... dates) {
        List<MealSelection> items = java.util.Arrays.stream(dates)
                .map(d -> new MealSelection(d, MealType.LUNCH)).toList();
        MealOrder order = orderService.apply(minji.getId(), month, items);
        em.flush();
        return order;
    }

    @Test
    @DisplayName("★ 한 달치를 한 주문으로 묶는다 — 결제가 붙을 자리다")
    void oneOrderPerMonth() {
        MealOrder order = apply(weekday1, weekday2);

        assertThat(order.activeItems()).hasSize(2);
        assertThat(order.getStatus()).isEqualTo(MealOrderStatus.PENDING);
        assertThat(order.month()).isEqualTo(month);
    }

    @Test
    @DisplayName("★ 같은 달에 또 신청하면 항목만 더한다 — 주문을 또 만들면 결제가 쪼개진다")
    void secondApplyAddsItemsToSameOrder() {
        Long first = apply(weekday1).getId();
        Long second = apply(weekday2).getId();

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("★★ 접수기간 밖이면 신청이 막힌다 — 미등록도 닫힘이다")
    void applyIsBlockedOutsideWindow() {
        YearMonth other = month.plusMonths(1);   // 접수기간 미등록

        assertThatThrownBy(() -> orderService.apply(minji.getId(), other,
                List.of(new MealSelection(other.atDay(1), MealType.LUNCH))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("신청 기간");
    }

    @Test
    @DisplayName("★ 주말은 신청할 수 없다 — 급식 가능일은 주말·공휴일을 뺀다")
    void weekendIsNotAvailable() {
        LocalDate saturday = month.atDay(1).datesUntil(month.plusMonths(1).atDay(1))
                .filter(d -> d.getDayOfWeek() == DayOfWeek.SATURDAY).findFirst().orElseThrow();

        assertThatThrownBy(() -> orderService.apply(minji.getId(), month,
                List.of(new MealSelection(saturday, MealType.LUNCH))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("★ 대상 월이 아닌 날짜가 섞이면 거부")
    void dateOutsideTargetMonthIsRejected() {
        assertThatThrownBy(() -> orderService.apply(minji.getId(), month,
                List.of(new MealSelection(month.plusMonths(1).atDay(1), MealType.LUNCH))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("★★ 한 건만 어긋나도 전체가 거부된다 — 결제는 한 덩어리로 확정돼야 한다")
    void allOrNothing() {
        LocalDate saturday = month.atDay(1).datesUntil(month.plusMonths(1).atDay(1))
                .filter(d -> d.getDayOfWeek() == DayOfWeek.SATURDAY).findFirst().orElseThrow();

        assertThatThrownBy(() -> orderService.apply(minji.getId(), month, List.of(
                new MealSelection(weekday1, MealType.LUNCH),
                new MealSelection(saturday, MealType.LUNCH))))
                .isInstanceOf(BusinessException.class);

        em.flush();
        em.clear();
        assertThat(orderService.findMine(minji.getId(), month)).isNull();
    }

    @Test
    @DisplayName("같은 끼니를 두 번 신청할 수 없다")
    void duplicateMealIsRejected() {
        apply(weekday1);

        assertThatThrownBy(() -> apply(weekday1))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("점심과 저녁은 별개다")
    void lunchAndDinnerAreSeparate() {
        orderService.apply(minji.getId(), month, List.of(
                new MealSelection(weekday1, MealType.LUNCH),
                new MealSelection(weekday1, MealType.DINNER)));
        em.flush();

        assertThat(orderService.findMine(minji.getId(), month).activeItems()).hasSize(2);
    }

    @Test
    @DisplayName("★ 앱 취소는 취소 경로가 APP으로 남는다 — 물리 삭제하지 않는다")
    void studentCancelKeepsRow() {
        MealOrder order = apply(weekday1);
        Long itemId = order.activeItems().get(0).getId();

        orderService.cancelByStudent(minji.getId(), itemId);
        em.flush();
        em.clear();

        MealOrderItem item = itemRepository.findById(itemId).orElseThrow();
        assertThat(item.isActive()).isFalse();
        assertThat(item.getCancelPath()).isEqualTo(CancelPath.APP);
    }

    @Test
    @DisplayName("★ 마감이 지나면 앱 취소가 막힌다 — 관리자는 제한이 없다")
    void deadlineBlocksStudentCancelOnly() {
        // 오늘 신청분을 직접 만들어 마감(D-3)을 넘긴 상태로 만든다
        MealOrder order = new MealOrder(minji, YearMonth.from(LocalDate.now(clock)));
        order.addItem(LocalDate.now(clock), MealType.LUNCH);
        em.persist(order);
        em.flush();
        Long itemId = order.activeItems().get(0).getId();

        assertThatThrownBy(() -> orderService.cancelByStudent(minji.getId(), itemId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("마감");

        // 관리자는 기간 제한 없이 즉시 취소한다
        assertThatCode(() -> orderService.cancelByAdmin(admin, itemId))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★ 남의 신청은 취소할 수 없다")
    void cannotCancelOthersItem() {
        MealOrder order = apply(weekday1);
        Long itemId = order.activeItems().get(0).getId();

        assertThatThrownBy(() -> orderService.cancelByStudent(minji.getId() + 999, itemId))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("항목이 다 취소되면 주문도 취소로 내린다 — 빈 주문이 신청중으로 남으면 안 된다")
    void orderIsCancelledWhenEmpty() {
        MealOrder order = apply(weekday1);
        orderService.cancelByStudent(minji.getId(), order.activeItems().get(0).getId());
        em.flush();

        assertThat(order.getStatus()).isEqualTo(MealOrderStatus.CANCELLED);
    }

    // ── 중단일 ────────────────────────────────────────────────

    @Test
    @DisplayName("★★ 중단일을 등록하면 그날 신청이 함께 취소된다 — 환불 대상 건수를 돌려준다")
    void closureCancelsExistingOrders() {
        apply(weekday1, weekday2);

        var result = adminService.addClosure(admin, bundang.getId(), weekday1, "급식업체 휴무");
        em.flush();
        em.clear();

        assertThat(result.canceledCount()).isEqualTo(1);
        assertThat(itemRepository.findActiveByAcademyAndDate(bundang.getId(), weekday1)).isEmpty();
        // 다른 날은 그대로다
        assertThat(itemRepository.findActiveByAcademyAndDate(bundang.getId(), weekday2)).hasSize(1);
    }

    @Test
    @DisplayName("★ 중단일 취소분은 CLOSURE로 남는다 — 나중에 환불 대상을 여기서 찾는다")
    void closureCancelPathIsRecorded() {
        MealOrder order = apply(weekday1);
        Long itemId = order.activeItems().get(0).getId();

        adminService.addClosure(admin, bundang.getId(), weekday1, "모의고사");
        em.flush();
        em.clear();

        assertThat(itemRepository.findById(itemId).orElseThrow().getCancelPath())
                .isEqualTo(CancelPath.CLOSURE);
    }

    @Test
    @DisplayName("★ 중단일에는 새로 신청할 수 없다")
    void cannotApplyOnClosedDate() {
        adminService.addClosure(admin, bundang.getId(), weekday1, "학원 휴무");
        em.flush();

        assertThatThrownBy(() -> apply(weekday1))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("같은 날 중단일을 두 번 등록할 수 없다")
    void duplicateClosureIsRejected() {
        adminService.addClosure(admin, bundang.getId(), weekday1, "학원 휴무");
        em.flush();

        assertThatThrownBy(() ->
                adminService.addClosure(admin, bundang.getId(), weekday1, "단축수업"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("★ 중단 해제해도 취소된 신청은 되살리지 않는다 — 학생이 다시 신청한다")
    void removingClosureDoesNotRestoreOrders() {
        MealOrder order = apply(weekday1);
        Long itemId = order.activeItems().get(0).getId();
        var result = adminService.addClosure(admin, bundang.getId(), weekday1, "학원 휴무");
        em.flush();

        adminService.removeClosure(admin, result.closure().getId());
        em.flush();
        em.clear();

        assertThat(itemRepository.findById(itemId).orElseThrow().isActive()).isFalse();
    }

    @Test
    @DisplayName("★ 월별 현황이 닫힌 이유를 구분해 내린다 — 원인을 모르면 관리자가 못 고친다")
    void monthlyTellsWhyClosed() {
        adminService.addClosure(admin, bundang.getId(), weekday1, "모의고사");
        apply(weekday2);
        em.flush();
        em.clear();

        List<MealAdminService.DayStatus> days = adminService.monthly(admin, bundang.getId(), month);

        var closed = days.stream().filter(d -> d.date().equals(weekday1)).findFirst().orElseThrow();
        assertThat(closed.available()).isFalse();
        assertThat(closed.closedReason()).isEqualTo("CLOSURE");
        assertThat(closed.closureReason()).isEqualTo("모의고사");

        var weekend = days.stream().filter(d -> d.date().getDayOfWeek() == DayOfWeek.SUNDAY)
                .findFirst().orElseThrow();
        assertThat(weekend.closedReason()).isEqualTo("WEEKEND");

        var open = days.stream().filter(d -> d.date().equals(weekday2)).findFirst().orElseThrow();
        assertThat(open.available()).isTrue();
        assertThat(open.lunchCount()).isEqualTo(1);
    }
}
