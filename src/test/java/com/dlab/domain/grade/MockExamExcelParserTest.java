package com.dlab.domain.grade;

import com.dlab.common.excel.TwoRowHeaderReader;
import com.dlab.domain.grade.service.MockExamExcelParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 모의고사 성적 엑셀 파서.
 *
 * <p>실물 파일(605명)로 확인한 구조를 그대로 줄여 만든다 — 실물은 커밋할 수 없다
 * (개인정보이고 {@code md/} 는 gitignore 대상이다).
 *
 * <p>지키려는 것 — <b>2단 키로 읽을 것</b>, <b>영역마다 다른 라벨을 흡수할 것</b>,
 * <b>빈 값을 오류로 보지 않을 것</b>.
 */
class MockExamExcelParserTest {

    private final MockExamExcelParser parser = new MockExamExcelParser(new TwoRowHeaderReader());

    @Test
    @DisplayName("★ 지망대학 1·2지망을 읽는다 — 헤더에 엑셀 줄바꿈(_x000D_)이 글자로 박혀 있다")
    void readsUniversityChoices() {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("성적");
            write(sheet.createRow(0), List.of("01. 학생별 성적"));
            write(sheet.createRow(1), List.of("학교코드", "학교명", "반", "번호", "이름",
                    "지망대학 (1지망 선택)", "", "", "", "", "", "", "", "",
                    "지망대학 (2지망 선택)", ""));
            write(sheet.createRow(2), List.of("학교", "학교명", "반", "번호", "이름",
                    "대학명", "학과(부)명", "모집정원", "지원자수", "지원자 중 석차_x000D_",
                    "적용된 본인의_x000D_\n 수능영역", "본인수능\n예상점수", "기준점수", "가능성진단",
                    "대학명", "학과(부)명"));
            write(sheet.createRow(3), List.of("99700", "디랩 분당", "1", "1002", "홍길동",
                    "가나대", "국어국문", "5", "22", "11", "국수영사", "479", "511", "위험",
                    "다라대", "철학"));
            workbook.write(out);

            var row = parser.parse(new ByteArrayInputStream(out.toByteArray())).students().get(0);

            assertThat(row.choices()).hasSize(2);
            var first = row.choices().get(0);
            assertThat(first.rank()).isEqualTo(1);
            assertThat(first.applicantRank()).isEqualTo("11");
            assertThat(first.expectedScore()).isEqualTo("479");
            assertThat(first.cutoffScore()).isEqualTo("511");
            assertThat(first.diagnosis()).isEqualTo("위험");
            assertThat(row.choices().get(1).universityName()).isEqualTo("다라대");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("지망대학이 비어 있으면 담지 않는다 — 평가원 회차는 원래 전원 비어 있다")
    void skipsEmptyUniversityChoices() {
        var row = parser.parse(new ByteArrayInputStream(sample())).students().get(0);

        assertThat(row.choices()).isEmpty();
    }

    @Test
    @DisplayName("★ 같은 항목명이 영역마다 반복돼도 어느 과목인지 구분한다")
    void readsByTwoRowKey() {
        var result = parser.parse(new ByteArrayInputStream(sample()));

        assertThat(result.students()).hasSize(2);
        var first = result.students().get(0);
        assertThat(first.schoolCode()).isEqualTo("99700");
        assertThat(first.name()).isEqualTo("전승은");
        assertThat(first.classNo()).isEqualTo("1");
        assertThat(first.studentNo()).isEqualTo("1003");

        // 국어와 수학의 "표준점수" 는 헤더 아래 줄이 똑같다 — 위 줄이 없으면 섞인다
        assertThat(first.subjects().get("국어").standardScore()).isEqualTo("125");
        assertThat(first.subjects().get("수학").standardScore()).isEqualTo("135");
    }

    @Test
    @DisplayName("★ 영역마다 라벨이 다르다 — 국어는 「선택과목」, 탐구는 「과목명」")
    void absorbsLabelVariants() {
        var first = parser.parse(new ByteArrayInputStream(sample())).students().get(0);

        assertThat(first.subjects().get("국어").electiveSubject()).isEqualTo("언어와 매체");
        assertThat(first.subjects().get("탐구영역-선택1과목").electiveSubject()).isEqualTo("생명1");
    }

    @Test
    @DisplayName("절대평가 영역은 등급만 온다 — 표준점수가 비어도 정상이다")
    void absoluteGradeSubjectsHaveGradeOnly() {
        var first = parser.parse(new ByteArrayInputStream(sample())).students().get(0);

        var english = first.subjects().get("영어");
        assertThat(english.gradeLevel()).isEqualTo("2");
        assertThat(english.standardScore()).isEmpty();
    }

    @Test
    @DisplayName("응시하지 않은 영역은 담지 않는다 — 두 번째 학생은 탐구를 안 봤다")
    void skipsUntakenSubjects() {
        var second = parser.parse(new ByteArrayInputStream(sample())).students().get(1);

        assertThat(second.subjects()).containsKey("국어");
        assertThat(second.subjects()).doesNotContainKey("탐구영역-선택1과목");
    }

    @Test
    @DisplayName("행 번호는 엑셀 기준이다 — 오류를 알렸을 때 그 행을 찾을 수 있어야 한다")
    void keepsExcelRowNumber() {
        var students = parser.parse(new ByteArrayInputStream(sample())).students();

        assertThat(students.get(0).rowNumber()).isEqualTo(4);
        assertThat(students.get(1).rowNumber()).isEqualTo(5);
    }

    /**
     * 실물과 같은 모양의 축소본.
     *
     * <pre>
     * 0행  01. 학생별 성적          (제목)
     * 1행  학교코드 학교명 반 번호 이름 | 국어 …        (영역, 병합이라 첫 칸에만)
     * 2행  학교   학교명 반 번호 이름 | 선택과목 표준점수 …  (항목)
     * 3행~ 데이터
     * </pre>
     */
    private byte[] sample() {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("성적");

            write(sheet.createRow(0), List.of("01. 학생별 성적"));
            // 영역 줄 — 병합이라 과목의 첫 칸에만 이름이 있고 나머지는 빈칸이다
            write(sheet.createRow(1), List.of(
                    "학교코드", "학교명", "반", "번호", "이름",
                    "국어", "", "",
                    "수학", "", "",
                    "영어", "",
                    "탐구영역-선택1과목", "", ""));
            write(sheet.createRow(2), List.of(
                    "학교", "학교명", "반", "번호", "이름",
                    "선택과목", "표준점수", "등급",
                    "선택과목", "표준점수", "등급",
                    "원점수", "등급",
                    "과목명", "표준점수", "등급"));
            write(sheet.createRow(3), List.of(
                    "99700", "디랩 분당", "1", "1003", "전승은",
                    "언어와 매체", "125", "2",
                    "미적분", "135", "1",
                    "85", "2",
                    "생명1", "65", "2"));
            // 탐구 미응시
            write(sheet.createRow(4), List.of(
                    "99700", "디랩 분당", "1", "1006", "김지성",
                    "화법과 작문", "118", "3",
                    "확률과 통계", "120", "3",
                    "80", "3",
                    "", "", ""));

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
