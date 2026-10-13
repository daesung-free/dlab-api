package com.dlab.api.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.api.admin.admission.AdminAdmissionReservationController;
import com.dlab.api.admin.student.AdminStudentController;
import com.dlab.api.admin.student.StudentRequests;
import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.admission.entity.AdmissionReservation;
import com.dlab.domain.admission.entity.ConsultStatus;
import com.dlab.domain.admission.service.AdmissionDuplicateService;
import com.dlab.domain.admission.service.AdmissionReservationAdminService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 홈페이지 입학예약 ↔ 데스크 신규접수 <b>중복 탐지</b> (피드백 6-3①).
 *
 * <p>디랩 질문: <i>"홈페이지에서 입학상담예약을 완료했는데 신규접수 등록을 한 경우 두 개
 * 데이터가 각각 등록되나요?"</i> — 그대로 두면 각각 등록된다. 그래서 막는다.
 *
 * <p>지키려는 것 — <b>자동 병합은 하지 않을 것</b>(동명이인이 있고 되돌릴 수 없다),
 * <b>양방향으로 막힐 것</b>(한쪽만 막으면 순서를 바꿔 통과한다), <b>형식이 달라도 같은
 * 값으로 볼 것</b>(생년 {@code yyyyMMdd} 문자열 · 하이픈 섞인 연락처),
 * <b>이름만으로는 걸리지 않을 것</b>.
 */
@SpringBootTest
@Transactional
class AdmissionDuplicateTest {

    @Autowired AdmissionDuplicateService duplicateService;
    @Autowired AdmissionReservationAdminService adminService;
    @Autowired AdminAdmissionReservationController reservationController;
    @Autowired AdminStudentController studentController;
    @Autowired EntityManager em;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    static final short YEAR = 2094;

    Academy bundang;
    AdmissionReservation reservation;
    AuthPrincipal admin;

    @BeforeEach
    void setUp() {
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);

        // 생년은 규격서 필드라 yyyyMMdd 문자열, 연락처는 하이픈이 섞여 들어온다
        reservation = AdmissionReservation.builder()
                .academy(bundang).year(YEAR).rsvCd("RSV-2094-0001")
                .studentName("홍길동").studentTel("010-1111-2222").parentTel("010-3333-4444")
                .gender("M").birth("20080302").stdGrade("N")
                .agreeAd(true).promoAd(false)
                .build();
        em.persist(reservation);
        em.flush();

