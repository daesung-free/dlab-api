package com.dlab.api.admin.vocab;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.vocab.entity.VocabWord;
import com.dlab.domain.vocab.repository.VocabMeaningRepository;
import com.dlab.domain.vocab.repository.VocabWordRepository;
import com.dlab.domain.vocab.service.VocabImportService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * D:VOCA 단어 관리 (개발문서 v2 착수 순서 1·3).
 *
 * <p>교재 「D:VOCA」 50유닛 × 40단어 = 2,000개를 원고 엑셀로 적재한다.
 */
@Tag(name = "관리자 - D:VOCA 단어")
@RestController
@RequestMapping("/api/v1/admin/vocab")
@RequiredArgsConstructor
public class AdminVocabController {

    private final VocabImportService importService;
    private final VocabWordRepository wordRepository;
    private final VocabMeaningRepository meaningRepository;

    /**
     * 원고 엑셀 적재. <b>여러 파일을 한 번에</b> 올린다(50개가 한 묶음이다).
     *
     * <h2>★★ 유닛 번호는 파일명에서 뽑는다</h2>
     * 시트명에 유닛 번호가 없는 파일이 <b>30개</b>라 시트명으로는 60%가 실패한다.
     * 그래서 <b>파일명을 바꿔 올리면 엉뚱한 유닛에 들어간다</b> — 원본 파일명
     * ({@code DVOCA_Unit07.xlsx})을 그대로 올려야 한다. 없으면 400이다.
     *
     * <h2>★ 오류 행이 있어도 정상 행은 들어간다</h2>
     * 원고에 오류가 2건 있다(품사 1 · 예문 해석 1). 2,000행 중 2개 때문에 전부
     * 막히면 안 되고, <b>파서가 추정해 채우지 않는다</b> — 응답의 {@code errors} 를
     * 화면에 보여주고 관리자가 채운다.
     *
     * <h2>★ 다시 올려도 행이 늘지 않는다</h2>
     * 번호 기준으로 덮어쓴다. ⚠️ <b>뜻 후보는 {@code SPLIT} 만 다시 만들고
     * {@code MANUAL}(관리자 인정 표현)은 남긴다</b> — 그건 학생 오답을 보고 하나씩
     * 넣은 것이라 지우면 복구할 방법이 없다.
     *
     * <p>⚠️ <b>본사만</b> 올린다. 전 지점 공통 교재다.
     */
    @PostMapping("/import")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<List<VocabImportService.ImportResult>> importManuscript(
            @CurrentAccount AuthPrincipal me,
            @RequestPart("files") List<MultipartFile> files) throws IOException {

        List<VocabImportService.ImportResult> results = new ArrayList<>();
        for (MultipartFile file : files) {
            results.add(importService.importFile(
                    file.getOriginalFilename(), file.getInputStream()));
        }
        return ApiResponse.success(results);
    }

    /** 적재 현황 — 화면이 「몇 개 들어갔는지」를 먼저 보여줘야 한다. */
    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER','STAFF','READONLY')")
    @Transactional(readOnly = true)
    public ApiResponse<Status> status() {
        return ApiResponse.success(new Status(
                wordRepository.countByDeletedFalse(),
                meaningRepository.countByDeletedFalse()));
    }

    /** 유닛의 단어 목록. */
    @GetMapping("/words")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER','STAFF','READONLY')")
    @Transactional(readOnly = true)
    public ApiResponse<List<WordView>> words(@RequestParam Integer unitNo) {
        return ApiResponse.success(wordRepository
                .findByUnitNoAndDeletedFalseOrderBySeqNoAsc(unitNo.shortValue()).stream()
                .map(w -> WordView.of(w, meaningRepository
                        .findByWordIdAndDeletedFalse(w.getId()).stream()
                        .map(m -> new MeaningView(m.getId(), m.getText(), m.getOrigin().name()))
                        .toList()))
                .toList());
    }

    /**
     * @param wordCount    표제어 수. 전부 들어가면 2,000이다
     * @param meaningCount 정답 후보 수 — 뜻을 쪼갠 결과라 단어 수보다 많다
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "VocabStatusResponse")
    public record Status(long wordCount, long meaningCount) {
    }

    /**
     * @param pos ★ 복합 품사가 18.7%다({@code v; n}). 단일 값으로 가정하지 말 것
     * @param meanings 정답 후보. {@code origin} 이 {@code MANUAL} 이면 관리자가 넣은 것이다
     */
    public record WordView(Long id, Short unitNo, String seqNo, String word,
                           String pronunciation, String pos, String meaningRaw,
                           String exampleEn, String exampleKo, String source,
                           String synonym, String antonym, String derivative,
                           String etymology, List<MeaningView> meanings) {

        static WordView of(VocabWord w, List<MeaningView> meanings) {
            return new WordView(w.getId(), w.getUnitNo(), w.getSeqNo(), w.getWord(),
                    w.getPronunciation(), w.getPos(), w.getMeaningRaw(),
                    w.getExampleEn(), w.getExampleKo(), w.getSource(),
                    w.getSynonym(), w.getAntonym(), w.getDerivative(), w.getEtymology(),
                    meanings);
        }
    }

    public record MeaningView(Long id, String text, String origin) {
    }
}
