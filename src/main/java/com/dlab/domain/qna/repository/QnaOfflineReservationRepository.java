package com.dlab.domain.qna.repository;

import com.dlab.domain.qna.entity.QnaOfflineReservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface QnaOfflineReservationRepository
        extends JpaRepository<QnaOfflineReservation, Long> {

    /** 슬롯의 유효 예약 수. 정원 비교의 기준이라 취소분은 세지 않는다. */
    long countBySlotIdAndCanceledAtIsNullAndDeletedFalse(Long slotId);

    /**
     * ★★ 그 학생이 <b>그 날짜</b>에 잡아 둔 유효 예약 수 (디랩 1029 8-1).
     *
     * <h2>★ 슬롯 날짜로 센다 — 신청 시각이 아니다</h2>
     * ⚠️ 신청 시각으로 세면 <b>밤 11시에 내일·모레 것을 몰아 잡을 수 있다.</b>
     *
     * <p>★ <b>취소·삭제분은 빼고 센다</b> — 취소한 자리는 비어 있으므로 상한에
     * 들어가면 <b>취소하고 다시 잡을 수 없다.</b>
     *
     * <p>★ <b>직원 대리분도 센다</b> — 세지 않는 것은 <b>상한 검사를 건너뛰는 쪽</b>이고
     * (대리 예약 경로), 여기는 <b>학생이 잡을 때 지금 몇 건인지</b>를 묻는 자리다.
     * ⚠️ 대리분을 빼면 직원이 두 건 넣어 둔 학생이 <b>또 두 건을 잡는다.</b>
     */
    @Query("""
            select count(r)
              from QnaOfflineReservation r
             where r.enrollment.id = :enrollmentId
               and r.slot.slotDate = :slotDate
               and r.canceledAt is null
               and r.deleted = false
            """)
    long countByEnrollmentAndDate(@Param("enrollmentId") Long enrollmentId,
                                  @Param("slotDate") java.time.LocalDate slotDate);

    /** 중복 예약 검사. 취소분은 재예약이 가능해야 하므로 제외한다. */
    Optional<QnaOfflineReservation> findBySlotIdAndEnrollmentIdAndCanceledAtIsNullAndDeletedFalse(
            Long slotId, Long enrollmentId);

    /** 슬롯별 예약자 명단 — 관리자 예약 현황. */
    @Query("""
            SELECT r FROM QnaOfflineReservation r
            JOIN FETCH r.enrollment e
            JOIN FETCH e.student
            WHERE r.slot.id IN :slotIds AND r.canceledAt IS NULL AND r.deleted = false
            ORDER BY r.reservedAt
            """)
    List<QnaOfflineReservation> findBySlotIds(@Param("slotIds") List<Long> slotIds);

    /** 학생 본인 예약 — 취소분도 이력으로 함께 나온다. */
    @Query("""
            SELECT r FROM QnaOfflineReservation r
            JOIN FETCH r.slot s
            LEFT JOIN FETCH s.teacher
            WHERE r.enrollment.id = :enrollmentId
              AND s.slotDate >= :from AND s.slotDate <= :to
              AND r.deleted = false
            ORDER BY s.slotDate DESC, s.startTime DESC
            """)
    List<QnaOfflineReservation> findMine(@Param("enrollmentId") Long enrollmentId,
                                         @Param("from") LocalDate from,
                                         @Param("to") LocalDate to);
}
