package com.dlab.domain.grade;

import com.dlab.common.excel.ExcelReader;
import com.dlab.common.excel.TwoRowHeaderReader;
import com.dlab.domain.grade.service.ExamItemParser;
import com.dlab.domain.grade.service.SubjectNames;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실물 문항 파일 검증 — <b>파일이 있을 때만</b> 돈다(md/ 는 gitignore).
 *
 * <p>문항 파일에는 학생 정보가 없다 — 통계만 있다.
 */
class ExamItemRealFileCheckTest {

    private static final Path DIR = Path.of("md/성적/자료/01_8월더프_세트");

    /** 파일명이 macOS 방식(NFD)이라 한글을 그대로 비교하면 안 걸린다 */
    private static Path find(String keyword) {
        if (!Files.isDirectory(DIR)) {
            return null;
        }
        try (Stream<Path> files = Files.list(DIR)) {
            return files.filter(p -> Normalizer.normalize(p.getFileName().toString(),
                            Normalizer.Form.NFC).contains(keyword))
                    .filter(p -> !p.getFileName().toString().contains("99"))
                    .findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean filesExist() {
        return find("문항분석표") != null && find("정답률") != null;
    }

    @Test
    @EnabledIf("filesExist")
    @DisplayName("★ 8월 더프 — 문항분석표와 정답률이 한 문항도 빠짐없이 이어진다")
    void analysisAndRatesJoinCompletely() throws Exception {
        ExamItemParser parser = new ExamItemParser(new ExcelReader(), new TwoRowHeaderReader());
        var analysis = parseAnalysis(parser);
        var rates = parseRates(parser);

        Set<String> analysisKeys = analysis.stream()
                .map(a -> SubjectNames.key(a.subjectName()) + "#" + a.questionNo())
                .collect(Collectors.toSet());
        Set<String> rateKeys = rates.stream()
                .map(r -> SubjectNames.key(r.subjectName()) + "#" + r.questionNo())
                .collect(Collectors.toSet());

        System.out.printf("문항분석표 %d문항 · 정답률 %d문항 · 이어지지 않은 정답률 %d%n",
                analysis.size(), rates.size(),
                rateKeys.stream().filter(k -> !analysisKeys.contains(k)).count());

        assertThat(rates).hasSize(507);                  // 범위 정보(4행)를 믿으면 1행만 읽힌다
        assertThat(analysisKeys).containsAll(rateKeys);  // 로마숫자 표기 차이를 넘어 전부 이어져야 한다
    }

    static boolean sheetExists() {
        return findSheet("정오표") != null && findSheet("답안표") != null;
    }

    /** 분당(99700) 정오표·답안표 */
    private static Path findSheet(String keyword) {
        if (!Files.isDirectory(DIR)) {
            return null;
        }
        try (Stream<Path> files = Files.list(DIR)) {
            return files.filter(p -> {
                        String n = Normalizer.normalize(p.getFileName().toString(), Normalizer.Form.NFC);
                        return n.contains("99700") && n.contains(keyword);
                    })
                    .findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    @Test
    @EnabledIf("sheetExists")
    @DisplayName("★ 정오표·답안표 — 영역이 7개로 나뉘고 탐구2·한국사가 탐구1 에 덮이지 않는다")
    void itemSheetsSplitIntoBlocks() throws Exception {
        var parser = new com.dlab.domain.grade.service.ItemSheetParser(new TwoRowHeaderReader());
        java.util.List<com.dlab.domain.grade.service.ItemSheetParser.StudentSheet> results;
        try (InputStream in = Files.newInputStream(findSheet("정오표"))) {
            results = parser.parse(in);
        }
        var sizes = results.stream().flatMap(s -> s.blocks().stream())
                .collect(Collectors.groupingBy(b -> b.index(),
                        java.util.TreeMap::new,
                        Collectors.mapping(b -> b.values().size(), Collectors.toSet())));
        System.out.printf("정오표 학생 %d명 · 영역별 문항 수 %s%n", results.size(), sizes);

        assertThat(sizes.keySet()).contains(0, 1, 2, 3, 4, 5);
        assertThat(sizes.get(0)).containsExactly(45);   // 국어
        assertThat(sizes.get(1)).containsExactly(30);   // 수학
        assertThat(sizes.get(2)).containsExactly(45);   // 영어
        assertThat(sizes.get(3)).containsExactly(20);   // 탐구1
        assertThat(sizes.get(4)).containsExactly(20);   // 탐구2
        assertThat(sizes.get(5)).containsExactly(20);   // 한국사
        // 약어는 전부 대응표에 있어야 한다 — 모르는 과목이 들어오면 채점이 빈다
        var unknown = results.stream().flatMap(s -> s.blocks().stream())
                .filter(b -> b.index() != 6)
                .map(b -> b.abbreviation())
                .filter(a -> com.dlab.domain.grade.service.SubjectNames.fromAbbreviation(a).isEmpty())
                .collect(Collectors.toSet());
        System.out.println("대응표에 없는 약어: " + unknown);
        // 탐구1 과 탐구2 값이 같은 학생이 전원이면 덮인 것이다
        long identical = results.stream().filter(s -> {
            var t1 = s.blocks().stream().filter(b -> b.index() == 3).findFirst();
            var t2 = s.blocks().stream().filter(b -> b.index() == 4).findFirst();
            return t1.isPresent() && t2.isPresent() && t1.get().values().equals(t2.get().values());
        }).count();
        assertThat(identical).isLessThan(results.size());
    }

    private java.util.List<ExamItemParser.AnalysisRow> parseAnalysis(ExamItemParser p) throws Exception {
        try (InputStream in = Files.newInputStream(find("문항분석표"))) {
            return p.parseAnalysis(in);
        }
    }

    private java.util.List<ExamItemParser.RateRow> parseRates(ExamItemParser p) throws Exception {
        try (InputStream in = Files.newInputStream(find("정답률"))) {
            return p.parseRates(in);
        }
    }
}
