package com.dlab.domain.vocab.repository;

import com.dlab.domain.vocab.entity.VocabPhrase;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VocabPhraseRepository extends JpaRepository<VocabPhrase, Long> {

    Optional<VocabPhrase> findByUnitNoAndExpressionAndDeletedFalse(
            Short unitNo, String expression);

    List<VocabPhrase> findByUnitNoAndDeletedFalse(Short unitNo);

    long countByDeletedFalse();
}
