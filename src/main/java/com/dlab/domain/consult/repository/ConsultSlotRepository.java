package com.dlab.domain.consult.repository;

import com.dlab.domain.consult.entity.ConsultSlot;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConsultSlotRepository extends JpaRepository<ConsultSlot, Long> {

    /**
     * 정원 검사를 직렬화하기 위한 행 락.
     *
     * <p>"인원 세기 → 정원 비교 → INSERT"는 동시 예약에 뚫린다 — 특강 정원과 같은 처리다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ConsultSlot s WHERE s.id = :id AND s.deleted = false")
    Optional<ConsultSlot> findByIdForUpdate(@Param("id") Long id);

    /** 담임이 보는 자기 일정. 노출 전 슬롯도 함께 나온다. */
    @Query("""
            SELECT s FROM ConsultSlot s
            WHERE s.teacher.id = :teacherId
              AND s.slotDate BETWEEN :from AND :to
              AND s.deleted = false
            ORDER BY s.slotDate, s.startTime
            """)
    List<ConsultSlot> findByTeacher(@Param("teacherId") Long teacherId,
                                    @Param("from") LocalDate from,
                                    @Param("to") LocalDate to);

    /**
     * 학생이 보는 슬롯 — <b>자기 담임 것이면서 노출된 것만</b>.
     *
     * <p>담임 조건을 빼면 아무 담임에게나 예약이 걸리고, 노출 조건을 빼면 아직 정리 중인
     * 일정이 학생에게 보인다.
     */
    @Query("""
            SELECT s FROM ConsultSlot s
            WHERE s.teacher.id = :teacherId
              AND s.published = true
              AND s.slotDate BETWEEN :from AND :to
              AND s.deleted = false
            ORDER BY s.slotDate, s.startTime
            """)
    List<ConsultSlot> findPublished(@Param("teacherId") Long teacherId,
                                    @Param("from") LocalDate from,
                                    @Param("to") LocalDate to);

    boolean existsByTeacherIdAndSlotDateAndStartTimeAndDeletedFalse(
            Long teacherId, LocalDate slotDate, java.time.LocalTime startTime);
}
