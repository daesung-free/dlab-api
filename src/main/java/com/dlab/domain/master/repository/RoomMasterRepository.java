package com.dlab.domain.master.repository;

import com.dlab.domain.master.entity.RoomMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface RoomMasterRepository extends JpaRepository<RoomMaster, Long> {

    /**
     * 지점의 강의실 전체. <b>중지된 것도 나온다</b> — 관리 화면이 상태를 보고 되살릴 수 있어야 한다.
     *
     * <p>번호를 문자열로 정렬하므로 {@code "10"}이 {@code "2"}보다 앞에 온다.
     * 실제 방 번호가 세 자리로 통일돼 있어("201"·"301") 지금은 문제가 안 되지만,
     * 한 자리 방이 섞이면 화면에서 정렬 기준을 따로 잡아야 한다.
     */
    @Query("""
            SELECT r FROM RoomMaster r
            WHERE r.academy.id = :academyId AND r.deleted = false
            ORDER BY r.roomNo ASC
            """)
    List<RoomMaster> findByAcademyId(Long academyId);

    @Query("""
            SELECT r FROM RoomMaster r
            JOIN FETCH r.academy
            WHERE r.id = :id AND r.deleted = false
            """)
    Optional<RoomMaster> findDetailById(Long id);

    boolean existsByAcademyIdAndRoomNoAndDeletedFalse(Long academyId, String roomNo);
}
