package com.dlab.domain.vocab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.domain.vocab.entity.VocabMeaning;
import com.dlab.domain.vocab.entity.VocabPhrase;
import com.dlab.domain.vocab.repository.VocabMeaningRepository;
import com.dlab.domain.vocab.repository.VocabPhraseRepository;
import com.dlab.domain.vocab.repository.VocabWordRepository;
import com.dlab.domain.vocab.service.VocabImportService;
import jakarta.persistence.EntityManager;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * D:VOCA 원고 적재 (개발문서 v2 4장).
 *
 * <h2>★ 실제 원고로 검증한다</h2>
 * 파서의 함정이 전부 <b>실제 파일의 성질</b>에서 나온다 — 시트명 두 형식, 열 개수 차이,
 * 0 패딩 번호, 줄바꿈. 손으로 만든 엑셀로는 그 어느 것도 재현되지 않는다.
 *
 * <p>⚠️ 원고는 {@code md/}(gitignore) 에 있어 CI 에는 없다. 없으면 조용히 빠진다 —
 * {@code KcpLiveSmokeTest} 와 같은 방식이다.
 */
@SpringBootTest
@Transactional
class VocabImportTest {

    static final Path MANUSCRIPT_DIR = Path.of("md/추가개발/D VOCA 엑셀 원고");

    static boolean manuscriptExists() {
        return Files.isDirectory(MANUSCRIPT_DIR);
    }

    @Autowired VocabImportService importService;
    @Autowired VocabWordRepository wordRepository;
    @Autowired VocabMeaningRepository meaningRepository;
    @Autowired VocabPhraseRepository phraseRepository;
    @Autowired EntityManager em;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    private Path file(int unit) {
        return MANUSCRIPT_DIR.resolve("DVOCA_Unit%02d.xlsx".formatted(unit));
    }

    @Test
    @DisplayName("★★ 유닛 번호를 파일명에서 뽑는다 — 시트명을 쓰면 30개 파일이 실패한다")
    @EnabledIf("manuscriptExists")
    void unitComesFromFileName() throws Exception {
        // Unit 07 은 시트명이 "Part 1" 뿐이다(유닛 번호 없음) — 실측 확인
        try (InputStream in = Files.newInputStream(file(7))) {
            var result = importService.importFile("DVOCA_Unit07.xlsx", in);
            assertThat(result.unitNo()).isEqualTo((short) 7);
            assertThat(result.wordInserted()).isEqualTo(40);
        }
        em.flush();

        assertThat(wordRepository.findByUnitNoAndDeletedFalseOrderBySeqNoAsc((short) 7))
                .hasSize(40);
    }

    @Test
    @DisplayName("★ 파일명에 유닛이 없으면 거절한다 — 추정하면 엉뚱한 유닛에 40단어가 들어간다")
    @EnabledIf("manuscriptExists")
    void rejectsFileWithoutUnitInName() throws Exception {
        try (InputStream in = Files.newInputStream(file(1))) {
            assertThatThrownBy(() -> importService.importFile("원고.xlsx", in))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("유닛 번호");
        }
    }

    @Test
    @DisplayName("★★ 번호의 앞 0이 살아 있다 — 숫자로 읽으면 '0001' 이 '1' 이 된다")
    @EnabledIf("manuscriptExists")
    void zeroPaddedSeqSurvives() throws Exception {
        try (InputStream in = Files.newInputStream(file(1))) {
            importService.importFile("DVOCA_Unit01.xlsx", in);
        }
        em.flush();

        assertThat(wordRepository.findBySeqNoAndDeletedFalse("0001")).isPresent();
        assertThat(wordRepository.findBySeqNoAndDeletedFalse("1")).isEmpty();
    }

    @Test
    @DisplayName("★★ 뜻이 후보로 쪼개진다 — financial 에 「금융의」라고 써도 맞아야 한다")
    @EnabledIf("manuscriptExists")
    void meaningsAreSplit() throws Exception {
        try (InputStream in = Files.newInputStream(file(1))) {
            importService.importFile("DVOCA_Unit01.xlsx", in);
        }
        em.flush();

        var financial = wordRepository.findBySeqNoAndDeletedFalse("0001").orElseThrow();
        assertThat(financial.getWord()).isEqualTo("financial");
        assertThat(meaningRepository.findByWordIdAndDeletedFalse(financial.getId()))
                .extracting(VocabMeaning::getText)
                .contains("재정의", "금융의");
    }

