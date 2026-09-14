package com.dlab.api.facility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.attendance.entity.AttendanceEventType;
import com.dlab.domain.attendance.entity.AttendanceSource;
import com.dlab.domain.attendance.entity.AttendanceTaggingLog;
import com.dlab.domain.facility.entity.SeatAssignment;
import com.dlab.domain.facility.entity.SeatMaster;
import com.dlab.domain.facility.entity.SeatPresence;
import com.dlab.domain.facility.entity.StudyArea;
import com.dlab.domain.facility.service.SeatLayoutService;
import com.dlab.domain.facility.service.SeatLayoutService.SeatCell;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.support.FacilityFixtures;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
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
 * 좌석배치도 (독서실 좌석배치표).
 *
 * <p>핵심은 <b>배정 축과 재실 축이 따로 나온다</b>와 <b>키오스크와 같은 판정을 쓴다</b> 둘이다.
 */
@SpringBootTest
@Transactional
class SeatLayoutTest {

    @Autowired SeatLayoutService seatLayoutService;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    StudyArea areaA;
    AuthPrincipal admin;
    LocalDate today;

    @BeforeEach
    void setUp() {
        today = LocalDate.now(clock);

        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);
        areaA = new StudyArea(FacilityFixtures.mainBuilding(em, bundang), "A", "A", "A실", (short) 1);
        em.persist(areaA);
        em.flush();

