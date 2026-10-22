package com.dlab.domain.vocab.repository;

import com.dlab.domain.vocab.entity.VocabNoExamDay;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VocabNoExamDayRepository extends JpaRepository<VocabNoExamDay, Long> {

    List<VocabNoExamDay> findByAcademyIdAndYearAndDeletedFalse(Long academyId, Short year);

    Optional<VocabNoExamDay> findByAcademyIdAndYearAndExamDateAndDeletedFalse(
            Long academyId, Short year, LocalDate examDate);
}
