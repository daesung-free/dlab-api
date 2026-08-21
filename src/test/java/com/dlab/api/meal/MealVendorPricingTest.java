package com.dlab.api.meal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.meal.entity.MealOrder;
import com.dlab.domain.meal.entity.MealPolicy;
import com.dlab.domain.meal.entity.MealType;
import com.dlab.domain.meal.entity.MealVendor;
import com.dlab.domain.meal.service.MealVendorService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 급식업체·단가 (0820 규정).
 *
 * <p>지키려는 것은 셋이다 — <b>업체는 여러 지점을 담당한다</b>,
 * <b>단가에 기본값을 두지 않는다</b>, <b>주문에 단가를 스냅샷으로 박는다</b>.
 */
@SpringBootTest
@Transactional
class MealVendorPricingTest {

    @Autowired MealVendorService vendorService;
    @Autowired EntityManager em;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    static final short YEAR = 2099;

    Academy bundang;
    Academy daegu;
    AuthPrincipal superAdmin;

    @BeforeEach
    void setUp() {
        bundang = new Academy("V1", "업체분당", LocalTime.of(9, 0));
        daegu = new Academy("V2", "업체대구", LocalTime.of(9, 0));
        em.persist(bundang);
        em.persist(daegu);
        em.flush();

        superAdmin = AuthPrincipal.of(1L, "EMPLOYEE", null,
                java.util.List.of(com.dlab.common.security.Role.SUPER_ADMIN), true);
    }

    // ─────────────────────────────────────────── 업체

    @Test
    @DisplayName("★ 한 업체가 여러 지점을 담당한다 — 지점마다 복제하지 않는다")
    void oneVendorServesManyAcademies() {
        MealVendor dion = vendorService.create("테스트디온", null, null, null);

        vendorService.assignToAcademy(superAdmin, bundang.getId(), YEAR, dion.getId(), 7700);
        vendorService.assignToAcademy(superAdmin, daegu.getId(), YEAR, dion.getId(), 7700);
        em.flush();

        assertThat(vendorService.policyOf(superAdmin, bundang.getId(), YEAR).getVendor().getId())
                .isEqualTo(dion.getId());
        assertThat(vendorService.policyOf(superAdmin, daegu.getId(), YEAR).getVendor().getId())
                .isEqualTo(dion.getId());
    }

    @Test
    @DisplayName("★ 지점마다 단가가 다를 수 있다 — 대구만 8,000원이다")
    void unitPriceDiffersByAcademy() {
        MealVendor dion = vendorService.create("테스트디온2", null, null, null);
        MealVendor sungrim = vendorService.create("테스트성림", null, null, null);

        vendorService.assignToAcademy(superAdmin, bundang.getId(), YEAR, dion.getId(), 7700);
        vendorService.assignToAcademy(superAdmin, daegu.getId(), YEAR, sungrim.getId(), 8000);
        em.flush();

        assertThat(vendorService.policyOf(superAdmin, bundang.getId(), YEAR).getUnitPrice())
                .isEqualTo(7700);
        assertThat(vendorService.policyOf(superAdmin, daegu.getId(), YEAR).getUnitPrice())
                .isEqualTo(8000);
    }

