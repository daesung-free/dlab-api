package com.dlab.domain.penalty.repository;

import com.dlab.domain.penalty.entity.PenaltyItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PenaltyItemRepository extends JpaRepository<PenaltyItem, Long> {

    List<PenaltyItem> findByAcademyIdAndYearAndDeletedFalseOrderByItemNameAsc(Long academyId, short year);

    /** 이름 중복 확인. 삭제된 항목 이름은 다시 쓸 수 있다. */
    java.util.Optional<PenaltyItem> findByAcademyIdAndYearAndItemNameAndDeletedFalse(
            Long academyId, short year, String itemName);
}
