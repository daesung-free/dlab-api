package com.dlab.domain.grade;

import com.dlab.common.excel.TwoRowHeaderReader;
import com.dlab.domain.grade.service.MockExamExcelParser;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실물 파일 검증 — <b>파일이 있을 때만</b> 돈다.
 *
 * <p>연구소 파일은 실제 학생 이름·점수·지망대학이 들어 있어 커밋할 수 없다({@code md/} 는
 * gitignore). CI 에는 없으므로 건너뛴다. 로컬에서 파서가 실물 구조를 제대로 읽는지만 본다.
 *
 * <p>★ <b>이름이나 점수 값을 단정하지 않는다.</b> 건수와 형식만 본다 — 개인정보를 테스트
 * 코드에 옮기지 않는다.
 */
class MockExamRealFileCheckTest {

    private static final Path DUP_FILE = Path.of(
            "md/성적/자료/01_8월더프_세트/01_고3_99700(디랩 분당)_학생별 성적_20260818.xlsx");

    static boolean fileExists() {
        return Files.exists(DUP_FILE);
    }

    @Test
    @EnabledIf("fileExists")
    @DisplayName("8월 더프 실물 — 지망대학 진단이 연구소 표기 5단계로 읽힌다")
    void readsUniversityDiagnosisFromRealFile() throws Exception {
        var parser = new MockExamExcelParser(new TwoRowHeaderReader());
        try (InputStream in = Files.newInputStream(DUP_FILE)) {
            var students = parser.parse(in).students();

            var choices = students.stream().flatMap(s -> s.choices().stream()).toList();
            System.out.printf("학생 %d명 · 지망대학 %d건 (지망 적은 학생 %d명)%n",
                    students.size(), choices.size(),
                    students.stream().filter(s -> !s.choices().isEmpty()).count());

            assertThat(choices).isNotEmpty();
            assertThat(choices).allSatisfy(c -> {
                assertThat(c.diagnosis()).isIn(Set.of("위험", "불안", "소신", "가능", "안정", ""));
                assertThat(c.cutoffScore()).matches("^$|^\\d+(\\.\\d+)?$");
                assertThat(c.applicantRank()).matches("^$|^\\d+$");
            });
        }
    }
}
