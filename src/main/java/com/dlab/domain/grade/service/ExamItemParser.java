package com.dlab.domain.grade.service;

import com.dlab.common.excel.ColumnMapping;
import com.dlab.common.excel.ExcelReader;
import com.dlab.common.excel.ExcelRow;
import com.dlab.common.excel.TwoRowHeaderReader;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 연구소 문항 파일 두 종 — 문항분석표 · 정답률.
 *
 * <h2>★ 정답률 파일은 범위 정보가 틀려 있다</h2>
 * 실물 파일(8월 더프)이 <b>507행인데 시트 범위(dimension)가 4행으로 적혀 있다.</b> 범위를 믿고
 * 읽는 방식이면 1행만 읽힌다. 여기서는 실제 행을 끝까지 읽는 리더를 쓴다
 * ({@link TwoRowHeaderReader} — POI 가 마지막 행 번호를 직접 센다).
 *
 * <h2>★ 정답률 파일은 과목명·학년이 과목의 첫 행에만 있다</h2>
 * 병합 셀이라 나머지 행은 비어 온다. <b>직전 값을 이어 채운다</b> — 안 그러면 과목 첫 문항만
 * 과목이 있고 나머지는 어느 과목인지 모른다.
 */
@Component
@RequiredArgsConstructor
public class ExamItemParser {

    private static final ColumnMapping ANALYSIS = ColumnMapping.builder()
            .required("subjectCode", "과목")
            .required("subjectName", "과목명")
            .required("questionNo", "문항번호")
            .optional("answer", "정답")
            .optional("points", "배점")
            .optional("elective", "선택과목")
            .optional("unitCode", "단원요소")
            .optional("skillCode", "평가요소")
            .optional("unitName", "내용영역")
            .optional("skillName", "행동영역");

    private final ExcelReader excelReader;
    private final TwoRowHeaderReader twoRowReader;

    /** 문항분석표 — 첫 시트(입력부), 헤더 한 줄. */
    public List<AnalysisRow> parseAnalysis(InputStream input) {
        List<AnalysisRow> rows = new ArrayList<>();
        for (ExcelRow row : excelReader.read(input, ANALYSIS).rows()) {
            String name = row.text("subjectName");
            Integer no = number(row.text("questionNo"));
            if (name == null || name.isBlank() || no == null) {
                continue;
            }
            String elective = row.text("elective");
            rows.add(new AnalysisRow(row.rowNumber(), trim(row.text("subjectCode")), name.trim(),
                    no.shortValue(), shortOf(row.text("answer")), shortOf(row.text("points")),
                    // 선택과목 칸이 0 이면 공통 문항이다
                    elective != null && !elective.isBlank() && !elective.trim().equals("0"),
                    trim(row.text("unitCode")), trim(row.text("unitName")),
                    trim(row.text("skillCode")), trim(row.text("skillName"))));
        }
        return rows;
    }

    /** 정답률 — 제목 1행 + 헤더 2행. */
    public List<RateRow> parseRates(InputStream input) {
        TwoRowHeaderReader.Result raw = twoRowReader.read(input, 1, 2);
        List<RateRow> rows = new ArrayList<>();
        String subject = "";
        for (Map<String, String> row : raw.rows()) {
            String name = get(row, "과목명");
            if (!name.isBlank()) {
                subject = name.trim();   // 병합 — 과목의 첫 행에만 있다
            }
            Integer no = number(get(row, "문항번호"));
            if (subject.isBlank() || no == null) {
                continue;
            }
            BigDecimal[] choices = new BigDecimal[5];
            for (int i = 0; i < 5; i++) {
                choices[i] = decimal(row.get(TwoRowHeaderReader.key("답지반응률", (i + 1) + "번")));
            }
            rows.add(new RateRow(subject, no.shortValue(),
                    decimal(get(row, "정답률")), choices,
                    decimal(row.get(TwoRowHeaderReader.key("전체", "변별도(전체)")))));
        }
        return rows;
    }

    /** 헤더 위아래가 같은 칸("학년" / 빈칸)은 키가 "학년 > " 처럼 생긴다 — 접두어로 찾는다. */
    private static String get(Map<String, String> row, String label) {
        String exact = row.get(label);
        if (exact != null) {
            return exact;
        }
        String prefix = label + " > ";
        return row.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("");
    }

    private static Integer number(String v) {
        try {
            return v == null || v.isBlank() ? null : new BigDecimal(v.trim()).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }

    private static Short shortOf(String v) {
        Integer n = number(v);
        return n == null ? null : n.shortValue();
    }

    private static BigDecimal decimal(String v) {
        try {
            return v == null || v.isBlank() ? null : new BigDecimal(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String trim(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    public record AnalysisRow(int rowNumber, String subjectCode, String subjectName,
                              short questionNo, Short answer, Short points, boolean elective,
                              String unitCode, String unitName, String skillCode,
                              String skillName) {
    }

    /** @param choiceRates 1~5번 응답률(%). 5지선다가 아닌 문항은 뒤가 빈다 */
    public record RateRow(String subjectName, short questionNo, BigDecimal nationalRate,
                          BigDecimal[] choiceRates, BigDecimal discrimination) {
    }
}
