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
    @Autowired com.dlab.domain.vocab.service.VocabWordService wordService;

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
    @DisplayName("★★ 원고 50개 전체가 들어간다 — 단어 2,000 · Part3 300, 오류는 해석 1건뿐")
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
        // ★★ 업체 회신(2026-10-22)으로 품사 빈 칸(Unit 15 beneath)은 「맞다」가 됐다 —
        //    더 이상 오류가 아니다. 남는 것은 예문 해석 1건(allow, Unit 31)뿐이고,
        //    업체가 그 해석 문구를 줬으므로 원고가 갱신되면 0건이 된다.
        //    ⚠️ 그래서 「정확히 1건」으로 못 박지 않는다 — 원고 갱신이 테스트를 깨면 안 된다.
        System.out.println("원고 오류: " + errors);
        assertThat(errors).as("★ 품사 빈 칸은 업체 확인 결과 정상이다").noneMatch(e -> e.contains("품사"));
        assertThat(errors).as("남는 오류는 예문 해석 누락뿐이다").allMatch(e -> e.contains("해석"));
        assertThat(errors).hasSizeLessThanOrEqualTo(1);
        assertThat(wordRepository.countByDeletedFalse()).isEqualTo(2_000);
    }

    // ─────────────────────────────────────────────────────────────
    // 단어 관리 (v2 착수 순서 3)
    // ─────────────────────────────────────────────────────────────

    /** 적재 없이 단어 하나를 직접 만든다 — 원고 파일이 없는 CI 에서도 돈다. */
    private com.dlab.domain.vocab.entity.VocabWord word(String seq, String raw) {
        var w = new com.dlab.domain.vocab.entity.VocabWord((short) 1, seq, "financial", raw);
        em.persist(w);
        for (String t : com.dlab.domain.vocab.service.MeaningSplitter.split(raw)) {
            em.persist(new VocabMeaning(w, t, VocabMeaning.Origin.SPLIT));
        }
        em.flush();
        return w;
    }

    @Test
    @DisplayName("★★ 뜻을 고쳐도 관리자가 넣은 인정 표현은 남는다 — 지우면 복구할 방법이 없다")
    void modifyingMeaningKeepsManualEntries() {
        var w = word("9001", "재정의, 금융의");
        wordService.addMeaning(w.getId(), "돈에 관한");
        em.flush();

        wordService.modify(w.getId(), null, null, null, "재정의, 금전의",
                null, null, null, null, null, null, null);
        em.flush();
        em.clear();

        var texts = meaningRepository.findByWordIdAndDeletedFalse(w.getId()).stream()
                .map(VocabMeaning::getText).toList();
        // ★ 관리자가 학생 오답을 보고 넣은 것이라 살아남아야 한다
        assertThat(texts).contains("돈에 관한");
        // 새 뜻에서 파생된 후보가 들어오고 옛 SPLIT 은 빠진다
        assertThat(texts).contains("재정의", "금전의").doesNotContain("금융의");
    }

    @Test
    @DisplayName("★ 같은 인정 표현을 두 번 넣으면 400 — 「넣었는데 안 들어갔다」로 보이면 안 된다")
    void duplicateManualMeaningIsRejected() {
        var w = word("9002", "경제");
        wordService.addMeaning(w.getId(), "돈 문제");
        em.flush();

        assertThatThrownBy(() -> wordService.addMeaning(w.getId(), "돈 문제"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("이미 등록된");
    }

    @Test
    @DisplayName("★ 인정 표현도 학생 답과 같은 규칙으로 다듬어 저장한다")
    void manualMeaningIsNormalized() {
        var w = word("9003", "경제");
        var saved = wordService.addMeaning(w.getId(), "  돈 문제.  ");
        em.flush();

        assertThat(saved.getText()).isEqualTo("돈 문제");
    }

    @Test
    @DisplayName("★ 다른 단어의 인정 표현 id 로는 지워지지 않는다")
    void cannotRemoveAnotherWordsMeaning() {
        var a = word("9004", "경제");
        var b = word("9005", "재정의");
        var mine = wordService.addMeaning(b.getId(), "내 것");
        em.flush();

        assertThatThrownBy(() -> wordService.removeMeaning(a.getId(), mine.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("그 단어의");
    }

    @Test
    @DisplayName("★ 출제에서 빼도 단어를 지우지 않는다 — 지난 응시가 이 단어를 가리킨다")
    void deactivateKeepsTheRow() {
        var w = word("9006", "경제");

        wordService.changeActive(w.getId(), false);
        em.flush();
        em.clear();

        var after = wordRepository.findBySeqNoAndDeletedFalse("9006").orElseThrow();
        assertThat(after.isActive()).isFalse();
        assertThat(after.isDeleted()).isFalse();
    }
}
