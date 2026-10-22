package com.dlab.domain.vocab.repository;

import com.dlab.domain.vocab.entity.VocabAnswer;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VocabAnswerRepository extends JpaRepository<VocabAnswer, Long> {

    List<VocabAnswer> findByAttemptIdAndDeletedFalseOrderBySeqNoAsc(Long attemptId);

    /** 오답 확인(v1 7장) — 관리자가 학생 답을 보고 인정 표현을 넓히는 자리다. */
    List<VocabAnswer> findByWordIdAndCorrectFalseAndDeletedFalse(Long wordId);

    /** 그 단어의 전체 답 — 오답률을 내려면 분모(응시 수)가 필요하다. */
    List<VocabAnswer> findByWordIdAndDeletedFalse(Long wordId);
}
