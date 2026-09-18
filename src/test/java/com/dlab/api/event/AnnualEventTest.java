package com.dlab.api.event;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.event.entity.AnnualEvent;
import com.dlab.domain.event.entity.AnnualEventType;
import com.dlab.domain.event.service.AnnualEventService;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 연간 행사 (F-4.11-10).
 *
 * <p>지키려는 것 — <b>기간 행사의 중간 날짜가 빠지지 않을 것</b>(주간 계획이 경계에 걸린다),
 * <b>고치면 계획에 바로 반영될 것</b>(복사하지 않는 이유), <b>지점이 전 지점 달력을 바꾸지
 * 못할 것</b>.
 */
@SpringBootTest
@Transactional
class AnnualEventTest {

    @Autowired AnnualEventService service;
    @Autowired EntityManager em;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    static final short YEAR = 2095;

    Academy bundang;
    AuthPrincipal head;
    AuthPrincipal branch;

    @BeforeEach
    void setUp() {
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);
        em.flush();

        head = new AuthPrincipal(1L, "admin", bundang.getId(),
                Set.of(Role.SUPER_ADMIN), true, false);
        branch = new AuthPrincipal(2L, "branch", bundang.getId(),
                Set.of(Role.BRANCH_ADMIN), false, false);
    }

    @Test
    @DisplayName("★★ 기간 행사는 중간 날짜만 걸쳐도 나온다 — 시작일만 보면 2·3일차가 빠진다")
    void findsEventOverlappingPeriod() {
        service.create(head, bundang.getId(), YEAR, "수련회",
                LocalDate.of(2095, 5, 10), LocalDate.of(2095, 5, 12),
                AnnualEventType.ACADEMY, true, null);
        em.flush();

        // 주간 계획이 5/11~5/17 이면 행사 시작일(5/10)은 이 주에 없다
        var found = service.findForPlan(head, bundang.getId(),
                LocalDate.of(2095, 5, 11), LocalDate.of(2095, 5, 17));

        assertThat(found).extracting(AnnualEvent::getName).containsExactly("수련회");
    }

    @Test
    @DisplayName("★ 고치면 계획 조회에 바로 반영된다 — 계획 행으로 복사하지 않는 이유다")
    void editReflectsImmediately() {
        AnnualEvent event = service.create(head, bundang.getId(), YEAR, "설명회",
                LocalDate.of(2095, 6, 1), LocalDate.of(2095, 6, 1),
                AnnualEventType.ACADEMY, true, null);
        em.flush();

        service.update(head, event.getId(), "학부모 설명회",
                LocalDate.of(2095, 6, 8), LocalDate.of(2095, 6, 8), null, null, null);
        em.flush();

        assertThat(service.findForPlan(head, bundang.getId(),
                LocalDate.of(2095, 6, 1), LocalDate.of(2095, 6, 7))).isEmpty();
        assertThat(service.findForPlan(head, bundang.getId(),
                LocalDate.of(2095, 6, 8), LocalDate.of(2095, 6, 14)))
                .extracting(AnnualEvent::getName).containsExactly("학부모 설명회");
    }

    @Test
    @DisplayName("★ 지우면 계획에서 빠진다 — 흩어진 복사본을 따라다닐 필요가 없다")
    void deleteRemovesFromPlan() {
        AnnualEvent event = service.create(head, bundang.getId(), YEAR, "개원식",
                LocalDate.of(2095, 3, 2), LocalDate.of(2095, 3, 2),
                AnnualEventType.ACADEMY, true, null);
        em.flush();

        service.delete(head, event.getId());
        em.flush();

        assertThat(service.findForPlan(head, bundang.getId(),
                LocalDate.of(2095, 3, 1), LocalDate.of(2095, 3, 7))).isEmpty();
    }

    @Test
    @DisplayName("내부 일정은 학습계획에 뜨지 않는다 — 등록은 하되 학생에게 안 보인다")
    void hiddenEventIsNotInPlan() {
        service.create(head, bundang.getId(), YEAR, "직원 워크숍",
                LocalDate.of(2095, 7, 1), LocalDate.of(2095, 7, 1),
                AnnualEventType.ETC, false, null);
        em.flush();

        assertThat(service.findForPlan(head, bundang.getId(),
                LocalDate.of(2095, 7, 1), LocalDate.of(2095, 7, 7))).isEmpty();
        // 목록에는 남는다 — 등록한 사람이 못 찾으면 고칠 수가 없다
        assertThat(service.findAllOfYear(head, bundang.getId(), YEAR)).hasSize(1);
    }

    @Test
    @DisplayName("전 지점 공통은 지점 목록에도 함께 나온다 — 공휴일과 같은 규칙이다")
    void sharedEventAppearsInBranchList() {
        service.create(head, null, YEAR, "수능",
                LocalDate.of(2095, 11, 20), LocalDate.of(2095, 11, 20),
                AnnualEventType.EXAM, true, null);
        em.flush();

        assertThat(service.findAllOfYear(branch, bundang.getId(), YEAR))
                .extracting(AnnualEvent::isShared).containsExactly(true);
    }

    @Test
    @DisplayName("★ 지점은 전 지점 공통 행사를 만들 수 없다 — 남의 지점 달력이 같이 바뀐다")
    void branchCannotCreateSharedEvent() {
        assertThatThrownBy(() -> service.create(branch, null, YEAR, "임의 공통행사",
                LocalDate.of(2095, 4, 1), LocalDate.of(2095, 4, 1),
                AnnualEventType.ACADEMY, true, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("본사");
    }

    @Test
    @DisplayName("종료일이 시작일보다 앞서면 거절한다")
    void rejectsInvertedPeriod() {
        assertThatThrownBy(() -> service.create(head, bundang.getId(), YEAR, "거꾸로",
                LocalDate.of(2095, 5, 10), LocalDate.of(2095, 5, 1),
                AnnualEventType.ACADEMY, true, null))
                .isInstanceOf(BusinessException.class);
    }
}
