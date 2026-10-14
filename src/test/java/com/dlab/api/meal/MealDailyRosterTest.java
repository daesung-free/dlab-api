package com.dlab.api.meal;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.api.admin.meal.AdminMealController;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.meal.entity.MealType;
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
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 급식 <b>일자별 신청 명단</b> (피드백 5-4).
 *
 * <p>지키려는 것 — <b>점심·저녁이 갈릴 것</b>(배식이 따로 돈다), <b>취소된 끼니가 빠질 것</b>,
 * <b>권한에 따라 이름이 가려지고 가려진 사실이 응답에 실릴 것</b>,
 * <b>쿼리가 인원 수만큼 늘지 않을 것</b>.
 */
@SpringBootTest
@Transactional
class MealDailyRosterTest {

    @Autowired AdminMealController controller;
    @Autowired MealOrderService orderService;
    @Autowired MealAdminService adminService;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    StudentEnrollment minji;
    StudentEnrollment junho;
    AuthPrincipal branchAdmin;
    AuthPrincipal staff;
    YearMonth month;
    LocalDate day;

    @BeforeEach
    void setUp() {
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);

        minji = enroll("DL-R1", "김민지", "2026-0001");
        junho = enroll("DL-R2", "박준호", "2026-0002");
        em.flush();

        branchAdmin = AuthPrincipal.of(1L, "EMPLOYEE", bundang.getId(),
                List.of(Role.BRANCH_ADMIN), false);
        staff = AuthPrincipal.of(2L, "EMPLOYEE", bundang.getId(),
                List.of(Role.STAFF), false);

        // 마감(D-3)에 걸리지 않게 넉넉히 뒤인 달을 쓴다
        month = YearMonth.from(LocalDate.now(clock).plusMonths(2));
        day = month.atDay(1).datesUntil(month.plusMonths(1).atDay(1))
                .filter(d -> d.getDayOfWeek() != DayOfWeek.SATURDAY
                        && d.getDayOfWeek() != DayOfWeek.SUNDAY)
                .findFirst().orElseThrow();

        adminService.saveWindow(branchAdmin, bundang.getId(), month,
                LocalDate.now(clock).minusDays(1), LocalDate.now(clock).plusDays(10));
        em.flush();
    }

    private StudentEnrollment enroll(String code, String name, String studentNo) {
        Student student = new Student(code, name, "010-0000-0000");
        em.persist(student);
        StudentEnrollment enrollment = new StudentEnrollment(student, bundang, (short) 2026,
                studentNo, null, GradeType.N_SU);
        em.persist(enrollment);
        return enrollment;
    }

    private void apply(StudentEnrollment enrollment, MealType... types) {
        orderService.apply(enrollment.getId(), month,
                java.util.Arrays.stream(types).map(t -> new MealSelection(day, t)).toList());
        em.flush();
    }

    @Test
    @WithMockUser(roles = "BRANCH_ADMIN")
    @DisplayName("★ 점심·저녁이 갈린다 — 배식이 따로 돌고 인원도 다르다")
    void splitsLunchAndDinner() {
        apply(minji, MealType.LUNCH, MealType.DINNER);
        apply(junho, MealType.LUNCH);

        var roster = controller.daily(branchAdmin, bundang.getId(), day).data();

        assertThat(roster.lunchCount()).isEqualTo(2);
        assertThat(roster.dinnerCount()).isEqualTo(1);
        assertThat(roster.lunch()).extracting(AdminMealController.DailyRosterRow::studentNo)
                .containsExactly("2026-0001", "2026-0002");
        assertThat(roster.dinner()).extracting(AdminMealController.DailyRosterRow::studentName)
                .containsExactly("김민지");
    }

    @Test
    @WithMockUser(roles = "BRANCH_ADMIN")
    @DisplayName("★ 취소된 끼니는 명단에 없다 — 안 먹는 사람 몫이 배식에 잡히면 안 된다")
    void canceledMealIsNotOnTheRoster() {
        apply(minji, MealType.LUNCH);
        apply(junho, MealType.LUNCH);

        Long itemId = controller.daily(branchAdmin, bundang.getId(), day).data()
                .lunch().stream()
                .filter(r -> "2026-0002".equals(r.studentNo()))
                .findFirst().orElseThrow().orderItemId();
        // ★ 명단의 orderItemId 로 바로 취소된다 — 끼니 단위가 취소 단위다
        controller.cancelItem(branchAdmin, itemId);
        em.flush();

        var roster = controller.daily(branchAdmin, bundang.getId(), day).data();

        assertThat(roster.lunchCount()).isEqualTo(1);
        assertThat(roster.lunch()).extracting(AdminMealController.DailyRosterRow::studentNo)
                .containsExactly("2026-0001");
    }

    @Test
    @WithMockUser(roles = "BRANCH_ADMIN")
    @DisplayName("★ 직원에게는 이름이 가려지고, 가려진 사실이 응답에 실린다")
    void masksNameForStaff() {
        apply(minji, MealType.LUNCH);

        var masked = controller.daily(staff, bundang.getId(), day).data();

        assertThat(masked.masked()).isTrue();
        assertThat(masked.lunch().get(0).studentName()).isEqualTo("김*지");
        // 학번은 가리지 않는다 — 배식대에서 사람을 특정하는 값이고 개인정보가 아니다
        assertThat(masked.lunch().get(0).studentNo()).isEqualTo("2026-0001");

        var raw = controller.daily(branchAdmin, bundang.getId(), day).data();
        assertThat(raw.masked()).isFalse();
        assertThat(raw.lunch().get(0).studentName()).isEqualTo("김민지");
    }

    @Test
    @WithMockUser(roles = "BRANCH_ADMIN")
    @DisplayName("신청이 없는 날은 빈 명단이다 — 404가 아니다")
    void emptyDayIsEmptyRoster() {
        var roster = controller.daily(branchAdmin, bundang.getId(), day).data();

        assertThat(roster.date()).isEqualTo(day);
        assertThat(roster.lunch()).isEmpty();
        assertThat(roster.dinner()).isEmpty();
    }

    @Test
    @WithMockUser(roles = "BRANCH_ADMIN")
    @DisplayName("★ 쿼리가 인원 수만큼 늘지 않는다 — 그날 전원이 대상인 화면이다")
    void queryCountDoesNotGrowWithStudents() {
        apply(minji, MealType.LUNCH);
        long one = countQueries();

        apply(junho, MealType.LUNCH);
        long two = countQueries();

        // 명단 1 + 반 1 + 좌석 1. 신청자가 늘어도 이 수는 그대로다
        assertThat(two).isEqualTo(one).isLessThanOrEqualTo(3);
    }

    private long countQueries() {
        Statistics stats = em.getEntityManagerFactory()
                .unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        em.flush();
        em.clear();
        stats.clear();

        controller.daily(branchAdmin, bundang.getId(), day);

        return stats.getPrepareStatementCount();
    }
}
