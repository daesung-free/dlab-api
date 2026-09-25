package com.dlab.api.admission;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.admission.entity.AdmissionReservation;
import com.dlab.domain.admission.entity.ConsultStatus;
import com.dlab.domain.admission.service.AdmissionReservationAdminService;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.EntityManager;
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
 * 입학예약 관리 = 대기자 관리 (F-4.2-1).
 *
 * <p>지키려는 것 — <b>입학확정에서만 학생이 될 것</b>(학번은 회수되지 않는다),
 * <b>두 번 전환되지 않을 것</b>, <b>상태를 바꾼 이유가 남을 것</b>.
 */
@SpringBootTest
@Transactional
class AdmissionReservationAdminTest {

    @Autowired AdmissionReservationAdminService service;
    @Autowired EntityManager em;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    static final short YEAR = 2093;

    Academy bundang;
    AdmissionReservation reservation;
    AuthPrincipal admin;

    @BeforeEach
    void setUp() {
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);

        reservation = AdmissionReservation.builder()
                .academy(bundang).year(YEAR).rsvCd("RSV-2093-0001")
                .studentName("박예약").studentTel("010-3333-4444").parentTel("010-5555-6666")
                .gender("M").birth("20070315").stdGrade("N")
                .schNmHigh("디랩고").addr1("경기도 성남시").addr2("101동 202호")
                .agreeAd(true).promoAd(false)
                .build();
        em.persist(reservation);
        em.flush();

        admin = new AuthPrincipal(1L, "admin", bundang.getId(),
                Set.of(Role.SUPER_ADMIN), true, false);
    }

    @Test
    @DisplayName("접수 직후 상태는 「통화필요」다 — 아무것도 안 한 건과 처리한 건이 구분돼야 한다")
    void defaultStatusIsCallNeeded() {
        assertThat(reservation.getConsultStatus()).isEqualTo(ConsultStatus.CALL_NEEDED);
        assertThat(service.search(admin, YEAR, ConsultStatus.CALL_NEEDED, null)).hasSize(1);
    }

    @Test
    @DisplayName("★★ 입학확정이 아니면 학생으로 전환하지 않는다 — 학번은 회수되지 않는다")
    void convertsOnlyWhenConfirmed() {
        service.changeStatus(admin, reservation.getId(), ConsultStatus.CANCELED, "연락 두절");
        em.flush();

        assertThatThrownBy(() -> service.convert(admin, reservation.getId(), null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("입학확정");
    }

    @Test
    @DisplayName("입학확정이면 학생이 된다 — 학번은 기존 신규 접수 경로가 채번한다")
    void convertsWhenConfirmed() {
        service.changeStatus(admin, reservation.getId(), ConsultStatus.CONFIRMED, null);
        em.flush();

        var enrollment = service.convert(admin, reservation.getId(), null, null);
        em.flush();

        assertThat(enrollment.getStudentNo()).startsWith(String.valueOf(YEAR));
        assertThat(enrollment.getStudent().getName()).isEqualTo("박예약");
        assertThat(reservation.converted()).isTrue();
    }

    @Test
    @DisplayName("★ 두 번 전환하지 않는다 — 학번이 두 개면 출결·수납이 갈라진다")
    void doesNotConvertTwice() {
        service.changeStatus(admin, reservation.getId(), ConsultStatus.CONFIRMED, null);
        service.convert(admin, reservation.getId(), null, null);
        em.flush();

        assertThatThrownBy(() -> service.convert(admin, reservation.getId(), null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미");
    }

    @Test
    @DisplayName("★ 상태를 바꾼 이유가 남는다 — 없으면 「왜 미등록이 됐나」에 답할 수 없다")
    void keepsStatusHistory() {
        service.changeStatus(admin, reservation.getId(), ConsultStatus.CONSULTED, "1차 상담");
        service.changeStatus(admin, reservation.getId(), ConsultStatus.NOT_REGISTERED, "타 학원 등록");
        em.flush();

        var logs = service.statusLogs(admin, reservation.getId());

        assertThat(logs).hasSize(2);
        assertThat(logs.get(0).getToStatus()).isEqualTo(ConsultStatus.NOT_REGISTERED);
        assertThat(logs.get(0).getFromStatus()).isEqualTo(ConsultStatus.CONSULTED);
        assertThat(logs.get(0).getReason()).isEqualTo("타 학원 등록");
    }

    @Test
    @DisplayName("메모를 남기고 지운다 — 지워도 목록에서만 빠진다")
    void memoLifecycle() {
        var memo = service.addMemo(admin, reservation.getId(), "학부모가 통학 거리 문의");
        em.flush();
        assertThat(service.memos(admin, reservation.getId())).hasSize(1);

        service.deleteMemo(admin, memo.getId());
        em.flush();
        assertThat(service.memos(admin, reservation.getId())).isEmpty();
    }

    @Test
    @DisplayName("★ 다른 지점 신청은 보이지 않는다 — 지점은 권한에서 온다")
    void otherBranchIsNotVisible() {
        Academy mokdong = new Academy("45", "목동", LocalTime.of(9, 0));
        em.persist(mokdong);
        em.persist(AdmissionReservation.builder()
                .academy(mokdong).year(YEAR).rsvCd("RSV-2093-0002")
                .studentName("목동신청").studentTel("010-7777-8888").parentTel("010-9999-0000")
                .stdGrade("N").agreeAd(true).promoAd(false)
                .build());
        em.flush();

        AuthPrincipal branchAdmin = new AuthPrincipal(2L, "branch", bundang.getId(),
                Set.of(Role.BRANCH_ADMIN), false, false);

        assertThat(service.search(branchAdmin, YEAR, null, null))
                .extracting(AdmissionReservation::getStudentName)
                .containsExactly("박예약");
        // 전 지점 권한자에게는 둘 다 보인다
        assertThat(service.search(admin, YEAR, null, null)).hasSize(2);
    }
}
