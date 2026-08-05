package com.dlab.domain.penalty.repository;

import com.dlab.domain.penalty.entity.PenaltyItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PenaltyItemRepository extends JpaRepository<PenaltyItem, Long> {

    List<PenaltyItem> findByAcademyIdAndYearAndDeletedFalseOrderByItemNameAsc(Long academyId, short year);
}
