package com.dlab.domain.vocab.repository;

import com.dlab.domain.vocab.entity.VocabMeaning;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VocabMeaningRepository extends JpaRepository<VocabMeaning, Long> {

    List<VocabMeaning> findByWordIdAndDeletedFalse(Long wordId);

    /** ★ 재업로드 때 SPLIT 만 지운다 — MANUAL(관리자 인정 표현)은 남겨야 한다. */
    List<VocabMeaning> findByWordIdAndOriginAndDeletedFalse(
            Long wordId, VocabMeaning.Origin origin);

    /**
     * 여러 단어의 뜻을 한 번에.
     *
     * <p>★ 채점이 단어마다 조회하면 <b>문항 수만큼 쿼리가 난다</b>(실측: 문항 20개면 +20).
     * 제출은 시험이 끝나는 순간 한꺼번에 몰리는 호출이라 v1 9장의 2,500명 동시 접속에서
     * 가장 먼저 걸린다.
     */
    List<VocabMeaning> findByWordIdInAndDeletedFalse(java.util.Collection<Long> wordIds);

    long countByDeletedFalse();
}
