package com.dlab.api.grade;

import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.grade.entity.ExamCode;
import com.dlab.domain.grade.entity.ExamMaster;
import com.dlab.domain.grade.service.MockExamUploadService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.EntityManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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

/**
 * 모의고사 성적 엑셀 업로드.
 *
 * <p>지키려는 것 — <b>매칭 안 된 행 때문에 전체가 막히지 않을 것</b>,
 * <b>동명이인을 임의로 고르지 않을 것</b>, <b>회차 양식 밖 과목을 넣지 않을 것</b>.
 */
@SpringBootTest
@Transactional
class MockExamUploadTest {

    @Autowired MockExamUploadService uploadService;
    @Autowired EntityManager em;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    static final short YEAR = 2098;

    Academy bundang;
    ExamMaster june;
    AuthPrincipal admin;

    @BeforeEach
    void setUp() {
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);

        enrollment("전승은", "2098-0001");
        enrollment("김지성", "2098-0002");
        // 동명이인 — 둘 중 하나를 고르면 남의 성적이 들어간다
        enrollment("이중복", "2098-0003");
        enrollment("이중복", "2098-0004");

        june = ExamMaster.common(YEAR, GradeType.HIGH3, ExamCode.JUNE, "6월 평가원 모의고사", 1);
        june.addSubject("KOREAN", "국어", 1);
        june.addSubject("MATH", "수학", 2);
        // ★ 파일에는 있지만 이 회차 양식에는 없는 과목(한국사)은 저장되지 않아야 한다
        em.persist(june);
        em.flush();

