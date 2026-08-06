package com.dlab.domain.lecture.repository;

import com.dlab.domain.lecture.entity.Lecture;
import com.dlab.domain.lecture.entity.LectureStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LectureRepository extends JpaRepository<Lecture, Long> {

    /**
     * ★ 정원 확인 → 신청 사이를 잠근다.
     *
     * <p>"현재 인원 세기 → 정원과 비교 → INSERT"는 <b>동시 신청에 그대로 뚫린다.</b>
     * 실제로 같은 유형의 결함을 기숙사 정원에서 겪었다(2인실에 8명이 들어갔다).
     * 애플리케이션 락은 다중 인스턴스에서 무의미하므로 행 락으로 직렬화한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM Lecture l WHERE l.id = :id AND l.deleted = false")
    Optional<Lecture> findByIdForUpdate(@Param("id") Long id);

    List<Lecture> findByAcademyIdAndYearAndDeletedFalseOrderByStartDateDescIdDesc(
            Long academyId, short year);

    List<Lecture> findByAcademyIdAndYearAndStatusAndDeletedFalseOrderByStartDateDescIdDesc(
            Long academyId, short year, LectureStatus status);

    /**
     * 앱 목록 — <b>노출 설정된 것만</b>(0803 "개설 시에만 노출").
     *
     * <p>{@code DRAFT}·{@code CANCELED}는 제외한다. 준비 중인 특강이 앱에 뜨면
     * 학생이 신청하려다 거절당한다.
     */
    @Query("""
            SELECT l FROM Lecture l
            WHERE l.academy.id = :academyId AND l.year = :year
              AND l.deleted = false AND l.visible = true
              AND l.status IN (com.dlab.domain.lecture.entity.LectureStatus.OPEN,
                               com.dlab.domain.lecture.entity.LectureStatus.CLOSED,
                               com.dlab.domain.lecture.entity.LectureStatus.DONE)
            ORDER BY l.startDate DESC, l.id DESC
            """)
    List<Lecture> findVisible(@Param("academyId") Long academyId, @Param("year") short year);
}
