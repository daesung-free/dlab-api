package com.dlab.api.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;

import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.attendance.entity.AttendanceDailyStatus;
import com.dlab.domain.attendance.entity.AttendanceEventType;
import com.dlab.domain.attendance.entity.AttendanceSource;
import com.dlab.domain.attendance.entity.AttendanceTaggingLog;
import com.dlab.domain.attendance.entity.DailyStatus;
import com.dlab.domain.attendance.service.AttendanceBoardService;
import com.dlab.domain.attendance.service.AttendanceBoardService.AttendanceRow;
import com.dlab.domain.attendance.service.AttendanceBoardService.ScreenStatus;
import com.dlab.domain.period.entity.DayType;
import com.dlab.domain.period.entity.PeriodMaster;
import com.dlab.domain.period.entity.PeriodType;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.EnrollmentStatus;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
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
 * 관리자 웹 출결 현황 (F-4.3-1).
 *
 * <p>화면(`Attendance.tsx`) 기준으로 검증한다 — 상태 5종 매핑과
 * <b>결석자가 목록에 남는지</b>가 핵심이다.
 */
@SpringBootTest
@Transactional
class AdminAttendanceBoardTest {

    @Autowired AttendanceBoardService boardService;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    LocalDate day;
    AuthPrincipal admin;

    @BeforeEach
    void setUp() {
        day = LocalDate.now(clock);

        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);
        em.persist(new PeriodMaster(bundang, (short) 2026, (short) 1, "자습",
                DayType.of(day), PeriodType.SELF_STUDY,
                LocalTime.of(8, 0), LocalTime.of(22, 0)));
        em.flush();

