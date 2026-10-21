package com.dlab.api.admin.vocab;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.vocab.entity.VocabWord;
import com.dlab.domain.vocab.repository.VocabMeaningRepository;
import com.dlab.domain.vocab.repository.VocabWordRepository;
import com.dlab.domain.vocab.service.VocabImportService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final com.dlab.domain.vocab.service.VocabWordService wordService;
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
     * 단어 수정 (v2 착수 순서 3).
     *
     * <p>★ <b>원고 오류를 채우는 자리다</b> — 적재 때 오류로 띄운 2건(품사 · 예문 해석)을
     * 여기서 넣는다. 파서가 추정해 채우지 않기 때문이다.
     *
     * <p>⚠️ <b>번호는 바꿀 수 없다.</b> 원고와 대조하는 유일한 키이고 재업로드가 그 값으로
     * 덮어쓴다 — 바꾸면 다음 업로드에서 <b>같은 단어가 두 벌로</b> 들어간다.
     *
     * <p>★ <b>뜻을 바꾸면 정답 후보를 다시 만든다 — 다만 관리자가 넣은 인정 표현은
     * 남는다.</b>
     *
     * <p>⚠️ <b>이미 응시 기록이 있는 단어의 뜻을 바꾸면 경고를 띄울 것.</b> 지난 점수는
     * {@code vocab_answer} 가 그때의 답·정오·보기를 들고 있어 흔들리지 않지만,
     * 담당자는 그 사실을 알고 고쳐야 한다.
     */
    @PatchMapping("/words/{wordId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<Void> modifyWord(@PathVariable Long wordId,
                                        @Valid @RequestBody ModifyWord request) {
        wordService.modify(wordId, request.word(), request.pronunciation(), request.pos(),
                request.meaningRaw(), request.exampleEn(), request.exampleKo(),
                request.synonym(), request.antonym(), request.derivative(),
                request.etymology(), request.source());
        // 뜻을 바꾸면 후보가 다시 만들어진다 — 목록을 다시 받아야 그 결과가 보인다
        return ApiResponse.success(null);
    }

    /** 출제에서 빼거나 되살린다. ★ 지우지 않는다 — 지난 응시가 이 단어를 가리킨다. */
    @PutMapping("/words/{wordId}/active")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<Void> changeActive(@PathVariable Long wordId,
                                          @Valid @RequestBody ChangeActive request) {
        wordService.changeActive(wordId, request.active());
        return ApiResponse.success(null);
    }

    /**
     * 추가 인정 표현 (v2 5.3).
     *
     * <p>규칙으로 안 걸리는 표현(어순 차이·조사 차이·동의어)을 <b>오답 화면에서 보고</b>
     * 넣는다. {@code MANUAL} 로 저장돼 <b>원고를 다시 올려도 살아남는다.</b>
     *
     * <p>⚠️ <b>넣어도 지난 오답이 정답으로 바뀌지 않는다</b>(v2 5.3) — 점수가 조용히
     * 변하면 안 된다. 필요하면 그 문항만 사람이 정정한다. 화면에 그 안내가 필요하다.
     */
    @PostMapping("/words/{wordId}/meanings")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<MeaningView> addMeaning(@PathVariable Long wordId,
                                               @Valid @RequestBody AddMeaning request) {
        var saved = wordService.addMeaning(wordId, request.text());
        return ApiResponse.success(
                new MeaningView(saved.getId(), saved.getText(), saved.getOrigin().name()));
    }

    /**
     * 인정 표현 삭제.
     *
     * <p>★ {@code SPLIT} 도 지울 수 있다 — 규칙이 엉뚱하게 쪼갠 경우가 있다. 다만
     * <b>뜻을 다시 저장하면 되살아난다</b>(규칙에서 파생되는 값이다). 영구히 빼려면
     * 원고 뜻 자체를 고쳐야 한다.
     */
    @DeleteMapping("/words/{wordId}/meanings/{meaningId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<Void> removeMeaning(@PathVariable Long wordId,
                                           @PathVariable Long meaningId) {
        wordService.removeMeaning(wordId, meaningId);
        return ApiResponse.success(null);
    }

    /** 비워 보낸 항목은 바꾸지 않는다. ⚠️ 번호(seqNo)·유닛은 받지 않는다 */
    @io.swagger.v3.oas.annotations.media.Schema(name = "VocabModifyWordRequest")
    public record ModifyWord(
            @jakarta.validation.constraints.Size(max = 100) String word,
            @jakarta.validation.constraints.Size(max = 100) String pronunciation,
            @jakarta.validation.constraints.Size(max = 20) String pos,
            @jakarta.validation.constraints.Size(max = 500) String meaningRaw,
            @jakarta.validation.constraints.Size(max = 1000) String exampleEn,
            @jakarta.validation.constraints.Size(max = 1000) String exampleKo,
            @jakarta.validation.constraints.Size(max = 300) String synonym,
            @jakarta.validation.constraints.Size(max = 300) String antonym,
            @jakarta.validation.constraints.Size(max = 300) String derivative,
            @jakarta.validation.constraints.Size(max = 1000) String etymology,
            @jakarta.validation.constraints.Size(max = 50) String source) {
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "VocabChangeActiveRequest")
    public record ChangeActive(
            @jakarta.validation.constraints.NotNull(message = "출제 여부는 필수입니다.")
            Boolean active) {
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "VocabAddMeaningRequest")
    public record AddMeaning(
            @jakarta.validation.constraints.NotBlank(message = "인정 표현은 필수입니다.")
            @jakarta.validation.constraints.Size(max = 300) String text) {
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
