package com.dlab.domain.university;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.domain.university.repository.UniversityDepartmentRepository;
import com.dlab.domain.university.repository.UniversityRepository;
import com.dlab.domain.university.service.UniversityImportService;
import jakarta.persistence.EntityManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대학·학과 마스터 적재 (디랩 1021 피드백 6-1).
 *
 * <h2>★ 이 테스트가 지키는 것</h2>
 * 원본 파일(4,921행)의 성질을 실제로 파싱해 확인했고, 그중 <b>설계를 가르는 둘</b>을
 * 여기서 못박는다.
 * <ul>
 *   <li><b>학과코드는 유일하지 않다</b> — 503개가 둘 이상의 대학에서 쓰인다.
 *       대학코드를 키에서 빼면 뒤에 온 대학이 앞 대학 학과를 덮어쓴다</li>
 *   <li><b>같은 학과코드에 다른 이름이 있다</b>(표기 차이 3건). 전달 문서는 *"충돌 0건"*
 *       이라고 했으나 실제로는 4건이다 — 코드→이름 사전을 만들면 깨진다</li>
 * </ul>
 */
@SpringBootTest
@Transactional
class UniversityImportTest {

    @Autowired UniversityImportService importService;
    @Autowired UniversityRepository universityRepository;
    @Autowired UniversityDepartmentRepository departmentRepository;
    @Autowired EntityManager em;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    static final short YEAR = 2091;

