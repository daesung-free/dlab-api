package com.dlab.domain.master.repository;

import com.dlab.domain.master.entity.DormRoom;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
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

    /**
     * 배정 직전에 방 행을 <b>잠근다</b>({@code SELECT ... FOR UPDATE}).
     *
     * <p><b>★ 정원 검사는 "세고 나서 넣는" 방식이라 DB 제약으로 막을 수가 없다</b> —
     * 좌석·사물함은 "1칸 1명"이라 부분 유니크로 끝나지만, 정원 N은 표현할 제약이 없다.
     * 잠그지 않으면 동시 요청이 전부 "아직 자리 있음"을 보고 통과한다.
     * 실제로 정원 2명인 방에 <b>8명이 들어갔다</b>({@code ConcurrencyTest}).
     *
     * <p>애플리케이션 락이 아니라 DB 락이라 <b>다중 인스턴스에서도 유효</b>하다.
     * 방 단위 잠금이라 다른 방 배정은 서로 막지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM DormRoom r WHERE r.id = :id AND r.deleted = false")
    Optional<DormRoom> findByIdForUpdate(Long id);

    boolean existsByAcademyIdAndYearAndBuildingAndRoomNoAndDeletedFalse(
            Long academyId, short year, String building, String roomNo);
}
