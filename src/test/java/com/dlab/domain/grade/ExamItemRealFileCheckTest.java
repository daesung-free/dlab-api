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
