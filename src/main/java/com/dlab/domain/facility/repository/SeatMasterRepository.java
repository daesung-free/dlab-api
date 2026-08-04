package com.dlab.domain.facility.repository;

import com.dlab.domain.facility.entity.SeatMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SeatMasterRepository extends JpaRepository<SeatMaster, Long> {

    @Query("""
            SELECT s FROM SeatMaster s
            JOIN FETCH s.studyArea
            JOIN FETCH s.academy
            WHERE s.id = :id AND s.deleted = false
            """)
    Optional<SeatMaster> findDetailById(Long id);

    @Query("""
            SELECT s FROM SeatMaster s
            WHERE s.studyArea.id = :studyAreaId AND s.deleted = false
            ORDER BY s.yPos ASC, s.xPos ASC
            """)
    List<SeatMaster> findByStudyAreaId(Long studyAreaId);
}