    @Test
    @DisplayName("★ Part 3 종류를 첫 열 이름으로 판별한다 — 유닛 번호로 추정하지 않는다")
    @EnabledIf("manuscriptExists")
    void part3KindFromHeader() throws Exception {
        try (InputStream in = Files.newInputStream(file(1))) {
            importService.importFile("DVOCA_Unit01.xlsx", in);   // 구동사
        }
        try (InputStream in = Files.newInputStream(file(25))) {
            importService.importFile("DVOCA_Unit25.xlsx", in);   // 혼동 어휘
        }
        try (InputStream in = Files.newInputStream(file(40))) {
            importService.importFile("DVOCA_Unit40.xlsx", in);   // 숙어
        }
        em.flush();

        assertThat(phraseRepository.findByUnitNoAndDeletedFalse((short) 1))
                .isNotEmpty()
                .allMatch(p -> p.getKind() == VocabPhrase.Kind.PHRASAL_VERB);
        assertThat(phraseRepository.findByUnitNoAndDeletedFalse((short) 25))
                .isNotEmpty()
                .allMatch(p -> p.getKind() == VocabPhrase.Kind.CONFUSING);
        assertThat(phraseRepository.findByUnitNoAndDeletedFalse((short) 40))
                .isNotEmpty()
                .allMatch(p -> p.getKind() == VocabPhrase.Kind.IDIOM);
    }

    @Test
    @DisplayName("★★ 다시 올려도 행이 늘지 않고, 관리자 인정 표현(MANUAL)은 남는다")
    @EnabledIf("manuscriptExists")
    void reuploadKeepsManualMeanings() throws Exception {
        try (InputStream in = Files.newInputStream(file(1))) {
            importService.importFile("DVOCA_Unit01.xlsx", in);
        }
        em.flush();

        var word = wordRepository.findBySeqNoAndDeletedFalse("0001").orElseThrow();
        meaningRepository.save(new VocabMeaning(word, "돈에 관한", VocabMeaning.Origin.MANUAL));
        em.flush();

        // 원고를 다시 올린다
        try (InputStream in = Files.newInputStream(file(1))) {
            var again = importService.importFile("DVOCA_Unit01.xlsx", in);
            assertThat(again.wordInserted()).isZero();
            assertThat(again.wordUpdated()).isEqualTo(40);
        }
        em.flush();
        em.clear();

        var reloaded = wordRepository.findBySeqNoAndDeletedFalse("0001").orElseThrow();
        var texts = meaningRepository.findByWordIdAndDeletedFalse(reloaded.getId()).stream()
                .map(VocabMeaning::getText).toList();
        // ★★ 관리자가 넣은 것이 살아 있다 — 지우면 복구할 방법이 없다
        assertThat(texts).contains("돈에 관한", "재정의", "금융의");
        // 그리고 SPLIT 이 두 벌로 늘지 않았다
        assertThat(texts.stream().filter("재정의"::equals).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("★★ 원고 50개 전체가 들어간다 — 단어 2,000 · Part3 300, 오류는 2건뿐")
    @EnabledIf("manuscriptExists")
    void allFiftyFilesLoad() throws Exception {
        int words = 0;
        int phrases = 0;
        var errors = new java.util.ArrayList<String>();
        for (int unit = 1; unit <= 50; unit++) {
            try (InputStream in = Files.newInputStream(file(unit))) {
                var result = importService.importFile(
                        "DVOCA_Unit%02d.xlsx".formatted(unit), in);
                words += result.wordInserted();
                phrases += result.phraseCount();
                final int u = unit;
                result.errors().forEach(e -> errors.add("Unit %d — %s".formatted(u, e)));
            }
        }
        em.flush();

        assertThat(words).isEqualTo(2_000);
        assertThat(phrases).isEqualTo(300);
        // ★★ 원고 오류가 정확히 2건이어야 한다 — 품사 1건(beneath, Unit 15) ·
        //    예문 해석 1건(allow, Unit 31). 전달 문서의 실측과 같은 숫자다.
        //    더 나오면 파서가 멀쩡한 행을 오류로 보는 것이고, 덜 나오면 놓치는 것이다
        System.out.println("원고 오류: " + errors);
        assertThat(errors).hasSize(2);
        assertThat(errors).anyMatch(e -> e.contains("beneath") && e.contains("품사"));
        assertThat(errors).anyMatch(e -> e.contains("allow") && e.contains("해석"));
        assertThat(wordRepository.countByDeletedFalse()).isEqualTo(2_000);
    }
}