    @Test
    @DisplayName("같은 이름 업체를 두 번 만들 수 없다")
    void duplicateVendorRejected() {
        vendorService.create("중복푸드", null, null, null);
        em.flush();

        assertThatThrownBy(() -> vendorService.create("중복푸드", null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEAL_VENDOR_DUPLICATED);
    }

    @Test
    @DisplayName("★ 업체를 내려도 지우지 않는다 — 과거 주문의 정산 근거다")
    void deactivateKeepsRow() {
        MealVendor vendor = vendorService.create("내릴푸드", null, null, null);
        vendorService.assignToAcademy(superAdmin, bundang.getId(), YEAR, vendor.getId(), 7700);
        em.flush();

        vendorService.deactivate(vendor.getId());
        em.flush();

        // 목록에서는 빠지지만 지점 설정은 여전히 그 업체를 가리킨다
        assertThat(vendorService.findAll()).extracting(MealVendor::getId)
                .doesNotContain(vendor.getId());
        assertThat(vendorService.policyOf(superAdmin, bundang.getId(), YEAR).getVendor().getId())
                .isEqualTo(vendor.getId());
    }

    @Test
    @DisplayName("연락처는 비운 항목을 덮어쓰지 않는다")
    void contactPartialUpdate() {
        MealVendor vendor = vendorService.create("연락처푸드", "김담당", "010-1111-2222",
                "a@example.com");
        em.flush();

        vendorService.update(vendor.getId(), null, null, null, "b@example.com");

        assertThat(vendor.getContactName()).isEqualTo("김담당");
        assertThat(vendor.getContactPhone()).isEqualTo("010-1111-2222");
        assertThat(vendor.getContactEmail()).isEqualTo("b@example.com");
    }

    // ─────────────────────────────────────────── 단가 스냅샷

    @Test
    @DisplayName("★★ 주문 항목에 단가가 박힌다 — 나중에 단가를 올려도 과거 주문이 안 바뀐다")
    void unitPriceIsSnapshotted() {
        MealVendor vendor = vendorService.create("스냅푸드", null, null, null);
        vendorService.assignToAcademy(superAdmin, bundang.getId(), YEAR, vendor.getId(), 7700);
        em.flush();

        StudentEnrollment enrollment = enrollment(bundang, "SNAP-1");
        MealOrder order = new MealOrder(enrollment, YearMonth.of(YEAR, 3));
        order.addItem(LocalDate.of(YEAR, 3, 2), MealType.LUNCH, 7700);
        order.addItem(LocalDate.of(YEAR, 3, 2), MealType.DINNER, 7700);
        em.persist(order);
        em.flush();

        // 단가 인상
        vendorService.assignToAcademy(superAdmin, bundang.getId(), YEAR, vendor.getId(), 8500);
        em.flush();

        // 이미 만들어진 주문 금액은 그대로다
        assertThat(order.totalAmount()).isEqualTo(15_400);
    }

    @Test
    @DisplayName("★ 단가 미등록이면 금액이 0이다 — 임의값을 만들지 않는다")
    void missingPriceMeansZero() {
        StudentEnrollment enrollment = enrollment(bundang, "NOPRICE-1");
        MealOrder order = new MealOrder(enrollment, YearMonth.of(YEAR, 3));
        order.addItem(LocalDate.of(YEAR, 3, 2), MealType.LUNCH);
        em.persist(order);
        em.flush();

        // 마감일수는 기본값(3일)이 있지만 단가는 없다 — 틀리면 돈이라 성격이 다르다
        assertThat(order.totalAmount()).isZero();
    }

    @Test
    @DisplayName("취소된 끼니는 금액에서 빠진다")
    void canceledItemsExcluded() {
        StudentEnrollment enrollment = enrollment(bundang, "CANCEL-1");
        MealOrder order = new MealOrder(enrollment, YearMonth.of(YEAR, 3));
        order.addItem(LocalDate.of(YEAR, 3, 2), MealType.LUNCH, 7700);
        order.addItem(LocalDate.of(YEAR, 3, 2), MealType.DINNER, 7700);
        em.persist(order);
        em.flush();

        order.activeItems().get(0).cancel(java.time.Instant.now(),
                com.dlab.domain.meal.entity.CancelPath.APP);

        assertThat(order.totalAmount()).isEqualTo(7_700);
    }

    // ─────────────────────────────────────────── 경계

    @Test
    @DisplayName("단가 0원으로는 연결할 수 없다")
    void zeroPriceRejected() {
        MealVendor vendor = vendorService.create("영원푸드", null, null, null);
        em.flush();

        assertThatThrownBy(() -> vendorService.assignToAcademy(
                superAdmin, bundang.getId(), YEAR, vendor.getId(), 0))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("설정이 없던 지점도 연결하면 만들어진다 — 마감일수는 기본값으로 시작")
    void createsPolicyIfMissing() {
        MealVendor vendor = vendorService.create("신규푸드", null, null, null);

        MealPolicy policy = vendorService.assignToAcademy(
                superAdmin, daegu.getId(), YEAR, vendor.getId(), 8000);

        assertThat(policy.getDeadlineDays()).isEqualTo(MealPolicy.DEFAULT_DEADLINE_DAYS);
        assertThat(policy.isPriced()).isTrue();
    }

    private StudentEnrollment enrollment(Academy academy, String no) {
        Student student = new Student("MV-" + no, "김급식", "010-0000-0000");
        em.persist(student);
        StudentEnrollment e = new StudentEnrollment(student, academy, YEAR, no, null,
                GradeType.N_SU);
        em.persist(e);
        return e;
    }
}
