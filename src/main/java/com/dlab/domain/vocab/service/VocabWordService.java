package com.dlab.domain.vocab.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.vocab.entity.VocabMeaning;
import com.dlab.domain.vocab.entity.VocabWord;
import com.dlab.domain.vocab.repository.VocabMeaningRepository;
import com.dlab.domain.vocab.repository.VocabWordRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * D:VOCA 단어 관리 (개발문서 v2 착수 순서 3).
 *
 * <p>원고 적재가 끝난 뒤 <b>사람이 손보는 자리</b>다 — 원고 오류 2건을 채우고,
 * 학생 오답을 보고 인정 표현을 넓힌다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VocabWordService {

    private final VocabWordRepository wordRepository;
    private final VocabMeaningRepository meaningRepository;

    /**
     * 단어 수정.
     *
     * <h2>★★ 뜻을 바꾸면 정답 후보를 다시 만든다 — 다만 {@code MANUAL} 은 남긴다</h2>
     * 관리자가 오답 화면에서 하나씩 넣은 인정 표현이라 <b>지우면 복구할 방법이 없다.</b>
     * 적재(재업로드)와 같은 규칙이다.
     *
     * <p>⚠️ <b>이미 응시 기록이 있는 단어의 뜻을 바꾸면 지난 채점의 근거가 달라진다.</b>
     * 그래도 막지 않는다 — 원고 오타를 영구 보존할 이유가 없다. 대신
     * {@code vocab_answer} 가 <b>그때의 답·정오·보기를 들고 있어</b> 지난 점수는
     * 흔들리지 않는다. 화면이 경고를 띄우는 것으로 충분하다.
     *
     * <p>⚠️ 인정 표현을 넓혀도 <b>지난 오답을 소급해 정답으로 바꾸지 않는다</b>(v2 5.3) —
     * 점수가 조용히 변하면 안 된다. 필요하면 그 문항만 사람이 정정한다.
     */
    @Transactional
    public VocabWord modify(Long wordId, String word, String pronunciation, String pos,
                            String meaningRaw, String exampleEn, String exampleKo,
                            String synonym, String antonym, String derivative,
                            String etymology, String source) {
        VocabWord target = require(wordId);
        boolean meaningChanged = meaningRaw != null && !meaningRaw.equals(target.getMeaningRaw());

        target.modify(word, pronunciation, pos, meaningRaw, exampleEn, exampleKo,
                synonym, antonym, derivative, etymology, source);

        if (meaningChanged) {
            int created = rebuildSplitMeanings(target);
            log.info("단어 뜻 수정으로 정답 후보 재생성: wordId={}, 후보={}", wordId, created);
        }
        return target;
    }

    /** 출제에서 빼거나 되살린다. ★ 지우지 않는다 — 지난 응시가 이 단어를 가리킨다. */
    @Transactional
    public VocabWord changeActive(Long wordId, boolean active) {
        VocabWord target = require(wordId);
        target.changeActive(active);
        return target;
    }

    /**
     * 추가 인정 표현 (v2 5.3).
     *
     * <p>디랩 요구의 뒷단이다 — 규칙으로 안 걸리는 표현(어순·조사 차이·동의어)을
     * <b>오답 화면에서 보고</b> 넣는다.
     *
     * <p>★ {@code MANUAL} 로 저장한다. 그래야 원고를 다시 올려도 살아남는다.
     *
     * <p>⚠️ 같은 표현을 두 번 넣으면 400 이다 — 유니크 제약이 막지만, 그 전에
     * 알려주는 쪽이 낫다. 「넣었는데 안 들어갔다」로 보이면 안 된다.
     */
    @Transactional
    public VocabMeaning addMeaning(Long wordId, String text) {
        VocabWord target = require(wordId);
        String normalized = MeaningSplitter.normalize(text);
        if (normalized.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "인정 표현이 비어 있습니다.");
        }
        boolean exists = meaningRepository.findByWordIdAndDeletedFalse(wordId).stream()
                .anyMatch(m -> m.getText().equals(normalized));
        if (exists) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "이미 등록된 인정 표현입니다: " + normalized);
        }
        log.info("인정 표현 추가: wordId={}, 표현={}", wordId, normalized);
        return meaningRepository.save(
                new VocabMeaning(target, normalized, VocabMeaning.Origin.MANUAL));
    }

    /**
     * 인정 표현 삭제.
     *
     * <p>★ <b>{@code SPLIT} 은 지울 수 있다</b> — 규칙이 엉뚱하게 쪼갠 경우가 있다
     * (예: 괄호 안 설명이 후보로 남는 것). 다만 <b>뜻을 다시 저장하면 되살아난다</b> —
     * 규칙에서 파생되는 값이기 때문이다. 영구히 빼려면 원고 뜻 자체를 고쳐야 한다.
     *
     * <p>soft delete 다 — 어떤 후보로 채점했는지 추적이 끊기면 안 된다.
     */
    @Transactional
    public void removeMeaning(Long wordId, Long meaningId) {
        VocabMeaning meaning = meaningRepository.findById(meaningId)
                .filter(m -> !m.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "인정 표현을 찾을 수 없습니다."));
        // ★ 다른 단어의 표현 id 로 지워지지 않게 한다
        if (!meaning.getWord().getId().equals(wordId)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "그 단어의 인정 표현이 아닙니다.");
        }
        meaning.markDeleted();
        log.info("인정 표현 삭제: wordId={}, meaningId={}, origin={}",
                wordId, meaningId, meaning.getOrigin());
    }

    /**
     * {@code SPLIT} 후보만 다시 만든다.
     *
     * <p>★★ {@code MANUAL} 은 남긴다 — 관리자가 학생 오답을 보고 하나씩 넣은 것이라
     * 지우면 복구할 방법이 없다. {@code VocabImportService} 와 같은 규칙이다.
     */
    private int rebuildSplitMeanings(VocabWord word) {
        meaningRepository
                .findByWordIdAndOriginAndDeletedFalse(word.getId(), VocabMeaning.Origin.SPLIT)
                .forEach(VocabMeaning::markDeleted);

        List<String> kept = meaningRepository
                .findByWordIdAndOriginAndDeletedFalse(word.getId(), VocabMeaning.Origin.MANUAL)
                .stream().map(VocabMeaning::getText).toList();

        List<VocabMeaning> created = new java.util.ArrayList<>();
        for (String candidate : MeaningSplitter.split(word.getMeaningRaw())) {
            if (!kept.contains(candidate)) {
                created.add(new VocabMeaning(word, candidate, VocabMeaning.Origin.SPLIT));
            }
        }
        meaningRepository.saveAll(created);
        return created.size();
    }

    private VocabWord require(Long wordId) {
        return wordRepository.findById(wordId)
                .filter(w -> !w.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "단어를 찾을 수 없습니다."));
    }
}
