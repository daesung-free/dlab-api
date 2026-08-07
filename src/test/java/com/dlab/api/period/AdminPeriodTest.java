package com.dlab.api.period;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.period.entity.DayType;
import com.dlab.domain.period.entity.PeriodMaster;
import com.dlab.domain.period.entity.PeriodType;
import com.dlab.domain.period.service.PeriodService;
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
 * 교시·시간 편집 (F-4.10-1).
 *
 * <p>출결 판정·순공시간·학습계획이 <b>같은 마스터</b>를 보기 때문에 편집 제약이 있다.
 */
@SpringBootTest
@Transactional
class AdminPeriodTest {

    @Autowired PeriodService periodService;
    @Autowired EntityManager em;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    AuthPrincipal admin;
    static final short YEAR = 2026;

    @BeforeEach
    void setUp() {
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);
        em.flush();

        admin = AuthPrincipal.of(1L, "EMPLOYEE", bundang.getId(),
                List.of(Role.BRANCH_ADMIN), false);
    }

    private PeriodMaster create(short no, int fromHour, int toHour) {
        return create(no, fromHour, toHour, DayType.WEEKDAY, PeriodType.CLASS);
    }

    private PeriodMaster create(short no, int fromHour, int toHour,
                                DayType dayType, PeriodType type) {
        PeriodMaster saved = periodService.create(admin, bundang.getId(), YEAR, dayType,
                no, no + "교시", type,
                LocalTime.of(fromHour, 0), LocalTime.of(toHour, 0), true, true);
        em.flush();
        return saved;
    }

    @Test
    @DisplayName("교시를 등록하고 요일 구분별로 조회한다")
    void createAndList() {
        create((short) 1, 9, 10);
        create((short) 1, 10, 11, DayType.SATURDAY, PeriodType.SELF_STUDY);
        em.clear();

        assertThat(periodService.findByDayType(admin, bundang.getId(), YEAR, DayType.WEEKDAY))
                .hasSize(1);
        assertThat(periodService.findAll(admin, bundang.getId(), YEAR)).hasSize(2);
    }

    @Test
    @DisplayName("★ 시간이 겹치면 거부 — 한 시각이 두 교시에 걸리면 출결 판정이 흔들린다")
    void overlappingPeriodIsRejected() {
        create((short) 1, 9, 11);

        assertThatThrownBy(() -> create((short) 2, 10, 12))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("겹칩니다");
    }

    @Test
    @DisplayName("★ 경계가 맞닿는 건 겹침이 아니다 — 09~10, 10~11은 정상이다")
    void adjacentPeriodIsAllowed() {
        create((short) 1, 9, 10);

        assertThatCode(() -> create((short) 2, 10, 11)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★ 요일 구분이 다르면 같은 시간대를 써도 된다 — 토요일은 별개 구성이다")
    void sameTimeOnDifferentDayTypeIsAllowed() {
        create((short) 1, 9, 11);

        assertThatCode(() -> create((short) 1, 9, 11, DayType.SATURDAY, PeriodType.SELF_STUDY))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("같은 요일 구분에 교시 번호가 겹치면 거부")
    void duplicatedPeriodNoIsRejected() {
        create((short) 1, 9, 10);

        assertThatThrownBy(() -> create((short) 1, 10, 11))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("종료가 시작보다 빠르거나 같으면 거부")
    void reversedTimeIsRejected() {
        assertThatThrownBy(() -> periodService.create(admin, bundang.getId(), YEAR,
                DayType.WEEKDAY, (short) 1, "1교시", PeriodType.CLASS,
                LocalTime.of(11, 0), LocalTime.of(9, 0), true, true))
                .isInstanceOf(BusinessException.class);

        assertThatThrownBy(() -> periodService.create(admin, bundang.getId(), YEAR,
                DayType.WEEKDAY, (short) 2, "2교시", PeriodType.CLASS,
                LocalTime.of(9, 0), LocalTime.of(9, 0), true, true))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("★ 수정할 때 자기 자신과는 안 겹친다 — 자기와 비교하면 항상 걸린다")
    void updateDoesNotConflictWithItself() {
        PeriodMaster period = create((short) 1, 9, 10);
        em.clear();

        periodService.update(admin, period.getId(), (short) 1, "1교시",
                PeriodType.CLASS, LocalTime.of(9, 0), LocalTime.of(10, 30), true, true);
        em.flush();
        em.clear();

        assertThat(periodService.findAll(admin, bundang.getId(), YEAR)).first()
                .satisfies(p -> assertThat(p.getEndTime()).isEqualTo(LocalTime.of(10, 30)));
    }

    @Test
    @DisplayName("수정 시 다른 교시와 겹치면 거부")
    void updateOverlappingIsRejected() {
        PeriodMaster first = create((short) 1, 9, 10);
        create((short) 2, 10, 11);
        em.clear();

        assertThatThrownBy(() -> periodService.update(admin, first.getId(), (short) 1, "1교시",
                PeriodType.CLASS, LocalTime.of(9, 0), LocalTime.of(10, 30), true, true))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("★★ 마지막 교시는 못 지운다 — 교시가 0개면 그날 태깅이 전원 거부된다")
    void lastPeriodCannotBeDeleted() {
        PeriodMaster only = create((short) 1, 9, 10);
        em.clear();

        assertThatThrownBy(() -> periodService.delete(admin, only.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("마지막 교시");
    }

    @Test
    @DisplayName("둘 이상이면 지울 수 있고 soft delete다")
    void deleteIsSoft() {
        PeriodMaster first = create((short) 1, 9, 10);
        create((short) 2, 10, 11);
        em.clear();

        periodService.delete(admin, first.getId());
        em.flush();
        em.clear();

        assertThat(periodService.findAll(admin, bundang.getId(), YEAR)).hasSize(1);
        // 행 자체는 남는다 — 지난 출결이 어느 구성으로 판정됐는지 추적이 끊기면 안 된다
        assertThat(em.find(PeriodMaster.class, first.getId())).isNotNull();
    }

    @Test
    @DisplayName("★ 지운 교시 자리에 새로 만들 수 있다 — 겹침 검사가 삭제분을 세면 안 된다")
    void deletedPeriodDoesNotBlockNewOne() {
        PeriodMaster first = create((short) 1, 9, 10);
        create((short) 2, 10, 11);
        em.clear();

        periodService.delete(admin, first.getId());
        em.flush();
        em.clear();

        assertThatCode(() -> create((short) 1, 9, 10)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★ 다른 지점 교시는 건드릴 수 없다")
    void otherAcademyIsRejected() {
        PeriodMaster period = create((short) 1, 9, 10);
        Academy ilsan = new Academy("32", "일산", LocalTime.of(9, 0));
        em.persist(ilsan);
        em.flush();
        em.clear();

        AuthPrincipal ilsanAdmin = AuthPrincipal.of(2L, "EMPLOYEE", ilsan.getId(),
                List.of(Role.BRANCH_ADMIN), false);

        assertThatThrownBy(() -> periodService.delete(ilsanAdmin, period.getId()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> periodService.findAll(ilsanAdmin, bundang.getId(), YEAR))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("급식·쉬는시간도 교시로 등록된다 — 순공시간이 이 유형으로 갈린다")
    void mealAndBreakAreStoredAsPeriods() {
        create((short) 1, 9, 12);
        PeriodMaster meal = create((short) 2, 12, 13, DayType.WEEKDAY, PeriodType.MEAL);
        em.clear();

        assertThat(em.find(PeriodMaster.class, meal.getId()).getPeriodType())
                .isEqualTo(PeriodType.MEAL);
    }
}
