package com.dlab.domain.vocab.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.vocab.entity.VocabMeaning;
import com.dlab.domain.vocab.entity.VocabPhrase;
import com.dlab.domain.vocab.entity.VocabWord;
import com.dlab.domain.vocab.repository.VocabMeaningRepository;
import com.dlab.domain.vocab.repository.VocabPhraseRepository;
import com.dlab.domain.vocab.repository.VocabWordRepository;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * D:VOCA 원고 엑셀 적재 (개발문서 v2 4장 · 착수 순서 1).
 *
 * <h2>★★ 유닛 번호는 파일명에서 뽑는다 — 시트명을 파싱하지 말 것</h2>
 * 시트명이 두 형식으로 섞여 있다(실측).
 * <pre>
 * Unit 01~05, 16~30   "Part 1 Unit 01"   유닛 번호 있음   20개 파일
 * Unit 06~15, 31~50   "Part 1"           유닛 번호 없음   30개 파일
 * </pre>
 * <b>30개 파일에 유닛 번호가 없다 — 시트명을 파싱하면 60%가 실패한다.</b>
 * 시트는 {@code Part 1}·{@code Part 3} 으로 <b>시작하는지만</b> 본다.
 *
 * <h2>★ 오류 행이 있어도 정상 행은 넣는다</h2>
 * 원고에 오류가 2건 있다(품사 빈 칸 1 · 예문 해석 빈 칸 1). 2,000행 중 2개 때문에
 * 전부 막히면 안 된다. <b>파서가 추정해 채우지 않고</b> 오류로 보여주고 관리자가
 * 화면에서 채운다.
 *
 * <h2>★ 같은 유닛을 다시 올리면 덮어쓴다</h2>
 * 번호 기준이다. 중복이 쌓이면 시험 범위에 같은 단어가 두 번 나온다.
 * ⚠️ 뜻 후보는 {@code SPLIT} 만 다시 만들고 {@code MANUAL} 은 남긴다 —
 * 관리자가 쌓아 둔 인정 표현이 사라지면 복구할 방법이 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VocabImportService {

    /** ★ 파일명에서 유닛을 뽑는다. {@code DVOCA_Unit07.xlsx} → 7 */
    private static final Pattern UNIT_IN_FILENAME =
            Pattern.compile("Unit\\s*(\\d{1,2})", Pattern.CASE_INSENSITIVE);

    private static final DataFormatter FORMATTER = new DataFormatter();

    private final VocabWordRepository wordRepository;
    private final VocabMeaningRepository meaningRepository;
    private final VocabPhraseRepository phraseRepository;

    /**
     * @param errors 엑셀 기준 <b>1-based</b> 행 번호가 들어간다 — 0-based 로 주면
     *               사용자가 엑셀에서 그 행을 찾지 못한다
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "VocabImportResult")
    public record ImportResult(Short unitNo, int wordInserted, int wordUpdated,
                               int meaningCount, int phraseCount, List<String> errors) {
    }

    @Transactional
    public ImportResult importFile(String fileName, InputStream input) {
        Short unitNo = unitOf(fileName);

        try (Workbook workbook = new XSSFWorkbook(input)) {
            List<String> errors = new ArrayList<>();
            var words = importPart1(workbook, unitNo, errors);
            int phrases = importPart3(workbook, unitNo, errors);

            log.info("D:VOCA 적재: 파일={}, 유닛={}, 단어 신규={}/갱신={}, 뜻={}, Part3={}, 오류={}",
                    fileName, unitNo, words.inserted(), words.updated(), words.meanings(),
                    phrases, errors.size());

            return new ImportResult(unitNo, words.inserted(), words.updated(),
                    words.meanings(), phrases, errors);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "원고 파일을 읽을 수 없습니다: " + e.getMessage());
        }
    }

    /**
     * ★ 파일명이 유닛의 진실이다.
     *
     * <p>⚠️ 없으면 거절한다 — 추정하면 <b>엉뚱한 유닛에 40단어가 들어가고</b>,
     * 그 뒤 시험 범위가 통째로 어긋난다. 그때 원인을 찾기가 매우 어렵다.
     */
    private static Short unitOf(String fileName) {
        Matcher m = UNIT_IN_FILENAME.matcher(fileName == null ? "" : fileName);
        if (!m.find()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "파일명에서 유닛 번호를 찾을 수 없습니다. "
                            + "「DVOCA_Unit07.xlsx」처럼 유닛 번호가 들어가야 합니다 — "
                            + "시트명에는 유닛 번호가 없는 파일이 많아 파일명으로 판단합니다.");
        }
        int unit = Integer.parseInt(m.group(1));
        if (unit < 1 || unit > 50) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "유닛 번호가 1~50 범위를 벗어났습니다: " + unit);
        }
        return (short) unit;
    }

    private record WordCounts(int inserted, int updated, int meanings) {
    }

    private WordCounts importPart1(Workbook workbook, Short unitNo, List<String> errors) {
        Sheet sheet = sheetStartingWith(workbook, "Part 1");
        if (sheet == null) {
            errors.add("Part 1 시트가 없습니다.");
            return new WordCounts(0, 0, 0);
        }
        Map<String, Integer> header = headerOf(sheet);
        requireColumns(header, "번호", "표제어", "단어 뜻");

        int inserted = 0;
        int updated = 0;
        int meanings = 0;

        for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            String seqNo = text(row, header, "번호");
            String word = text(row, header, "표제어");
            // ★ 시트 끝에 값 없는 행이 붙어 있다 — 전 열이 비면 건너뛴다
            if (seqNo.isBlank() && word.isBlank()) {
                continue;
            }
            int excelRow = r + 1;
            if (seqNo.isBlank() || word.isBlank()) {
                errors.add("%d행: 번호와 표제어는 비어 있을 수 없습니다.".formatted(excelRow));
                continue;
            }
            String meaningRaw = text(row, header, "단어 뜻");
            if (meaningRaw.isBlank()) {
                // ★ 추정해 채우지 않는다. 원고 오류를 그대로 드러내고 관리자가 채운다
                errors.add("%d행(%s): 단어 뜻이 비어 있습니다.".formatted(excelRow, word));
                continue;
            }
            // ★ 오류로 남기되 행 자체는 넣는다 — 2,000행 중 1개 때문에 전부 막히면
            //   안 된다. 파서가 추정해 채우지 않고 관리자가 화면에서 채운다.
            //
            // ★★ 품사 빈 칸은 오류가 아니다 (업체 회신 2026-10-22)
            //   실측에서 1건(Unit 15 beneath) 나왔던 것을 오류로 띄웠는데, 업체가
            //   「비어 있는 게 맞다. 수정 불필요」로 회신했다. 계속 띄우면 업로드마다
            //   고칠 수 없는 오류가 한 줄 남아, 정작 봐야 할 오류가 묻힌다.
            //   ⚠️ 보기 추출은 품사가 비면 3단계(품사 풀기)로 떨어진다 — 못 쓰는 것은 아니다.
            String pos = text(row, header, "품사");
            if (text(row, header, "해석").isBlank()) {
                // 채점에는 안 쓰지만 결과 화면에 예문만 뜨고 해석이 비어 보인다
                errors.add("%d행(%s): 예문 해석이 비어 있습니다.".formatted(excelRow, word));
            }

            var existing = wordRepository.findBySeqNoAndDeletedFalse(seqNo);
            VocabWord entity;
            if (existing.isPresent()) {
                entity = existing.get();
                entity.overwrite(unitNo, word, text(row, header, "발음"), pos, meaningRaw,
                        text(row, header, "유의어"), text(row, header, "반의어"),
                        text(row, header, "영문 예문"), text(row, header, "해석"),
                        text(row, header, "출처"), text(row, header, "파생어"),
                        text(row, header, "어원 PLUS"));
                updated++;
            } else {
                entity = new VocabWord(unitNo, seqNo, word, meaningRaw);
                entity.overwrite(unitNo, word, text(row, header, "발음"), pos, meaningRaw,
                        text(row, header, "유의어"), text(row, header, "반의어"),
                        text(row, header, "영문 예문"), text(row, header, "해석"),
                        text(row, header, "출처"), text(row, header, "파생어"),
                        text(row, header, "어원 PLUS"));
                entity = wordRepository.save(entity);
                inserted++;
            }
            meanings += rebuildMeanings(entity, meaningRaw);
        }
        return new WordCounts(inserted, updated, meanings);
    }

    /**
     * 뜻 후보를 다시 만든다.
     *
     * <p>★★ {@code SPLIT} 만 지우고 {@code MANUAL} 은 남긴다. 관리자가 오답 화면에서
     * 하나씩 넣은 인정 표현이라 지우면 <b>복구할 방법이 없다.</b>
     */
    private int rebuildMeanings(VocabWord word, String meaningRaw) {
        meaningRepository
                .findByWordIdAndOriginAndDeletedFalse(word.getId(), VocabMeaning.Origin.SPLIT)
                .forEach(VocabMeaning::markDeleted);

        List<VocabMeaning> manual = meaningRepository
                .findByWordIdAndOriginAndDeletedFalse(word.getId(), VocabMeaning.Origin.MANUAL);
        List<String> kept = manual.stream().map(VocabMeaning::getText).toList();

        List<VocabMeaning> created = new ArrayList<>();
        for (String candidate : MeaningSplitter.split(meaningRaw)) {
            // 관리자가 이미 같은 표현을 넣어 뒀으면 중복으로 만들지 않는다(유니크 제약)
            if (!kept.contains(candidate)) {
                created.add(new VocabMeaning(word, candidate, VocabMeaning.Origin.SPLIT));
            }
        }
        meaningRepository.saveAll(created);
        return created.size();
    }

    /**
     * Part 3 — 구동사·숙어·혼동어휘.
     *
     * <p>★ <b>첫 열 이름으로 종류를 판별한다.</b> 유닛 번호로 추정하지 않는다 — 원고가
     * 개정되면 구간이 바뀌고, 그때 조용히 틀린 종류로 들어간다.
     *
     * <p>⚠️ <b>열 개수를 4로 고정하지 말 것</b> — 빈 열이 붙은 파일이 있다.
     * 헤더 이름으로 찾으므로 영향이 없다.
     */
    private int importPart3(Workbook workbook, Short unitNo, List<String> errors) {
        Sheet sheet = sheetStartingWith(workbook, "Part 3");
        if (sheet == null) {
            return 0;
        }
        Map<String, Integer> header = headerOf(sheet);
        String firstColumn = firstHeaderName(sheet);
        VocabPhrase.Kind kind = VocabPhrase.Kind.ofHeader(firstColumn);
        if (kind == null) {
            errors.add("Part 3 의 첫 열 이름(%s)으로 종류를 판별할 수 없습니다. "
                    .formatted(firstColumn) + "구동사·혼동 어휘·숙어 중 하나여야 합니다.");
            return 0;
        }

        int count = 0;
        for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            String expression = text(row, header, firstColumn);
            if (expression.isBlank()) {
                continue;
            }
            // ⚠️ 해석에 줄바꿈이 들어 있다(뜻이 ①② 두 개인 경우) — 그대로 보존한다
            String meaning = text(row, header, "뜻");
            String exampleEn = text(row, header, "예문");
            String exampleKo = text(row, header, "해석");

            var existing = phraseRepository
                    .findByUnitNoAndExpressionAndDeletedFalse(unitNo, expression);
            if (existing.isPresent()) {
                existing.get().overwrite(kind, meaning, exampleEn, exampleKo);
            } else {
                phraseRepository.save(new VocabPhrase(
                        unitNo, kind, expression, meaning, exampleEn, exampleKo));
            }
            count++;
        }
        return count;
    }

    /** ★ 시트명이 유닛 번호를 포함하는지는 보지 않는다 — 30개 파일에 없다. */
    private static Sheet sheetStartingWith(Workbook workbook, String prefix) {
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet sheet = workbook.getSheetAt(i);
            if (sheet.getSheetName() != null && sheet.getSheetName().startsWith(prefix)) {
                return sheet;
            }
        }
        return null;
    }

    /** 헤더 이름 → 열 번호. ★ 열 순서에 의존하지 않는다. */
    private static Map<String, Integer> headerOf(Sheet sheet) {
        Map<String, Integer> map = new HashMap<>();
        Row header = sheet.getRow(sheet.getFirstRowNum());
        if (header == null) {
            return map;
        }
        for (int c = 0; c < header.getLastCellNum(); c++) {
            String name = cellText(header.getCell(c));
            if (!name.isBlank()) {
                map.putIfAbsent(name.replaceAll("\\s+", " ").trim(), c);
            }
        }
        return map;
    }

    private static String firstHeaderName(Sheet sheet) {
        Row header = sheet.getRow(sheet.getFirstRowNum());
        return header == null ? "" : cellText(header.getCell(0));
    }

    private static void requireColumns(Map<String, Integer> header, String... required) {
        List<String> missing = new ArrayList<>();
        for (String name : required) {
            if (!header.containsKey(name)) {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "필수 열이 없습니다: " + String.join(", ", missing));
        }
    }

    private static String text(Row row, Map<String, Integer> header, String name) {
        Integer index = header.get(name);
        return index == null ? "" : cellText(row.getCell(index));
    }

    /**
     * ★ 전부 문자열로 읽는다.
     *
     * <p>번호가 {@code '0001'} 인데 숫자 서식이면 {@code 1} 로 읽힌다 —
     * {@link DataFormatter} 는 셀 서식을 그대로 따라 읽으므로 0 패딩이 살아 있다.
     */
    private static String cellText(Cell cell) {
        if (cell == null) {
            return "";
        }
        return FORMATTER.formatCellValue(cell).trim();
    }
}
