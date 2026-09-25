package com.dlab.api.grade;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.grade.entity.ExamCode;
import com.dlab.domain.grade.entity.ExamItem;
import com.dlab.domain.grade.entity.ExamMaster;
import com.dlab.domain.grade.entity.StudentItemResponse;
import com.dlab.domain.grade.repository.StudentItemResponseRepository;
import com.dlab.domain.grade.service.ItemResponseService;
import com.dlab.domain.user.entity.*;
import jakarta.persistence.EntityManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
 * 학생 정오·답안 반영.
 *
 * <p>지키려는 것 — <b>국어·수학이 공통/선택으로 갈릴 것</b>(경계는 문항분석표가 안다),
 * <b>세 자리 답이 깨지지 않을 것</b>, <b>모르는 과목을 버리지 않고 알릴 것</b>.
 */
@SpringBootTest
@Transactional
class ItemResponseTest {

    @Autowired ItemResponseService service;
    @Autowired StudentItemResponseRepository responseRepository;
    @Autowired EntityManager em;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    ExamMaster august;
    StudentEnrollment student;
    AuthPrincipal admin;

    @BeforeEach
    void setUp() {
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);
        Student s = new Student("DL-R1", "정오학생", "010-1111-4444");
        em.persist(s);
        student = new StudentEnrollment(s, bundang, (short) 2087, "2087-0001", null, GradeType.N_SU);
        em.persist(student);
        august = ExamMaster.academyExam(null, (short) 2087, GradeType.N_SU, ExamCode.MONTHLY,
                "8월 더 프리미엄", LocalDate.of(2087, 8, 18), 1);
        august.addSubject("KOREAN", "국어", 1);
        em.persist(august);
        admin = new AuthPrincipal(1L, "admin", bundang.getId(), Set.of(Role.SUPER_ADMIN), true, false);
        em.flush();
    }

    private void items() {
        em.persist(new ExamItem(august, "01", "국어", (short) 1, (short) 2, (short) 2, false, null, null, null, null));
        em.persist(new ExamItem(august, "01", "국어", (short) 2, (short) 5, (short) 2, false, null, null, null, null));
        em.persist(new ExamItem(august, "01", "언어와매체", (short) 35, (short) 4, (short) 2, true, null, null, null, null));
        em.persist(new ExamItem(august, "03", "수학", (short) 22, (short) 125, (short) 4, false, null, null, null, null));
        em.persist(new ExamItem(august, "03", "미적분", (short) 23, (short) 3, (short) 3, true, null, null, null, null));
        em.flush();
    }

    @Test
    @DisplayName("★★ 국어 영역이 공통(국어)과 선택(언어와매체)으로 갈린다 — 경계는 문항분석표가 안다")
    void splitsCommonAndElective() {
        items();
        service.upload(admin, bundang.getId(), august.getId(),
                new ByteArrayInputStream(sheet("O", "X", "O", "O")), null);
        em.flush();

        var rows = responseRepository.findOf(student.getId(), august.getId());
        assertThat(rows).extracting(StudentItemResponse::getSubjectKey)
                .containsExactlyInAnyOrder("국어", "언어와매체", "수학");
        StudentItemResponse korean = rows.stream().filter(r -> r.getSubjectKey().equals("국어")).findFirst().orElseThrow();
        assertThat(korean.resultOf(1)).isEqualTo('O');
        assertThat(korean.resultOf(2)).isEqualTo('X');
        StudentItemResponse elective = rows.stream().filter(r -> r.getSubjectKey().equals("언어와매체")).findFirst().orElseThrow();
        assertThat(elective.getFirstNo()).isEqualTo((short) 35);
        assertThat(elective.resultOf(35)).isEqualTo('O');
    }

    @Test
    @DisplayName("★ 세 자리 답이 깨지지 않는다 — 수학 단답형 정답이 125 처럼 온다")
    void keepsMultiDigitAnswers() {
        items();
        service.upload(admin, bundang.getId(), august.getId(),
                new ByteArrayInputStream(sheet("O", "X", "O", "O")),
                new ByteArrayInputStream(sheet("2", "3", "4", "125")));
        em.flush();

        StudentItemResponse math = responseRepository.findOf(student.getId(), august.getId()).stream()
                .filter(r -> r.getSubjectKey().equals("수학")).findFirst().orElseThrow();
        assertThat(math.answerOf(22)).isEqualTo("125");
    }

    @Test
    @DisplayName("★ 문항 정보가 없으면 거절한다 — 공통·선택 경계를 코드에 박지 않는다")
    void requiresItemsFirst() {
        assertThatThrownBy(() -> service.upload(admin, bundang.getId(), august.getId(),
                new ByteArrayInputStream(sheet("O", "X", "O", "O")), null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("문항분석표");
    }

    @Test
    @DisplayName("★ 모르는 과목 약어는 알린다 — 조용히 버리면 그 과목 채점이 빈다")
    void reportsUnknownSubject() {
        items();
        var result = service.upload(admin, bundang.getId(), august.getId(),
                new ByteArrayInputStream(sheetWithElective("통사")), null);

        assertThat(result.unknownSubjects()).containsExactly("통사");
    }

    @Test
    @DisplayName("대응표엔 있는데 이 회차 문항 정보에 없는 과목도 알린다 — 공통 문항까지 통째로 빠진다")
    void reportsSubjectMissingFromItems() {
        items();
        var result = service.upload(admin, bundang.getId(), august.getId(),
                new ByteArrayInputStream(sheetWithElective("화작")), null);

        assertThat(result.unknownSubjects()).containsExactly("화작(문항 정보 없음)");
    }

    @Test
    @DisplayName("다시 올리면 교체된다 — 두 벌이 쌓이지 않는다")
    void reuploadReplaces() {
        items();
        service.upload(admin, bundang.getId(), august.getId(),
                new ByteArrayInputStream(sheet("O", "X", "O", "O")), null);
        em.flush();
        service.upload(admin, bundang.getId(), august.getId(),
                new ByteArrayInputStream(sheet("X", "X", "X", "X")), null);
        em.flush();

        var rows = responseRepository.findOf(student.getId(), august.getId());
        assertThat(rows).hasSize(3);
        assertThat(rows.stream().filter(r -> r.getSubjectKey().equals("국어")).findFirst()
                .orElseThrow().resultOf(1)).isEqualTo('X');
    }

    /** 국어(언매) 1·2·35번 + 수학(미적) 22번 */
    private byte[] sheet(String k1, String k2, String k35, String m22) {
        return build("언매", List.of(k1, k2, k35), m22);
    }

    private byte[] sheetWithElective(String elective) {
        return build(elective, List.of("O", "O", "O"), "O");
    }

    private byte[] build(String koreanElective, List<String> korean, String math22) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("정오표");
            write(sh.createRow(0), List.of("03. 학생별 정오표"));
            write(sh.createRow(1), List.of("학교", "학교명", "반", "번호", "이름",
                    "1교시 국어 영역", "", "", "", "2교시 수학 영역", ""));
            write(sh.createRow(2), List.of("학교", "학교명", "반", "번호", "이름",
                    "선택과목", "1", "2", "35", "선택과목", "22"));
            write(sh.createRow(3), List.of("99700", "디랩 분당", "1", "1002", "정오학생",
                    koreanElective, korean.get(0), korean.get(1), korean.get(2), "미적", math22));
            wb.write(out);
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
