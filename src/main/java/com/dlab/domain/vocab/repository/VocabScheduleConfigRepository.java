package com.dlab.domain.vocab.repository;

import com.dlab.domain.vocab.entity.VocabScheduleConfig;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VocabScheduleConfigRepository extends JpaRepository<VocabScheduleConfig, Long> {

    Optional<VocabScheduleConfig> findByAcademyIdAndYearAndDeletedFalse(
            Long academyId, Short year);
}
