package com.dlab.domain.vocab.repository;

import com.dlab.domain.vocab.entity.VocabExamRound;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VocabExamRoundRepository extends JpaRepository<VocabExamRound, Long> {

    /** ★ 공개된 회차만 들어 있다 — 예정은 저장하지 않는다(v1 5.6). */
    List<VocabExamRound> findByAcademyIdAndYearAndDeletedFalseOrderByRoundNoAsc(
            Long academyId, Short year);

    Optional<VocabExamRound> findByAcademyIdAndYearAndOpenDateAndWeeklyAndDeletedFalse(
            Long academyId, Short year, LocalDate openDate, boolean weekly);

    /** 공개 배치가 「어디까지 공개했는가」를 아는 유일한 근거다. */
    Optional<VocabExamRound> findFirstByAcademyIdAndYearAndDeletedFalseOrderByRoundNoDesc(
            Long academyId, Short year);
}