        admin = new AuthPrincipal(1L, "admin", bundang.getId(), Set.of(Role.SUPER_ADMIN),
                true, false);
    }

    private void enrollment(String name, String studentNo) {
        Student student = new Student("DL-" + studentNo, name, "010-0000-0000");
        em.persist(student);
        em.persist(new StudentEnrollment(student, bundang, YEAR, studentNo, null, GradeType.HIGH3));
    }

    @Test
    @DisplayName("★ 미리보기는 저장하지 않는다 — 605명 파일을 바로 반영하면 되돌릴 방법이 없다")
    void previewDoesNotSave() {
        var preview = uploadService.preview(admin, bundang.getId(), june.getId(),
                new ByteArrayInputStream(sample()));

        assertThat(preview.matched()).hasSize(2);
        assertThat(preview.totalRows()).isEqualTo(3);

        Long saved = em.createQuery("""
                SELECT COUNT(s) FROM StudentExamScore s
                """, Long.class).getSingleResult();
        assertThat(saved).isZero();
    }

    @Test
    @DisplayName("★ 동명이인은 매칭하지 않는다 — 둘 중 하나를 고르면 남의 성적이 들어간다")
    void duplicateNameIsNotMatched() {
        var preview = uploadService.preview(admin, bundang.getId(), june.getId(),
                new ByteArrayInputStream(sample()));

        assertThat(preview.unmatched()).hasSize(1);
        assertThat(preview.unmatched().get(0).name()).isEqualTo("이중복");
        assertThat(preview.unmatched().get(0).reason()).contains("2명");
    }

    @Test
    @DisplayName("★ 매칭된 학생만 저장한다 — 한 명 때문에 나머지를 다시 올리게 하면 안 된다")
    void appliesMatchedOnly() {
        var result = uploadService.apply(admin, bundang.getId(), june.getId(),
                new ByteArrayInputStream(sample()));
        em.flush();

        assertThat(result.matched()).hasSize(2);
        assertThat(result.unmatched()).hasSize(1);

        Long saved = em.createQuery("""
                SELECT COUNT(s) FROM StudentExamScore s WHERE s.deleted = false
                """, Long.class).getSingleResult();
        // 두 학생 × (국어·수학). 파일의 한국사는 이 회차 양식에 없어 빠진다
        assertThat(saved).isEqualTo(4);
    }

    @Test
    @DisplayName("회차 양식에 없는 과목은 넣지 않는다 — 파일에는 한국사까지 들어 있다")
    void skipsSubjectsOutsideForm() {
        uploadService.apply(admin, bundang.getId(), june.getId(),
                new ByteArrayInputStream(sample()));
        em.flush();

        List<String> subjects = em.createQuery("""
                SELECT DISTINCT s.examSubject.subjectName FROM StudentExamScore s
                WHERE s.deleted = false
                """, String.class).getResultList();
        assertThat(subjects).containsExactlyInAnyOrder("국어", "수학");
    }

    @Test
    @DisplayName("★★ 한 번 연결해두면 다음 회차부터 자동이다 — 아니면 같은 학생이 매번 빠진다")
    void linkedRowMatchesNextTime() {
        // 동명이인 둘 중 누구인지 사람이 정한다
        uploadService.link(admin, bundang.getId(), YEAR, "99700", "2", "2001",
                enrollmentId("2098-0004"));
        em.flush();

        var result = uploadService.apply(admin, bundang.getId(), june.getId(),
                new ByteArrayInputStream(sample()));

        assertThat(result.unmatched()).isEmpty();
        assertThat(result.matched()).extracting(MockExamUploadService.Matched::studentNo)
                .contains("2098-0004");
    }

    @Test
    @DisplayName("연결을 해제하면 다시 이름으로 찾는다 — 잘못 이었을 때 되돌릴 방법이 있어야 한다")
    void unlinkRestoresNameMatching() {
        var key = uploadService.link(admin, bundang.getId(), YEAR, "99700", "2", "2001",
                enrollmentId("2098-0004"));
        em.flush();

        uploadService.unlink(admin, key.getId());
        em.flush();

        var preview = uploadService.preview(admin, bundang.getId(), june.getId(),
                new ByteArrayInputStream(sample()));
        assertThat(preview.unmatched()).hasSize(1);
    }

    @Test
    @DisplayName("★ 같은 칸을 다시 연결하면 대상만 바뀐다 — 둘이면 어느 학생 성적인지 정해지지 않는다")
    void relinkDoesNotDuplicate() {
        Long fourth = enrollmentId("2098-0004");
        uploadService.link(admin, bundang.getId(), YEAR, "99700", "2", "2001",
                enrollmentId("2098-0003"));
        uploadService.link(admin, bundang.getId(), YEAR, "99700", "2", "2001", fourth);
        em.flush();

        var links = uploadService.links(admin, bundang.getId(), YEAR);
        assertThat(links).hasSize(1);
        assertThat(links.get(0).getEnrollment().getId()).isEqualTo(fourth);
    }

    @Test
    @DisplayName("미매칭 행에 학교코드가 실려 온다 — 없으면 화면이 연결을 걸 수 없다")
    void unmatchedCarriesSchoolCode() {
        var preview = uploadService.preview(admin, bundang.getId(), june.getId(),
                new ByteArrayInputStream(sample()));

        assertThat(preview.unmatched().get(0).schoolCode()).isEqualTo("99700");
    }

    private Long enrollmentId(String studentNo) {
        return em.createQuery(
                        "SELECT e.id FROM StudentEnrollment e WHERE e.studentNo = :no", Long.class)
                .setParameter("no", studentNo)
                .getSingleResult();
    }

    /** 실물과 같은 2단 헤더 구조의 축소본. */
    private byte[] sample() {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("성적");
            write(sheet.createRow(0), List.of("01. 학생별 성적"));
            write(sheet.createRow(1), List.of(
                    "학교코드", "학교명", "반", "번호", "이름",
                    "국어", "", "", "수학", "", "", "한국사", ""));
            write(sheet.createRow(2), List.of(
                    "학교", "학교명", "반", "번호", "이름",
                    "선택과목", "표준점수", "등급", "선택과목", "표준점수", "등급",
                    "원점수", "등급"));
            write(sheet.createRow(3), List.of(
                    "99700", "디랩 분당", "1", "1003", "전승은",
                    "언어와 매체", "125", "2", "미적분", "135", "1", "39", "2"));
            write(sheet.createRow(4), List.of(
                    "99700", "디랩 분당", "1", "1006", "김지성",
                    "화법과 작문", "118", "3", "확률과 통계", "120", "3", "35", "3"));
            write(sheet.createRow(5), List.of(
                    "99700", "디랩 분당", "2", "2001", "이중복",
                    "언어와 매체", "110", "4", "미적분", "112", "4", "30", "4"));
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void write(Row row, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            row.createCell(i).setCellValue(values.get(i));
        }
    }
}
