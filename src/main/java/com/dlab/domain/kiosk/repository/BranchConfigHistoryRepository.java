package com.dlab.domain.kiosk.repository;

import com.dlab.domain.kiosk.entity.BranchConfigHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BranchConfigHistoryRepository extends JpaRepository<BranchConfigHistory, Long> {

    List<BranchConfigHistory> findByAcademyIdAndDeletedFalseOrderByIdDesc(Long academyId);
}
