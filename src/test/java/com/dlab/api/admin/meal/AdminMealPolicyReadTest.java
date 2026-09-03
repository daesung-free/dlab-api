package com.dlab.api.admin.meal;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.meal.entity.MealPolicy;
import com.dlab.domain.meal.service.MealAdminService;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.EntityManager;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 급식 마감 규칙 조회(API_GAPS 6-8) — {@code PUT} 만 있어 화면이 현재 마감일을 못 읽던 것.
 *
 * <p><b>핵심은 미등록 처리다.</b> 신청 판정은 미등록 지점을 기본값으로 보고 그대로 돌아가는데,
 * 조회가 404를 내면 화면에는 "설정 없음"이 뜬다 — <b>실제로 적용되는 값과 화면이 어긋난다.</b>
 * 그래서 기본값을 내리되 {@code registered} 로 구분한다.
 */
@SpringBootTest
@Transactional
class AdminMealPolicyReadTest {

    @Autowired MealAdminService mealAdminService;
    @Autowired EntityManager em;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    static final short YEAR = 2097;

    Academy academy;
    AuthPrincipal admin;

    @BeforeEach
    void setUp() {
        academy = new Academy("MP1", "급식정책테스트", LocalTime.of(9, 0));
        em.persist(academy);
        em.flush();
        admin = AuthPrincipal.of(1L, "EMPLOYEE", null, List.of(Role.SUPER_ADMIN), true);
    }

    @Test
    @DisplayName("★ 미등록 지점은 비어 있는 값이 온다 — 404가 아니다")
    void unregisteredIsEmptyNotError() {
        assertThat(mealAdminService.findPolicy(admin, academy.getId(), YEAR)).isEmpty();
    }

    @Test
    @DisplayName("저장한 마감일이 그대로 조회된다")
    void savedDeadlineIsReadBack() {
        mealAdminService.saveDeadline(admin, academy.getId(), YEAR, (short) 5);
        em.flush();

        assertThat(mealAdminService.findPolicy(admin, academy.getId(), YEAR))
                .get()
                .extracting(MealPolicy::getDeadlineDays)
                .isEqualTo((short) 5);
    }

    @Test
    @DisplayName("연도가 다르면 따로다 — 내년 값을 올해 화면이 보면 안 된다")
    void yearsAreSeparate() {
        mealAdminService.saveDeadline(admin, academy.getId(), YEAR, (short) 5);
        em.flush();

        assertThat(mealAdminService.findPolicy(admin, academy.getId(), (short) (YEAR + 1)))
                .isEmpty();
    }
}