        admin = AuthPrincipal.of(1L, "EMPLOYEE", bundang.getId(),
                List.of(Role.BRANCH_ADMIN), false);
    }

    private StudentEnrollment enroll(String code, String name, String stdNo) {
        Student student = new Student(code, name, "010-0000-0000");
        em.persist(student);
        StudentEnrollment e = new StudentEnrollment(
                student, bundang, (short) 2026, stdNo, null, GradeType.HIGH3);
        em.persist(e);
        em.flush();
        return e;
    }

    private void tag(StudentEnrollment e, AttendanceEventType type, int hour, int minute) {
        em.persist(new AttendanceTaggingLog(bundang, e, type, AttendanceSource.KIOSK_NFC,
                day.atTime(hour, minute).atZone(clock.getZone()).toInstant(), day));
        em.flush();
    }

    private void confirm(StudentEnrollment e, DailyStatus status, boolean excused) {
        em.persist(new AttendanceDailyStatus(bundang, e, day, status, excused));
        em.flush();
    }

    private AttendanceRow rowOf(List<AttendanceRow> rows, String stdNo) {
        return rows.stream().filter(r -> stdNo.equals(r.studentNo())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("★ 결석자가 목록에 남는다 — 태깅한 학생만 주면 결석을 셀 수 없다")
    void absentStudentsStayInTheList() {
        StudentEnrollment came = enroll("DL-1", "김민지", "2026-0001");
        enroll("DL-2", "박서준", "2026-0002");   // 안 옴
        tag(came, AttendanceEventType.CHECK_IN, 8, 30);

        List<AttendanceRow> rows = boardService.board(admin, null, day, null);

        assertThat(rows).hasSize(2);
        assertThat(rowOf(rows, "2026-0002").status()).isEqualTo(ScreenStatus.ABSENT);
    }

    @Test
    @DisplayName("★ 외출 중은 OUT — 일자 상태가 아니라 지금 나가 있는지다")
    void currentlyOutIsOut() {
        StudentEnrollment student = enroll("DL-1", "김민지", "2026-0001");
        tag(student, AttendanceEventType.CHECK_IN, 8, 30);
        tag(student, AttendanceEventType.OUTING, 14, 0);

        assertThat(rowOf(boardService.board(admin, null, day, null), "2026-0001").status())
                .isEqualTo(ScreenStatus.OUT);
    }

    @Test
    @DisplayName("복귀하면 OUT이 풀린다")
    void returnClearsOut() {
        StudentEnrollment student = enroll("DL-1", "김민지", "2026-0001");
        tag(student, AttendanceEventType.CHECK_IN, 8, 30);
        tag(student, AttendanceEventType.OUTING, 14, 0);
        tag(student, AttendanceEventType.RETURN, 15, 0);

        assertThat(rowOf(boardService.board(admin, null, day, null), "2026-0001").status())
                .isEqualTo(ScreenStatus.ON_TIME);
    }

    @Test
    @DisplayName("★ EXCUSED는 상태가 아니라 플래그 — 결석이면서 사유 승인일 수 있다")
    void excusedIsAFlagNotAStatus() {
        StudentEnrollment student = enroll("DL-1", "김민지", "2026-0001");
        confirm(student, DailyStatus.ABSENT, true);

        AttendanceRow row = rowOf(boardService.board(admin, null, day, null), "2026-0001");

        assertThat(row.status()).isEqualTo(ScreenStatus.ABSENT);
        assertThat(row.excused()).isTrue();
    }

    @Test
    @DisplayName("★ 무단 지각만 배지가 붙는다 — 사유 낸 지각과 섞이면 누구에게 연락할지 모른다")
    void onlyUnexcusedLateIsFlagged() {
        StudentEnrollment unexcused = enroll("DL-1", "김민지", "2026-0001");
        StudentEnrollment excused = enroll("DL-2", "박서준", "2026-0002");
        tag(unexcused, AttendanceEventType.LATE, 9, 30);
        tag(excused, AttendanceEventType.LATE, 9, 40);
        confirm(excused, DailyStatus.LATE, true);

        List<AttendanceRow> rows = boardService.board(admin, null, day, null);

        assertThat(rowOf(rows, "2026-0001").unexcusedLate()).isTrue();
        assertThat(rowOf(rows, "2026-0002").unexcusedLate()).isFalse();
    }

    @Test
    @DisplayName("★ 확정 전(오늘)에도 원장으로 판정한다 — 안 그러면 오늘이 통째로 빈다")
    void todayIsDerivedFromLedgerBeforeBatchConfirms() {
        StudentEnrollment student = enroll("DL-1", "김민지", "2026-0001");
        tag(student, AttendanceEventType.LATE, 9, 30);

        // attendance_daily_status가 아직 없다(배치는 새벽 2시)
        AttendanceRow row = rowOf(boardService.board(admin, null, day, null), "2026-0001");

        assertThat(row.status()).isEqualTo(ScreenStatus.LATE);
        assertThat(row.checkInAt()).isEqualTo(LocalTime.of(9, 30));
    }

    @Test
    @DisplayName("하원 시각은 하원·조퇴만 — 외출은 하원이 아니다")
    void departureIgnoresOuting() {
        StudentEnrollment student = enroll("DL-1", "김민지", "2026-0001");
        tag(student, AttendanceEventType.CHECK_IN, 8, 30);
        tag(student, AttendanceEventType.OUTING, 14, 0);
        tag(student, AttendanceEventType.RETURN, 15, 0);
        tag(student, AttendanceEventType.CHECK_OUT, 22, 0);

        AttendanceRow row = rowOf(boardService.board(admin, null, day, null), "2026-0001");
        assertThat(row.checkOutAt()).isEqualTo(LocalTime.of(22, 0));
    }

    @Test
    @DisplayName("★ 휴원생은 대상이 아니다")
    void nonEnrolledIsExcluded() {
        StudentEnrollment onLeave = enroll("DL-1", "김민지", "2026-0001");
        onLeave.updateEnrollment(null, null, EnrollmentStatus.LEAVE);
        em.flush();

        assertThat(boardService.board(admin, null, day, null)).isEmpty();
    }

    @Test
    @DisplayName("순공시간이 함께 나온다")
    void studyTimeIsIncluded() {
        StudentEnrollment student = enroll("DL-1", "김민지", "2026-0001");
        tag(student, AttendanceEventType.CHECK_IN, 8, 0);
        tag(student, AttendanceEventType.CHECK_OUT, 12, 0);

        AttendanceRow row = rowOf(boardService.board(admin, null, day, null), "2026-0001");

        assertThat(row.studyMinutes()).isEqualTo(240);
        assertThat(row.studyTimeLabel()).isEqualTo("4시간 00분");
    }

    @Test
    @DisplayName("★ 엑셀에 결석자까지 전부 나온다 — 화면과 같은 목록이어야 대조가 된다")
    void exportContainsEveryStudent() {
        StudentEnrollment came = enroll("DL-1", "김민지", "2026-0001");
        enroll("DL-2", "박서준", "2026-0002");
        tag(came, AttendanceEventType.CHECK_IN, 8, 30);

        String text = textOf(boardService.export(admin, null, day, null, false));

        assertThat(text).contains("2026-0001").contains("2026-0002");
        assertThat(text).contains("결석");   // 코드값이 아니라 화면 표기로 나가야 한다
    }

    @Test
    @DisplayName("★ 엑셀 연락처는 마스킹이 기본 — 파일은 회수가 안 된다")
    void exportMasksPhoneByDefault() {
        StudentEnrollment student = enroll("DL-1", "김민지", "2026-0001");
        com.dlab.domain.user.entity.ParentGuardian guardian =
                new com.dlab.domain.user.entity.ParentGuardian("김보호", "010-9999-8888", "M");
        em.persist(guardian);
        em.persist(new com.dlab.domain.user.entity.StudentGuardianLink(
                student.getStudent(), guardian, (short) 1));
        em.flush();

        assertThat(textOf(boardService.export(admin, null, day, null, false)))
                .contains("010-****-8888").doesNotContain("010-9999-8888");

        assertThat(textOf(boardService.export(admin, null, day, null, true)))
                .contains("010-9999-8888");
    }

    /** 엑셀 전 셀을 한 문자열로. 마스킹·표기만 보면 되므로 좌표까지 따지지 않는다. */
    private String textOf(byte[] xlsx) {
        try (var wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(
                new java.io.ByteArrayInputStream(xlsx))) {
            StringBuilder sb = new StringBuilder();
            wb.getSheetAt(0).forEach(row -> row.forEach(c -> sb.append(c.toString()).append('|')));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── 지점 스코프 ────────────────────────────────────────────
    // 전 지점 권한자(본사)에게 academyScopeFilter()는 "필터 없음"이라 null이다.
    // 그 null을 "지점 모름"으로 읽고 400을 던지면 본사는 화면을 아예 못 연다.

    @Test
    @DisplayName("★ 본사가 지점을 고르면 그 지점 출결이 보인다 — 예전엔 400이라 화면이 안 열렸다")
    void headOfficeSeesPickedAcademy() {
        enroll("DL-1", "김민지", "2026-0001");
        AuthPrincipal headOffice = AuthPrincipal.of(9L, "EMPLOYEE", null,
                List.of(Role.SUPER_ADMIN), true);

        assertThat(boardService.board(headOffice, bundang.getId(), day, null)).hasSize(1);
    }

    @Test
    @DisplayName("본사가 지점을 안 고르면 400 — 전 지점을 한 화면에 섞어 뿌리지 않는다")
    void headOfficeMustPickAcademy() {
        AuthPrincipal headOffice = AuthPrincipal.of(9L, "EMPLOYEE", null,
                List.of(Role.SUPER_ADMIN), true);

        assertThatThrownBy(() -> boardService.board(headOffice, null, day, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("지점 관리자는 안 골라도 자기 지점이 보인다")
    void branchAdminDefaultsToOwnAcademy() {
        enroll("DL-1", "김민지", "2026-0001");

        assertThat(boardService.board(admin, null, day, null)).hasSize(1);
    }

    @Test
    @DisplayName("★ 지점 관리자가 다른 지점을 지정하면 거부된다 — 요청 값을 그대로 믿지 않는다")
    void branchAdminCannotPickOtherAcademy() {
        Academy ilsan = new Academy("32", "일산", LocalTime.of(9, 0));
        em.persist(ilsan);
        em.flush();

        assertThatThrownBy(() -> boardService.board(admin, ilsan.getId(), day, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
    }

    @Test
    @DisplayName("★ 기간 조회는 학생×날짜로 행이 늘어난다 — 하루 조회의 '1인 1행'과 다르다")
    void rangeQueryReturnsRowPerDay() {
        enroll("RNG01", "범위학생", "2026-0009");

        var rows = boardService.board(admin, null, day.minusDays(2), day, null);

        // 재원생 1명 × 3일
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(r -> r.date()).contains(day, day.minusDays(1), day.minusDays(2));
    }

    @Test
    @DisplayName("★ 기간이 너무 넓으면 거부한다 — 페이징이 없는 응답이라 조용히 느려진다")
    void tooWideRangeIsRejected() {
        assertThatThrownBy(() -> boardService.board(admin, null, day.minusDays(40), day, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("시작이 끝보다 뒤면 거부한다")
    void invertedRangeIsRejected() {
        assertThatThrownBy(() -> boardService.board(admin, null, day, day.minusDays(1), null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("★ 아직 오지 않은 날은 결석이 아니다 — 미래 날짜에 전원 결석이 나오던 건")
    void futureDateIsNotAbsent() {
        enroll("FUT-001", "미래학생", "2026-0101");

        // 4주 뒤 — 같은 요일이라 교시 마스터 구성이 같다
        var rows = boardService.board(admin, null, day.plusDays(28), null);

        // 태깅도 확정도 없는 미래 날짜다. NOT_YET 이 없으면
        // "등원 태깅이 없다 → ABSENT" 에 걸려 재원생 전원이 결석으로 나간다
        assertThat(rows).isNotEmpty();
        assertThat(rows).extracting(r -> r.status())
                .containsOnly(AttendanceBoardService.ScreenStatus.NOT_YET);
        assertThat(rows).extracting(r -> r.status())
                .doesNotContain(AttendanceBoardService.ScreenStatus.ABSENT);
    }

    @Test
    @DisplayName("지난 날짜는 그대로 결석으로 판정된다 — 미래 분기가 과거까지 먹으면 안 된다")
    void pastDateStillAbsent() {
        enroll("PAST-001", "과거학생", "2026-0102");

        var rows = boardService.board(admin, null, day.minusDays(28), null);

        assertThat(rows).extracting(r -> r.status())
                .contains(AttendanceBoardService.ScreenStatus.ABSENT);
    }
}
