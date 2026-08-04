package com.dlab.domain.master.repository;

import com.dlab.domain.master.entity.DormRoom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface DormRoomRepository extends JpaRepository<DormRoom, Long> {

    List<DormRoom> findByAcademyIdAndYearAndDeletedFalseOrderByBuildingAscRoomNoAsc(
            Long academyId, short year);

    @Query("""
            SELECT r FROM DormRoom r JOIN FETCH r.academy
            WHERE r.id = :id AND r.deleted = false
            """)
    Optional<DormRoom> findDetailById(Long id);

    boolean existsByAcademyIdAndYearAndBuildingAndRoomNoAndDeletedFalse(
            Long academyId, short year, String building, String roomNo);
}