    private InputStream excel(List<String[]> rows) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet();
            Row header = sheet.createRow(0);
            String[] headers = {"대학코드", "대학명", "계열코드", "계열명", "학과코드", "학과명"};
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < rows.get(r).length; c++) {
                    row.createCell(c).setCellValue(rows.get(r)[c]);
                }
            }
            wb.write(out);
            return new ByteArrayInputStream(out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("★★ 같은 학과코드가 여러 대학에 있어도 서로 덮어쓰지 않는다 — 503개가 그렇다")
    void sameDepartmentCodeAcrossUniversities() {
        var result = importService.importMaster(YEAR, excel(List.<String[]>of(
                new String[]{"001", "서울대", "1", "인문", "0694", "인문계열"},
                new String[]{"002", "연세대", "1", "인문", "0694", "인문학부"})));
        em.flush();

        assertThat(result.universities().inserted()).isEqualTo(2);
        // ★ 학과코드만으로 키를 잡으면 여기가 1이 된다 — 연세대 학과가 사라진다
        assertThat(result.departments().inserted()).isEqualTo(2);

        assertThat(departmentRepository
                .findByYearAndUniversityCodeAndCodeAndDeletedFalse(YEAR, "001", "0694")
                .orElseThrow().getName()).isEqualTo("인문계열");
        assertThat(departmentRepository
                .findByYearAndUniversityCodeAndCodeAndDeletedFalse(YEAR, "002", "0694")
                .orElseThrow().getName()).isEqualTo("인문학부");
    }

    @Test
    @DisplayName("★★ 같은 학과코드에 다른 학과명이 와도 둘 다 보존된다 — 실측 3건이 그렇다")
    void sameCodeDifferentNameIsKept() {
        // 원본의 실제 사례: 6274 「문예창작」 / 「문예창작과」
        importService.importMaster(YEAR, excel(List.<String[]>of(
                new String[]{"039", "명지대(인문-서울)", "3", "예체능", "6274", "문예창작"},
                new String[]{"083", "추계예술대", "3", "예체능", "6274", "문예창작과"})));
        em.flush();

        assertThat(departmentRepository
                .findByYearAndUniversityCodeAndCodeAndDeletedFalse(YEAR, "039", "6274")
                .orElseThrow().getName()).isEqualTo("문예창작");
        assertThat(departmentRepository
                .findByYearAndUniversityCodeAndCodeAndDeletedFalse(YEAR, "083", "6274")
                .orElseThrow().getName()).isEqualTo("문예창작과");
    }

    @Test
    @DisplayName("★ 다시 올려도 행이 늘지 않는다 — 검색에 같은 대학이 두 번 나오면 안 된다")
    void reuploadDoesNotDuplicate() {
        List<String[]> rows = List.<String[]>of(
                new String[]{"001", "서울대", "1", "인문", "0694", "인문계열"});

        importService.importMaster(YEAR, excel(rows));
        em.flush();
        var second = importService.importMaster(YEAR, excel(rows));
        em.flush();

        assertThat(second.universities().inserted()).isZero();
        assertThat(second.departments().inserted()).isZero();
        assertThat(universityRepository.findByYearAndDeletedFalse(YEAR)).hasSize(1);
        assertThat(departmentRepository.findByYearAndDeletedFalse(YEAR)).hasSize(1);
    }

    @Test
    @DisplayName("★ 이름이 바뀌면 갱신한다 — 캠퍼스 표기가 해마다 달라질 수 있다")
    void renameIsApplied() {
        importService.importMaster(YEAR, excel(List.<String[]>of(
                new String[]{"045", "가천대", "2", "자연", "1234", "컴퓨터공학"})));
        em.flush();

        var second = importService.importMaster(YEAR, excel(List.<String[]>of(
                new String[]{"045", "가천대(글로벌-성남)", "2", "자연", "1234", "컴퓨터공학과"})));
        em.flush();
        em.clear();

        assertThat(second.universities().updated()).isEqualTo(1);
        assertThat(second.departments().updated()).isEqualTo(1);
        assertThat(universityRepository.findByYearAndCodeAndDeletedFalse(YEAR, "045")
                .orElseThrow().getName()).isEqualTo("가천대(글로벌-성남)");
    }

    @Test
    @DisplayName("★★ 코드의 앞 0이 살아 있다 — 숫자로 읽으면 '001' 이 '1' 이 되어 아무것도 안 맞는다")
    void zeroPaddedCodeSurvives() {
        importService.importMaster(YEAR, excel(List.<String[]>of(
                new String[]{"001", "서울대", "1", "인문", "0694", "인문계열"})));
        em.flush();

        assertThat(universityRepository.findByYearAndCodeAndDeletedFalse(YEAR, "001")).isPresent();
        assertThat(universityRepository.findByYearAndCodeAndDeletedFalse(YEAR, "1")).isEmpty();
        assertThat(departmentRepository
                .findByYearAndUniversityCodeAndCodeAndDeletedFalse(YEAR, "001", "0694")).isPresent();
    }

    @Test
    @DisplayName("★ 계열이 학과에 함께 저장된다 — 화면이 자동으로 채우려면 여기 있어야 한다")
    void trackIsStoredWithDepartment() {
        importService.importMaster(YEAR, excel(List.<String[]>of(
                new String[]{"079", "중앙대(다빈치-안성)", "3", "예체능", "7627", "디자인학부"})));
        em.flush();

        var dept = departmentRepository
                .findByYearAndUniversityCodeAndCodeAndDeletedFalse(YEAR, "079", "7627")
                .orElseThrow();
        assertThat(dept.getTrackCode()).isEqualTo("3");
        assertThat(dept.getTrackName()).isEqualTo("예체능");
    }

    @Test
    @DisplayName("★★ 실제 파일 4,921행이 그대로 들어간다 — 파일이 없으면 건너뛴다")
    @org.junit.jupiter.api.condition.EnabledIf("masterFileExists")
    void realFileLoads() throws Exception {
        // ⚠️ 원본은 md/(gitignore) 에 있어 CI 에는 없다. 없으면 조용히 빠진다 —
        //    CI 를 로컬 파일 유무에 묶지 않기 위해서다.
        try (InputStream in = java.nio.file.Files.newInputStream(MASTER_FILE)) {
            var result = importService.importMaster(YEAR, in);
            em.flush();

            assertThat(result.rowCount()).isEqualTo(4_921);
            assertThat(result.errors()).isEmpty();
            // 실측값이다 — 전달 문서는 학과코드를 2,510 으로 적었으나 2,487 이다
            assertThat(result.universities().inserted()).isEqualTo(223);
            assertThat(result.departments().inserted()).isEqualTo(4_921);

            // ★ 계열코드가 1·2·3·7 이다(4~6 없음) — 연속이라고 가정하지 말 것
            assertThat(departmentRepository.findByYearAndDeletedFalse(YEAR).stream()
                    .map(d -> d.getTrackCode()).distinct().sorted().toList())
                    .containsExactly("1", "2", "3", "7");
        }
    }

    static final java.nio.file.Path MASTER_FILE =
            java.nio.file.Path.of("md/전달/2026더프모지원대학학과코드_20260223.xlsx");

    static boolean masterFileExists() {
        return java.nio.file.Files.exists(MASTER_FILE);
    }
}
