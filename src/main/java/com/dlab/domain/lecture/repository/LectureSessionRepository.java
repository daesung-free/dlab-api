package com.dlab.domain.lecture.repository;

import com.dlab.domain.lecture.entity.LectureSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LectureSessionRepository extends JpaRepository<LectureSession, Long> {

    List<LectureSession> findByLectureIdAndDeletedFalseOrderBySessionNo(Long lectureId);

    /** 다음 회차 번호 계산용. 행이 없으면 0 — 첫 회차다. */
    @org.springframework.data.jpa.repository.Query("""
            SELECT COALESCE(MAX(s.sessionNo), 0) FROM LectureSession s
            WHERE s.lecture.id = :lectureId AND s.deleted = false
            """)
    short findMaxSessionNo(@org.springframework.data.repository.query.Param("lectureId") Long lectureId);
}
