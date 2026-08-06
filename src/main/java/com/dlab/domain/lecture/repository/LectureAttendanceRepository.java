package com.dlab.domain.lecture.repository;

import com.dlab.domain.lecture.entity.LectureAttendance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LectureAttendanceRepository extends JpaRepository<LectureAttendance, Long> {

    Optional<LectureAttendance> findBySessionIdAndApplicationId(Long sessionId, Long applicationId);

    List<LectureAttendance> findBySessionIdAndDeletedFalse(Long sessionId);
}
