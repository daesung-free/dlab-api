package com.dlab.domain.vocab.repository;

import com.dlab.domain.vocab.entity.VocabPhrase;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VocabPhraseRepository extends JpaRepository<VocabPhrase, Long> {

    Optional<VocabPhrase> findByUnitNoAndExpressionAndDeletedFalse(
            Short unitNo, String expression);

    List<VocabPhrase> findByUnitNoAndDeletedFalse(Short unitNo);

    /** ★ 객관식 보기 폴백에서만 쓴다 — 300건이라 1단계에서 차면 읽지 않는다 */
    List<VocabPhrase> findByDeletedFalse();

    long countByDeletedFalse();
}
