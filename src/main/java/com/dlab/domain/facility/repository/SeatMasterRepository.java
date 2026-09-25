package com.dlab.domain.facility.repository;

import com.dlab.domain.facility.entity.SeatMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SeatMasterRepository extends JpaRepository<SeatMaster, Long> {

    @Query("""
            SELECT s FROM SeatMaster s
            JOIN FETCH s.studyArea
            JOIN FETCH s.academy
            WHERE s.id = :id AND s.deleted = false
            """)
    Optional<SeatMaster> findDetailById(Long id);

    @Query("""
            SELECT s FROM SeatMaster s
            WHERE s.studyArea.id = :studyAreaId AND s.deleted = false
            ORDER BY s.yPos ASC, s.xPos ASC
            """)
    List<SeatMaster> findByStudyAreaId(Long studyAreaId);

    /**
     * ★ 키오스크 전용 — {@code kiosk_seat_cd}로 찾는다.
     *
     * <p>단말은 좌석 변경({@code setSeatChgProc})에서 <b>구역 없이 좌석번호만</b> 보낸다.
     * 그래서 지점 안에서 유일해야 하고, 그 유일성을 보장하는 값이 우리 {@code seat_cd}가
     * 아니라 관까지 반영된 {@code kiosk_seat_cd}다.
     */
    @Query("""
            SELECT s FROM SeatMaster s
            WHERE s.academy.id = :academyId
              AND s.kioskSeatCd = :kioskSeatCd
              AND s.deleted = false
            """)
    java.util.Optional<SeatMaster> findByAcademyIdAndKioskSeatCd(Long academyId,
                                                                 String kioskSeatCd);

    /**
     * 지운 좌석까지 포함해 <b>구역 안에서</b> 코드로 찾는다.
     *
     * <p>등록 전에 이걸로 확인하지 않으면 DB 제약 위반이 그대로 500으로 올라가고, 화면에는
     * 이유가 안 보인다. 살아 있는 행이 먼저 오도록 정렬한다 — 삭제분이 여러 개 쌓여 있을 수
     * 있고, 그중 무엇이 잡히느냐에 따라 되살릴지 새로 만들지가 갈린다.
     */
    @Query("""
            SELECT s FROM SeatMaster s
            WHERE s.studyArea.id = :studyAreaId AND s.seatCd IN :seatCds
            ORDER BY s.deleted ASC, s.id DESC
            """)
    List<SeatMaster> findAnyByStudyAreaIdAndSeatCdIn(Long studyAreaId, List<String> seatCds);

    /**
     * 키오스크 코드가 이미 쓰이고 있는지 — <b>지점 전체</b>에서 본다.
     *
     * <p>별관 1번의 변환 결과(1001)가 본관에 실재하는 1001번과 겹칠 수 있다. 제약이 막아
     * 주기는 하지만 격자로 40석을 넣다가 중간에 터지면 <b>어디까지 들어갔는지 알 수 없다</b>.
     */
    @Query("""
            SELECT s FROM SeatMaster s
            JOIN FETCH s.studyArea a
            WHERE s.academy.id = :academyId
              AND s.kioskSeatCd IN :kioskSeatCds
              AND s.deleted = false
            """)
    List<SeatMaster> findByAcademyIdAndKioskSeatCdIn(Long academyId, List<String> kioskSeatCds);

    /** 구역에 남아 있는 좌석 수. 구역 삭제 가능 여부 판정용. */
    @Query("""
            SELECT COUNT(s) FROM SeatMaster s
            WHERE s.studyArea.id = :studyAreaId AND s.deleted = false
            """)
    long countByStudyAreaId(Long studyAreaId);
}
