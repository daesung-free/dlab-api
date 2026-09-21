package com.dlab.api.grade;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.grade.entity.ExamCode;
import com.dlab.domain.grade.entity.ExamItem;
import com.dlab.domain.grade.entity.ExamMaster;
import com.dlab.domain.grade.repository.ExamItemRepository;
import com.dlab.domain.grade.service.ExamItemService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
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
 * 회차 문항 정보 — 문항분석표 + 정답률.
 *
 * <p>지키려는 것 — <b>표기가 다른 두 파일이 이어질 것</b>(물리학I / 물리학Ⅰ),
 * <b>이어지지 않은 행을 버리지 않고 알릴 것</b>, <b>다시 올리면 교체될 것</b>.
 */
@SpringBootTest
@Transactional
class ExamItemTest {

    @Autowired ExamItemService service;
    @Autowired ExamItemRepository itemRepository;
    @Autowired EntityManager em;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    ExamMaster august;
    AuthPrincipal admin;

    @BeforeEach
    void setUp() {
        Academy bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);
        august = ExamMaster.academyExam(null, (short) 2088, GradeType.N_SU, ExamCode.MONTHLY,
                "8월 더 프리미엄", LocalDate.of(2088, 8, 18), 1);
        august.addSubject("KOREAN", "국어", 1);
        em.persist(august);
        em.flush();
        admin = new AuthPrincipal(1L, "admin", bundang.getId(), Set.of(Role.SUPER_ADMIN), true, false);
    }

    @Test
    @DisplayName("★★ 로마숫자 표기가 달라도 이어진다 — 문항분석표 물리학I(U+0049) / 정답률 물리학Ⅰ(U+2160)")
    void joinsAcrossRomanNumeralVariants() {
        var result = service.upload(admin, august.getId(),
                new ByteArrayInputStream(analysis()), new ByteArrayInputStream(rates()));
        em.flush();

        assertThat(result.itemCount()).isEqualTo(3);
        assertThat(result.ratesApplied()).isEqualTo(3);
        assertThat(result.unmatchedRates()).isEmpty();

        ExamItem physics = itemRepository.findByExamMasterId(august.getId()).stream()
                .filter(i -> i.getSubjectName().equals("물리학I")).findFirst().orElseThrow();
        assertThat(physics.getNationalRate()).isEqualByComparingTo("61.3");
        assertThat(physics.getChoice2Rate()).isEqualByComparingTo("20.1");
    }

    @Test
    @DisplayName("★ 선택과목 문항을 구분한다 — 국어 35번부터는 화작·언매로 갈린다")
    void marksElectiveItems() {
        service.upload(admin, august.getId(), new ByteArrayInputStream(analysis()), null);
        em.flush();

        var items = itemRepository.findByExamMasterId(august.getId());
        assertThat(items).filteredOn(ExamItem::isElective)
                .extracting(ExamItem::getSubjectName).containsExactly("언어와매체");
    }

    @Test
    @DisplayName("★ 이어지지 않은 정답률은 알린다 — 조용히 버리면 그 과목 정답률이 비는데 아무도 모른다")
    void reportsUnmatchedRates() {
        var result = service.upload(admin, august.getId(),
                new ByteArrayInputStream(analysis()), new ByteArrayInputStream(ratesWithUnknown()));

        assertThat(result.unmatchedRates()).containsExactly("통합과학 1번");
    }

    @Test
    @DisplayName("다시 올리면 통째로 교체된다 — 두 벌이 쌓이지 않는다")
    void reuploadReplaces() {
        service.upload(admin, august.getId(), new ByteArrayInputStream(analysis()), null);
        em.flush();
        service.upload(admin, august.getId(), new ByteArrayInputStream(analysis()), null);
        em.flush();

        assertThat(itemRepository.findByExamMasterId(august.getId())).hasSize(3);
    }

    @Test
    @DisplayName("입학 전 성적 양식에는 올릴 수 없다")
    void rejectsAdmissionForm() {
        ExamMaster admission = ExamMaster.common((short) 2088, GradeType.N_SU, ExamCode.JUNE, "작년 6평", 1);
        admission.addSubject("KOREAN", "국어", 1);
        em.persist(admission);
        em.flush();

        assertThatThrownBy(() -> service.upload(admin, admission.getId(),
                new ByteArrayInputStream(analysis()), null))
                .isInstanceOf(BusinessException.class);
    }

    private byte[] analysis() {
        return workbook(sheet -> {
            write(sheet.createRow(0), List.of("학년", "시행", "과목", "과목명", "문항번호", "유형", "정답",
                    "배점", "선택과목", "단원요소", "평가요소", "내용영역", "행동영역"));
            write(sheet.createRow(1), List.of("3", "20880818", "01", "국어", "1", "1", "2", "2", "0",
                    "6", "A", "독서 이론", "사실적 이해"));
            write(sheet.createRow(2), List.of("3", "20880818", "01", "언어와매체", "35", "1", "4", "2", "1",
                    "3", "B", "매체", "추론적 이해"));
            write(sheet.createRow(3), List.of("3", "20880818", "07", "물리학I", "1", "1", "3", "2", "0",
                    "1", "A", "역학", "개념 이해"));
        });
    }

    private byte[] rates() {
        return ratesOf(List.of(
                List.of("3", "20880818", "국어", "1", "2", "2", "0.8", "94.4", "3.4", "0.7", "0.6", "94.4", "0.35"),
                List.of("", "", "언어와 매체", "35", "4", "2", "10", "10", "10", "60", "10", "60", "0.4"),
                List.of("", "", "물리학Ⅰ", "1", "3", "2", "5", "20.1", "61.3", "8", "5.6", "61.3", "0.5")));
    }

    private byte[] ratesWithUnknown() {
        return ratesOf(List.of(
                List.of("3", "20880818", "통합과학", "1", "2", "2", "1", "2", "3", "4", "5", "50", "0.1")));
    }

    private byte[] ratesOf(List<List<String>> data) {
        return workbook(sheet -> {
            write(sheet.createRow(0), List.of(" 과목별 문항 정답률 및 변별도"));
            write(sheet.createRow(1), List.of("학년", "시행일", "과목명", "문항번호", "정답", "배점",
                    "답지반응률", "", "", "", "", "정답률", "전체"));
            write(sheet.createRow(2), List.of("", "", "", "", "", "",
                    "1번", "2번", "3번", "4번", "5번", "", "변별도(전체)"));
            int r = 3;
            for (List<String> row : data) {
                write(sheet.createRow(r++), row);
            }
        });
    }

    private byte[] workbook(java.util.function.Consumer<Sheet> fill) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            fill.accept(wb.createSheet("입력부"));
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
