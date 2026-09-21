package com.dlab.api.admin;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.admission.repository.AdmissionResultRepository;
import com.dlab.domain.admission.service.AdmissionResultImportService;
import com.dlab.domain.audit.AuditLogRepository;
import com.dlab.domain.master.entity.RoomMaster;
import com.dlab.domain.statistics.service.StatisticsService;
import com.dlab.domain.user.entity.*;
import com.dlab.domain.user.service.ClassService;
import com.dlab.domain.user.service.StudentService;
import com.dlab.domain.user.service.StudentStatusService;
import jakarta.persistence.EntityManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
 * 관리자 웹 막힌 것 2차 — 반별 휴원·퇴원·계열 · 반 강의실 · 영문명·졸업연도 · 수정 이력 대상 학생 · 실적 엑셀 등록.
 */
@SpringBootTest
@Transactional
class AdminWebGaps2Test {

    @Autowired StatisticsService statisticsService;
    @Autowired StudentStatusService statusService;
    @Autowired ClassService classService;
    @Autowired StudentService studentService;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired AdmissionResultImportService importService;
    @Autowired AdmissionResultRepository resultRepository;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    short year;
    AuthPrincipal admin;
    ClassMaster class1;

    @BeforeEach
    void setUp() {
        year = (short) LocalDate.now(clock).getYear();
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);
        class1 = new ClassMaster(bundang, year, "1반", ClassType.FIXED, null);
        em.persist(class1);
        em.flush();
        admin = new AuthPrincipal(1L, "branch", bundang.getId(), Set.of(Role.BRANCH_ADMIN), false, false);
    }

    private StudentEnrollment enroll(String code, String name, String stdNo, TrackType track) {
        Student s = new Student(code, name, "010-0000-0000");
        em.persist(s);
        StudentEnrollment e = new StudentEnrollment(s, bundang, year, stdNo, null, GradeType.N_SU);
        e.updateEnrollment(null, track, null);
        em.persist(e);
        em.persist(new ClassAssignment(bundang, e, class1, ClassType.FIXED));
        em.flush();
        return e;
    }

    @Test
    @DisplayName("★ 반별 통계에 휴원·퇴원 인원과 계열이 실린다 — 퇴원생은 나가기 직전 반으로 센다")
    void classStatsWithLeaveWithdrawnTracks() {
        enroll("DL-1", "이과생", "0001", TrackType.SCIENCE);
        enroll("DL-2", "문과생", "0002", TrackType.HUMANITIES);
        StudentEnrollment leave = enroll("DL-3", "휴원생", "0003", TrackType.SCIENCE);
        StudentEnrollment out = enroll("DL-4", "퇴원생", "0004", TrackType.SCIENCE);
        statusService.changeStatus(leave.getId(), EnrollmentStatus.LEAVE, "휴원", admin);
        statusService.changeStatus(out.getId(), EnrollmentStatus.WITHDRAWN, "퇴원", admin);
        em.flush();

        var row = statisticsService.group(admin, bundang.getId(), year,
                StatisticsService.GroupBy.CLASS, LocalDate.now(clock)).get(0);

        assertThat(row.count()).isEqualTo(2);
        assertThat(row.onLeave()).isEqualTo(1);
        assertThat(row.withdrawn()).isEqualTo(1);
        assertThat(row.tracks()).containsEntry("SCIENCE", 1L).containsEntry("HUMANITIES", 1L);
    }

    @Test
    @DisplayName("반에 강의실을 붙인다 — 다른 지점 강의실은 안 된다")
    void assignRoom() {
        RoomMaster room = new RoomMaster(bundang, "301", "3층 대강의실", (short) 40, null);
        em.persist(room);
        Academy ilsan = new Academy("32", "일산", LocalTime.of(9, 0));
        em.persist(ilsan);
        RoomMaster other = new RoomMaster(ilsan, "101", "일산 강의실", null, null);
        em.persist(other);
        em.flush();

        assertThat(classService.assignRoom(class1.getId(), room.getId(), admin).getRoom().getName())
                .isEqualTo("3층 대강의실");
        assertThatThrownBy(() -> classService.assignRoom(class1.getId(), other.getId(), admin))
                .isInstanceOf(BusinessException.class);
        assertThat(classService.assignRoom(class1.getId(), null, admin).getRoom()).isNull();
    }

    @Test
    @DisplayName("영문명·졸업연도 — null 은 안 바꾸고, 빈 영문명은 지운다")
    void englishNameAndGraduationYear() {
        StudentEnrollment e = enroll("DL-E", "홍길동", "0001", null);

        studentService.updateExtra(e.getId(), "Gildong Hong", (short) 2025, false, admin);
        assertThat(e.getStudent().getEnglishName()).isEqualTo("Gildong Hong");
        assertThat(e.getStudent().getGraduationYear()).isEqualTo((short) 2025);

        studentService.updateExtra(e.getId(), null, null, false, admin);
        assertThat(e.getStudent().getEnglishName()).isEqualTo("Gildong Hong");

        studentService.updateExtra(e.getId(), "", null, true, admin);
        assertThat(e.getStudent().getEnglishName()).isNull();
        assertThat(e.getStudent().getGraduationYear()).isNull();
    }

    @Test
    @DisplayName("★ 수정 이력에 대상 학생(등록 건)이 남는다 — 이름·학번은 조회할 때 붙인다")
    void auditLogKeepsTargetEnrollment() {
        StudentEnrollment e = enroll("DL-AU", "이력학생", "0001", null);
        e.updateEnrollment(null, TrackType.SCIENCE, null);
        em.flush();

        var logs = auditLogRepository.findAll().stream()
                .filter(l -> e.getId().equals(l.getEntityId()) && e.getId().equals(l.getTargetEnrollmentId()))
                .toList();
        assertThat(logs).isNotEmpty();
    }

    @Test
    @DisplayName("★ 실적 엑셀 등록 — 학번·이름 대조, 이미 있는 지원·정원 초과는 오류로 빠진다")
    void importAdmissionResults() throws Exception {
        enroll("DL-R1", "김합격", "0001", null);
        enroll("DL-R2", "이정시", "0002", null);

        byte[] file = excel(List.of(
                List.of("학번", "이름", "구분", "대학명", "학과명", "결과"),
                List.of("0001", "김합격", "수시", "가나대", "국문", "합격"),
                List.of("0001", "김합격", "수시", "가나대", "국문", "합격"),       // 파일 안 중복
                List.of("0002", "박엉뚱", "정시", "다라대", "철학", ""),          // 이름 불일치
                List.of("9999", "", "정시", "다라대", "철학", ""),                // 없는 학번
                List.of("0002", "이정시", "정시", "다라대", "철학", ""),
                List.of("0002", "이정시", "정시", "마바대", "사학", "애매함")));   // 결과 오타

        var preview = importService.preview(admin, null, new ByteArrayInputStream(file));
        assertThat(preview.validRows()).isEqualTo(2);
        assertThat(preview.errorRows()).isEqualTo(4);

        importService.importResults(admin, null, new ByteArrayInputStream(file));
        em.flush();
        assertThat(resultRepository.findAll()).hasSize(2);

        // 같은 파일을 다시 올려도 쌓이지 않는다
        var again = importService.importResults(admin, null, new ByteArrayInputStream(file));
        em.flush();
        assertThat(again.validRows()).isZero();
        assertThat(resultRepository.findAll()).hasSize(2);
    }

    private byte[] excel(List<List<String>> rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("실적");
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < rows.get(r).size(); c++) {
                    row.createCell(c).setCellValue(rows.get(r).get(c));
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }
}
