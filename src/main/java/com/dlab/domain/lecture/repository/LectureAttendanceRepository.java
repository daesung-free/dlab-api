package com.dlab.domain.lecture.repository;

import com.dlab.domain.lecture.entity.LectureAttendance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LectureAttendanceRepository extends JpaRepository<LectureAttendance, Long> {

    Optional<LectureAttendance> findBySessionIdAndApplicationId(Long sessionId, Long applicationId);

    List<LectureAttendance> findBySessionIdAndDeletedFalse(Long sessionId);

    /** 회차 삭제 가능 여부 — 출결이 찍혔으면 그날 기록이 사라지므로 막는다. */
    boolean existsBySessionIdAndDeletedFalse(Long sessionId);
}
