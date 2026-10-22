package com.dlab.domain.vocab.repository;

import com.dlab.domain.vocab.entity.VocabWord;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VocabWordRepository extends JpaRepository<VocabWord, Long> {

    Optional<VocabWord> findBySeqNoAndDeletedFalse(String seqNo);

    List<VocabWord> findByUnitNoAndDeletedFalseOrderBySeqNoAsc(Short unitNo);

    List<VocabWord> findByDeletedFalse();

    long countByDeletedFalse();
}
