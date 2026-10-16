package com.dlab.api.admin.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.payment.entity.Billing;
import com.dlab.domain.payment.entity.BillingType;
import com.dlab.domain.payment.entity.SeatType;
import com.dlab.domain.payment.entity.TuitionMonth;
import com.dlab.domain.payment.entity.TuitionPrice;
import com.dlab.domain.payment.service.BillingService;
import com.dlab.domain.payment.service.TuitionBillingService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.EntityManager;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 청구 목록 응답이 <b>하위 항목</b>을 함께 내리는지 (1016 피드백 1-4).
 *
 * <h2>★ 합계만으로는 안 되는 이유</h2>
 * 한 청구(750,000)가 <b>교습비 660,000 + 독서실비 90,000</b>으로 나뉘어 있고
 * <b>환불 계산 방식이 서로 다르다</b> — 교습비는 이용기간 <b>구간</b>, 독서실비는 <b>일할</b>이다.
 * 그리고 환불 차감이 <b>정상가 기준</b>이라 <b>항목별 정가</b>가 없으면 청구 합계에서
 * 교습비 몫을 되짚을 수 없다.
 *
 * <p>항목은 처음부터 나뉘어 저장돼 있었고 <b>응답에 합계만 실려 있었다</b> — 그래서
 * 화면이 통합 매출장을 두 줄로 펼칠 수 없었다.
 */
@SpringBootTest
@Transactional
class BillingItemsResponseTest {

    @Autowired TuitionBillingService tuitionBillingService;
    @Autowired BillingService billingService;
    @Autowired com.dlab.domain.payment.service.ReceiptStatusService receiptStatusService;
    @Autowired EntityManager em;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    static final short YEAR = 2099;

    Academy academy;
    StudentEnrollment enrollment;
    AuthPrincipal admin;

    @BeforeEach
    void setUp() {
        academy = new Academy("BI1", "항목응답테스트", LocalTime.of(9, 0));
        em.persist(academy);
        Student student = new Student("BI-0001", "항목학생", "010-0000-0001");
        em.persist(student);
        enrollment = new StudentEnrollment(student, academy, YEAR, "2099-0001", null,
                GradeType.N_SU);
        em.persist(enrollment);

        // N수 일반좌석 750,000 = 교습비 660,000 + 독서실비 90,000
        em.persist(TuitionPrice.common(YEAR, GradeType.N_SU, SeatType.GENERAL, 660_000, 90_000));
        em.persist(TuitionMonth.common(YEAR, 3, 31));
        em.flush();

        admin = AuthPrincipal.of(1L, "EMPLOYEE", null, List.of(Role.SUPER_ADMIN), true);
    }

    @Test
    @DisplayName("★★ 목록 응답이 항목을 함께 내린다 — 항목별 정가와 할인 가능 여부까지")
    void listResponseCarriesItems() {
        tuitionBillingService.issueMonthly(admin, enrollment.getId(),
                YearMonth.of(YEAR, 3), SeatType.GENERAL, 0, null, null);
        em.flush();
        em.clear();

        List<Billing> billings = billingService.findByStudent(admin, enrollment.getId());
        var items = billingService.itemsOf(billings);
        var row = AdminBillingController.BillingResponse.from(billings.get(0), items);

        assertThat(row.billedAmount()).isEqualTo(750_000);   // 키오스크가 보는 값은 그대로
        assertThat(row.items())
                .extracting(i -> i.itemType().name(), i -> i.billedAmount(),
                        i -> i.suppliedAmount(), i -> i.discountable())
                .containsExactly(
                        // ★ 항목별 정가가 함께 와야 한다 — 환불 차감이 정상가 기준이다
                        Tuple.tuple("TUITION", 660_000, 660_000, true),
                        // ⚠️ 독서실비는 규정상 할인이 없다 — 화면이 할인 칸을 열지 않게 알려준다
                        Tuple.tuple("STUDY_ROOM", 90_000, 90_000, false));
    }

    @Test
    @DisplayName("항목이 없는 청구는 빈 목록이다 — 급식·특강처럼 나눌 것이 없는 청구가 정상이다")
    void billingWithoutItemsReturnsEmptyList() {
        em.persist(new Billing(enrollment, "특강비", BillingType.LECTURE, 100_000, 0, null));
        em.flush();
        em.clear();

        List<Billing> billings = billingService.findByStudent(admin, enrollment.getId());
        var items = billingService.itemsOf(billings);
        var row = AdminBillingController.BillingResponse.from(billings.get(0), items);

        assertThat(row.items()).isEmpty();
    }

    @Test
    @DisplayName("★★ 통합 매출장(수납현황)이 항목을 함께 내린다 — 화면이 보는 쿼리는 이쪽이다")
    void receiptStatusRowCarriesItems() {
        // 50% 할인 — 할인이 교습비에만 붙는 것이 이 응답에서 보여야 한다
        tuitionBillingService.issueMonthly(admin, enrollment.getId(),
                YearMonth.of(YEAR, 3), SeatType.GENERAL, 50, null, null);
        em.flush();
        em.clear();

        var rows = receiptStatusService.find(admin, academy.getId(), YEAR,
                null, null, (com.dlab.domain.payment.entity.BillingType) null, false);
        var view = AdminReceiptStatusController.RowView.from(rows.get(0));

        assertThat(view.billedAmount()).isEqualTo(420_000);          // 750,000 − 330,000
        assertThat(view.items())
                .extracting(i -> i.itemType().name(), i -> i.discountAmount(),
                        i -> i.billedAmount(), i -> i.discountable())
                .containsExactly(
                        // ★ 할인은 교습비에만 붙는다. 합계만 보면 독서실비도 깎인 줄 읽는다
                        Tuple.tuple("TUITION", 330_000, 330_000, true),
                        Tuple.tuple("STUDY_ROOM", 0, 90_000, false));
    }
}
