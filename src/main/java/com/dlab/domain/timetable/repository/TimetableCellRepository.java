package com.dlab.domain.timetable.repository;

import com.dlab.domain.timetable.entity.TimetableCell;
import java.time.DayOfWeek;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TimetableCellRepository extends JpaRepository<TimetableCell, Long> {

    /**
     * 그 반의 한 주. 편성 화면이 표를 한 번에 그린다.
     *
     * <p>강의실·홈룸·선생님을 함께 당겨온다 — 「실제 적용 강의실」(셀 ∅ → 홈룸)을 칸마다
     * 계산해야 해서, 없으면 칸 수만큼 쿼리가 더 돈다.
     */
    @Query("""
            SELECT c FROM TimetableCell c
            JOIN FETCH c.period
            JOIN FETCH c.classMaster cm
            LEFT JOIN FETCH cm.room
            LEFT JOIN FETCH c.teacher
            LEFT JOIN FETCH c.room
            WHERE c.classMaster.id = :classId AND c.deleted = false
            ORDER BY c.dayOfWeek ASC, c.period.startTime ASC
            """)
    List<TimetableCell> findByClass(@Param("classId") Long classId);

    /**
     * 그 지점·연도 전체 — 강의실 겹침을 되짚거나 복사 원본을 통째로 읽을 때 쓴다.
     *
     * <p>반을 비우면 전 반이다.
     */
    @Query("""
            SELECT c FROM TimetableCell c
            JOIN FETCH c.period
            JOIN FETCH c.classMaster cm
            LEFT JOIN FETCH cm.room
            LEFT JOIN FETCH c.teacher
            LEFT JOIN FETCH c.room
            WHERE c.academy.id = :academyId AND c.year = :year AND c.deleted = false
            ORDER BY cm.name ASC, c.dayOfWeek ASC, c.period.startTime ASC
            """)
    List<TimetableCell> findByYear(@Param("academyId") Long academyId,
                                   @Param("year") short year);

    /**
     * 그 반의 그 칸.
     *
     * <p>★ <b>지워진 칸도 찾는다({@code deleted} 조건이 없다).</b> 유니크 인덱스가
     * {@code WHERE is_deleted = FALSE} 라 지운 칸을 두고 같은 슬롯을 새로 넣으면 제약은
     * 통과하지만 <b>같은 슬롯의 행이 둘 쌓인다</b>. 비웠다가 다시 채우는 것은 편성 화면의
     * 일상이라, 기존 행을 되살려 쓴다({@code BaseEntity.restore()}).
     */
    @Query("""
            SELECT c FROM TimetableCell c
            WHERE c.classMaster.id = :classId
              AND c.dayOfWeek = :dayOfWeek
              AND c.period.id = :periodId
            """)
    Optional<TimetableCell> findSlot(@Param("classId") Long classId,
                                     @Param("dayOfWeek") DayOfWeek dayOfWeek,
                                     @Param("periodId") Long periodId);

    /**
     * 같은 시간 같은 강의실을 이미 쓰는 칸.
     *
     * <p>★ <b>DB 유니크({@code uq_timetable_room})가 최종 방어선이고 이건 안내용이다.</b>
     * 제약 위반 메시지에는 <b>어느 반이 먼저 쓰고 있는지가 없어서</b>, 담당자가 무엇을
     * 고쳐야 할지 알 수 없다. 그래서 저장 전에 먼저 찾아 반 이름을 담아 돌려준다.
     */
    @Query("""
            SELECT c FROM TimetableCell c
            JOIN FETCH c.classMaster
            WHERE c.year = :year
              AND c.period.id = :periodId
              AND c.dayOfWeek = :dayOfWeek
              AND c.room.id = :roomId
              AND c.classMaster.id <> :exceptClassId
              AND c.deleted = false
            """)
    List<TimetableCell> findRoomConflict(@Param("year") short year,
                                         @Param("periodId") Long periodId,
                                         @Param("dayOfWeek") DayOfWeek dayOfWeek,
                                         @Param("roomId") Long roomId,
                                         @Param("exceptClassId") Long exceptClassId);

    /**
     * 같은 시간 같은 선생님을 이미 쓰는 칸.
     *
     * <p>⚠️ <b>DB 제약으로 두지 않았다.</b> 강의실은 물리적으로 하나지만 선생님 쪽은
     * 참관·보조·합반 같은 운영 예외가 있는지 확인되지 않았다(§4). DB 로 막으면 그 예외가
     * 나타났을 때 <b>저장 자체가 불가능해지고 되돌리려면 마이그레이션이 필요하다</b> —
     * 서비스에서 막으면 정책이 확정될 때 코드 한 줄이다.
     */
    @Query("""
            SELECT c FROM TimetableCell c
            JOIN FETCH c.classMaster
            WHERE c.year = :year
              AND c.period.id = :periodId
              AND c.dayOfWeek = :dayOfWeek
              AND c.teacher.id = :teacherId
              AND c.classMaster.id <> :exceptClassId
              AND c.deleted = false
            """)
    List<TimetableCell> findTeacherConflict(@Param("year") short year,
                                            @Param("periodId") Long periodId,
                                            @Param("dayOfWeek") DayOfWeek dayOfWeek,
                                            @Param("teacherId") Long teacherId,
                                            @Param("exceptClassId") Long exceptClassId);
}
