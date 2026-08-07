package com.dlab.domain.qna.repository;

import com.dlab.domain.qna.entity.QnaOfflineSlot;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

public interface QnaOfflineSlotRepository extends JpaRepository<QnaOfflineSlot, Long> {

    /**
     * ★ 정원 확인 → 예약 사이를 잠근다.
     *
     * <p>"현재 인원 세기 → 정원 비교 → INSERT"는 동시 예약에 그대로 뚫린다.
     * 특강 정원에서 같은 처리를 했다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM QnaOfflineSlot s WHERE s.id = :id AND s.deleted = false")
    Optional<QnaOfflineSlot> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            SELECT s FROM QnaOfflineSlot s
            LEFT JOIN FETCH s.teacher
            WHERE s.academy.id = :academyId AND s.slotDate = :date AND s.deleted = false
            ORDER BY s.startTime, s.id
            """)
    List<QnaOfflineSlot> findByDate(@Param("academyId") Long academyId,
                                    @Param("date") LocalDate date);

    /** 개설 시 중복 확인. 같은 날 같은 시각에 같은 상담실을 두 번 열지 않는다. */
    boolean existsByAcademyIdAndSlotDateAndStartTimeAndRoomAndDeletedFalse(
            Long academyId, LocalDate slotDate, LocalTime startTime, String room);

    boolean existsByAcademyIdAndSlotDateAndStartTimeAndRoomIsNullAndDeletedFalse(
            Long academyId, LocalDate slotDate, LocalTime startTime);
}