        admin = new AuthPrincipal(1L, "EMPLOYEE", bundang.getId(),
                Set.of(Role.BRANCH_ADMIN), false, false);
    }

    private StudentRequests.Admit admitRequest(String name, LocalDate birthDate, String phone,
                                               boolean confirmedNotDuplicate) {
        return new StudentRequests.Admit(bundang.getId(), (int) YEAR, name, phone,
                GradeType.N_SU, null, null, birthDate, "M", null, null, null,
                null, null, confirmedNotDuplicate);
    }

    @Test
    @WithMockUser(roles = "BRANCH_ADMIN")
    @DisplayName("★★ 같은 분의 입학예약이 있으면 신규접수가 멈춘다 — 그대로 두면 두 건으로 쌓인다")
    void newAdmissionStopsWhenReservationExists() {
        assertThatThrownBy(() -> studentController.admit(admin,
                admitRequest("홍길동", LocalDate.of(2008, 3, 2), "010-9999-8888", false)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ADMISSION_DUPLICATE_SUSPECTED);
    }

    @Test
    @WithMockUser(roles = "BRANCH_ADMIN")
    @DisplayName("★ 차단 응답에 후보 id 가 실린다 — 화면이 메시지를 파싱하지 않게")
    void blockCarriesCandidateIds() {
        assertThatThrownBy(() -> studentController.admit(admin,
                admitRequest("홍길동", LocalDate.of(2008, 3, 2), null, false)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getData())
                .isEqualTo(List.of(reservation.getId()));
    }

    @Test
    @WithMockUser(roles = "BRANCH_ADMIN")
    @DisplayName("★★ 「다른 사람입니다」를 고르면 그대로 등록된다 — 자동 병합을 하지 않으므로")
    void proceedsWhenConfirmedNotDuplicate() {
        var response = studentController.admit(admin,
                admitRequest("홍길동", LocalDate.of(2008, 3, 2), "010-9999-8888", true));
        em.flush();

        assertThat(response.data().studentNo()).startsWith(String.valueOf(YEAR));
        // 예약은 그대로 남는다 — 합치지 않았으므로 대기자 목록에서 사라지면 안 된다
        assertThat(reservation.converted()).isFalse();
    }

    @Test
    @WithMockUser(roles = "BRANCH_ADMIN")
    @DisplayName("★ 이름만 같으면 걸리지 않는다 — 동명이인이 전부 「같은 분」으로 뜬다")
    void nameAloneIsNotEnough() {
        assertThatCode(() -> studentController.admit(admin,
                admitRequest("홍길동", null, null, false)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★ 연락처는 하이픈을 무시한다 — 01011112222 와 010-1111-2222 는 같은 번호다")
    void telComparisonIgnoresHyphen() {
        var byStudentTel = duplicateService.findReservations(
                bundang.getId(), YEAR, "홍길동", null, "01011112222");
        assertThat(byStudentTel).hasSize(1);
        assertThat(byStudentTel.get(0).matchedBy())
                .isEqualTo(AdmissionDuplicateService.MatchedBy.TEL);

        // 데스크 폼에 학부모 번호를 적는 경우가 많다 — 양쪽과 비교해야 그 건이 빠지지 않는다
        assertThat(duplicateService.findReservations(
                bundang.getId(), YEAR, "홍길동", null, "010 3333 4444")).hasSize(1);
    }

    @Test
    @DisplayName("★ 생년은 형식이 달라도 같은 값으로 본다 — 날짜로 변환하면 터진다")
    void birthComparisonIgnoresFormat() {
        assertThat(duplicateService.findReservations(
                bundang.getId(), YEAR, "홍길동", "2008-03-02", null)).hasSize(1);
        assertThat(duplicateService.findReservations(
                bundang.getId(), YEAR, "홍길동", "20080302", null)).hasSize(1);
        assertThat(duplicateService.findReservations(
                bundang.getId(), YEAR, "홍길동", "20080303", null)).isEmpty();
    }

    @Test
    @WithMockUser(roles = "BRANCH_ADMIN")
    @DisplayName("조회 경로가 후보를 돌려준다 — 화면이 저장 전에 「같은 분이 있습니다」를 띄운다")
    void duplicatesEndpointReturnsCandidates() {
        var found = reservationController.duplicates(admin, bundang.getId(), YEAR,
                "홍길동", "20080302", null).data();

        assertThat(found).hasSize(1);
        assertThat(found.get(0).reservationId()).isEqualTo(reservation.getId());
        assertThat(found.get(0).studentName()).isEqualTo("홍길동");
        assertThat(found.get(0).matchedBy())
                .isEqualTo(AdmissionDuplicateService.MatchedBy.BIRTH);
    }

    @Test
    @WithMockUser(roles = "BRANCH_ADMIN")
    @DisplayName("★★ 반대 방향도 막힌다 — 데스크가 먼저 등록하면 순서만 바꿔 통과했다")
    void conversionStopsWhenStudentAlreadyEnrolled() {
        studentController.admit(admin,
                admitRequest("홍길동", LocalDate.of(2008, 3, 2), "010-9999-8888", true));
        adminService.changeStatus(admin, reservation.getId(), ConsultStatus.CONFIRMED, null);
        em.flush();

        assertThatThrownBy(() -> adminService.convert(
                admin, reservation.getId(), null, null, false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ADMISSION_DUPLICATE_SUSPECTED);
    }

    @Test
    @DisplayName("이미 전환된 예약은 후보가 아니다 — 눌러도 막히는 버튼을 띄우게 된다")
    void convertedReservationIsNotACandidate() {
        adminService.changeStatus(admin, reservation.getId(), ConsultStatus.CONFIRMED, null);
        adminService.convert(admin, reservation.getId(), null, null, false);
        em.flush();

        assertThat(duplicateService.findReservations(
                bundang.getId(), YEAR, "홍길동", "20080302", null)).isEmpty();
    }

    @Test
    @DisplayName("★ 다른 지점 예약은 후보가 아니다 — 지점이 다르면 같은 사람이어도 다른 건이다")
    void otherBranchIsNotACandidate() {
        Academy mokdong = new Academy("45", "목동", LocalTime.of(9, 0));
        em.persist(mokdong);
        em.flush();

        assertThat(duplicateService.findReservations(
                mokdong.getId(), YEAR, "홍길동", "20080302", null)).isEmpty();
    }
}