        admin = AuthPrincipal.of(1L, "EMPLOYEE", bundang.getId(),
                List.of(Role.BRANCH_ADMIN), false);
    }

    private SeatMaster seat(String cd, int x, int y) {
        SeatMaster s = new SeatMaster(areaA, cd, cd, cd, x, y);
        em.persist(s);
        em.flush();
        return s;
    }

    private StudentEnrollment assign(SeatMaster seat, String name, String stdNo) {
        Student student = new Student("DL-" + stdNo, name, "010-0000-0000");
        em.persist(student);
        StudentEnrollment e = new StudentEnrollment(
                student, bundang, (short) 2026, stdNo, null, GradeType.HIGH3);
        em.persist(e);
        em.persist(new SeatAssignment(bundang, seat, e));
        em.flush();
        return e;
    }

    private void tag(StudentEnrollment e, AttendanceEventType type, int hour) {
        em.persist(new AttendanceTaggingLog(bundang, e, type, AttendanceSource.KIOSK_NFC,
                today.atTime(hour, 0).atZone(clock.getZone()).toInstant(), today));
        em.flush();
    }

    private SeatCell cellOf(String seatCd) {
        em.flush();
        em.clear();
        return seatLayoutService.layout(admin, areaA.getId(), false).stream()
                .filter(c -> c.seatCd().equals(seatCd)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("좌표가 그대로 나온다 — 도면을 그리는 값이다")
    void coordinatesAreReturned() {
        seat("A-01", 3, 5);

        SeatCell cell = cellOf("A-01");
        assertThat(cell.xPos()).isEqualTo(3);
        assertThat(cell.yPos()).isEqualTo(5);
    }

    @Test
    @DisplayName("★ 배정 없는 좌석도 나온다 — 빼면 도면에 구멍이 생겨 좌표가 어긋나 보인다")
    void unassignedSeatIsIncluded() {
        seat("A-01", 1, 1);
        SeatMaster taken = seat("A-02", 2, 1);
        assign(taken, "김민지", "2026-0001");

        assertThat(seatLayoutService.layout(admin, areaA.getId(), false)).hasSize(2);
        assertThat(cellOf("A-01").assignmentState()).isEqualTo("UNASSIGNED");
        assertThat(cellOf("A-01").presence()).isEqualTo(SeatPresence.EMPTY);
    }

    @Test
    @DisplayName("★★ 배정 축과 재실 축이 따로 나온다 — 합치면 '미등원'과 '빈자리'가 구분 안 된다")
    void assignmentAndPresenceAreSeparateAxes() {
        SeatMaster s = seat("A-01", 1, 1);
        assign(s, "김민지", "2026-0001");   // 배정됐지만 아직 안 옴

        SeatCell cell = cellOf("A-01");
        assertThat(cell.assignmentState()).isEqualTo("ASSIGNED");
        assertThat(cell.presence()).isEqualTo(SeatPresence.ABSENT);
    }

    @Test
    @DisplayName("등원하면 재실")
    void checkedInIsPresent() {
        SeatMaster s = seat("A-01", 1, 1);
        StudentEnrollment e = assign(s, "김민지", "2026-0001");
        tag(e, AttendanceEventType.CHECK_IN, 8);

        assertThat(cellOf("A-01").presence()).isEqualTo(SeatPresence.PRESENT);
    }

    @Test
    @DisplayName("외출 중이면 OUT — 마지막 태깅으로 판정한다")
    void outingIsOut() {
        SeatMaster s = seat("A-01", 1, 1);
        StudentEnrollment e = assign(s, "김민지", "2026-0001");
        tag(e, AttendanceEventType.CHECK_IN, 8);
        tag(e, AttendanceEventType.OUTING, 14);

        assertThat(cellOf("A-01").presence()).isEqualTo(SeatPresence.OUT);
    }

    @Test
    @DisplayName("★ 하원·조퇴한 자리는 공석이 아니라 미등원 — 남에게 줄 수 있는 빈자리가 아니다")
    void checkedOutIsAbsentNotEmpty() {
        SeatMaster s = seat("A-01", 1, 1);
        StudentEnrollment e = assign(s, "김민지", "2026-0001");
        tag(e, AttendanceEventType.CHECK_IN, 8);
        tag(e, AttendanceEventType.CHECK_OUT, 22);

        SeatCell cell = cellOf("A-01");
        assertThat(cell.presence()).isEqualTo(SeatPresence.ABSENT);
        assertThat(cell.assignmentState()).isEqualTo("ASSIGNED");
    }

    @Test
    @DisplayName("★ 사용중지는 배정 축이다 — 재실 축과 별개로 표기된다")
    void disabledSeatIsAnAssignmentAxisState() {
        SeatMaster s = seat("A-01", 1, 1);
        s.disable();
        em.flush();

        SeatCell cell = cellOf("A-01");
        assertThat(cell.assignmentState()).isEqualTo("DISABLED");
        assertThat(cell.presence()).isEqualTo(SeatPresence.EMPTY);
    }

    @Test
    @DisplayName("★ 학생 이름은 마스킹된다 — 도면을 벽에 띄워두는 일이 있다")
    void studentNameIsMasked() {
        SeatMaster s = seat("A-01", 1, 1);
        assign(s, "김민지", "2026-0001");

        assertThat(cellOf("A-01").studentName()).isEqualTo("김*지");
    }

    @Test
    @DisplayName("구역 목록에 좌석 수가 함께 나온다")
    void areaListIncludesSeatCount() {
        seat("A-01", 1, 1);
        seat("A-02", 2, 1);
        em.flush();
        em.clear();

        assertThat(seatLayoutService.areas(admin, bundang.getId())).first()
                .satisfies(a -> {
                    assertThat(a.areaCd()).isEqualTo("A");
                    assertThat(a.seatCount()).isEqualTo(2);
                });
    }

    @Test
    @DisplayName("★ 다른 지점 구역은 볼 수 없다")
    void otherAcademyAreaIsRejected() {
        Academy ilsan = new Academy("32", "일산", LocalTime.of(9, 0));
        em.persist(ilsan);
        em.flush();

        AuthPrincipal ilsanAdmin = AuthPrincipal.of(2L, "EMPLOYEE", ilsan.getId(),
                List.of(Role.BRANCH_ADMIN), false);

        assertThatThrownBy(() -> seatLayoutService.layout(ilsanAdmin, areaA.getId(), false))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("★ 키오스크와 같은 판정을 쓴다 — 두 벌이면 같은 좌석이 다르게 보인다")
    void sameRuleAsKiosk() {
        assertThat(SeatPresence.of(AttendanceEventType.CHECK_IN).dsaCode()).isEqualTo("S");
        assertThat(SeatPresence.of(AttendanceEventType.OUTING).dsaCode()).isEqualTo("D");
        assertThat(SeatPresence.of(AttendanceEventType.CHECK_OUT).dsaCode()).isEqualTo("N");
        assertThat(SeatPresence.of(null).dsaCode()).isEqualTo("N");
        assertThat(SeatPresence.EMPTY.dsaCode()).isEqualTo("B");
    }
}
