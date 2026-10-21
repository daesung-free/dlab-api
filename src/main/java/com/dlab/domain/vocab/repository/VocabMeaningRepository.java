package com.dlab.domain.vocab.repository;

import com.dlab.domain.vocab.entity.VocabMeaning;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VocabMeaningRepository extends JpaRepository<VocabMeaning, Long> {

    List<VocabMeaning> findByWordIdAndDeletedFalse(Long wordId);

    /** ★ 재업로드 때 SPLIT 만 지운다 — MANUAL(관리자 인정 표현)은 남겨야 한다. */
    List<VocabMeaning> findByWordIdAndOriginAndDeletedFalse(
            Long wordId, VocabMeaning.Origin origin);

    long countByDeletedFalse();
}
