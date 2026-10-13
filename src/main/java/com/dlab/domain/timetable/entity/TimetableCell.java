package com.dlab.domain.timetable.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.master.entity.RoomMaster;
import com.dlab.domain.period.entity.PeriodMaster;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.ClassMaster;
import com.dlab.domain.user.entity.Teacher;
import jakarta.persistence.*;
import java.time.DayOfWeek;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 반 주간 시간표 한 칸 — 요일 × 교시 (F-C-3 「시간표·이동수업」).
 *
 * <p>화면은 있는데 <b>「학기 선택·전 학기 복사·편성 저장」이 전부 「준비 중」</b>이었다 —
 * 저장할 곳이 없었다. 교시({@code period_master})·커리큘럼은 있었지만
 * <b>이동수업(이동반·강의실·담당 선생님)을 담을 데가 없다.</b>
 *
 * <h2>★★ 강의실이 두 군데다</h2>
 * 반의 홈룸은 {@link ClassMaster#getRoom()}, 여기 {@link #room}은 <b>그 칸에서만 쓰는
 * 강의실</b>(이동수업)이다. <b>셀이 비면 홈룸으로 떨어지는 관계</b>이고, 그 결과가
 * {@link #effectiveRoom()}이다. 한쪽으로 합치면 둘 중 하나가 사라진다 — 홈룸을 지우면
 * 「이 반이 평소 어디 있나」를 잃고, 셀을 지우면 이동수업을 표현할 수 없다.
 *
 * <h2>★ 교시 번호를 적지 않고 교시 마스터를 참조한다</h2>
 * 번호만 들면 시작·종료 시각을 이 표가 다시 들어야 하고, 교시 시간을 고쳤을 때
 * <b>시간표만 옛 시각으로 남는다.</b>
 *
 * <h2>★ 축은 연도다</h2>
 * 「기수」라는 별도 축은 없다(2026-10-13 확인 — 우리 용어였고 실제로는 연도였다).
 *
 * <h2>⚠️ 학생별 이동수업 예외는 1차 범위가 아니다</h2>
 * 여기까지가 <b>반 단위 편성</b>이다. 「이 학생만 3교시에 다른 반으로 간다」는
 * <b>이 표 위에 덮는 축</b>이라 별 테이블(학생 × 칸 × 대체 반)로 붙어야 한다 —
 * 여기에 학생 컬럼을 끼워 넣으면 반 하나의 편성이 학생 수만큼 복제된다.
 */
@Getter
@Entity
@Table(name = "timetable_cell")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TimetableCell extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private short year;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "class_id", nullable = false)
    private ClassMaster classMaster;

    /**
     * 요일. MON~SAT 다 — <b>이 학원은 토요일에도 운영한다</b>(교시 마스터에 토요일 구성이
     * 따로 있다). 일요일은 교시가 없어 칸을 만들 수 없다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, length = 10)
    private DayOfWeek dayOfWeek;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "period_id", nullable = false)
    private PeriodMaster period;

    /**
     * 과목명.
     *
     * <p>★ <b>마스터를 두지 않고 문자열이다.</b> 과목 마스터가 아직 없고
     * ({@code exam_subject}는 모의고사 전용이라 다르다), 억측으로 미리 만들면 편성 화면이
     * 실제 과목이 없는 목록에 묶인다.
     */
    @Column(length = 100)
    private String subject;

    /** 담당 선생님. 과목만 먼저 깔고 담당을 나중에 붙이는 흐름이 있어 비어 있을 수 있다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "teacher_id")
    private Teacher teacher;

    /** 그 칸에서만 쓰는 강의실(이동수업). 비면 반 홈룸으로 떨어진다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id")
    private RoomMaster room;

    /** 전년도 복사 원본. NULL 이면 그 해에 직접 만든 칸이다({@code class_master} 와 같은 규약). */
    @Column(name = "copied_from_id")
    private Long copiedFromId;

    public TimetableCell(ClassMaster classMaster, DayOfWeek dayOfWeek, PeriodMaster period,
                         String subject, Teacher teacher, RoomMaster room) {
        this.classMaster = classMaster;
        this.academy = classMaster.getAcademy();
        this.year = classMaster.getYear();
        this.dayOfWeek = dayOfWeek;
        this.period = period;
        this.subject = subject;
        this.teacher = teacher;
        this.room = room;
    }

    /**
     * 칸 내용 교체.
     *
     * <p><b>{@code null}은 「안 바꿈」이 아니라 「지움」이다</b> — 편성 화면은 칸을 통째로
     * 보내므로, 선생님을 떼려면 빈 값으로 보내는 것이 유일한 길이다. 「안 보낸 건 유지」로
     * 두면 <b>담당 선생님을 한 번 넣으면 영영 뗄 수 없다.</b>
     *
     * <p>슬롯(반·요일·교시)은 바꾸지 않는다. 그건 다른 칸이다 — 옮기려면 지우고 새로 넣는다.
     */
    public void fill(String subject, Teacher teacher, RoomMaster room) {
        this.subject = subject;
        this.teacher = teacher;
        this.room = room;
    }

    /** 전년도 복사가 호출한다. 직접 만든 칸은 계속 NULL 이어야 한다. */
    public void markCopiedFrom(Long sourceId) {
        this.copiedFromId = sourceId;
    }

    /**
     * 비어 있는 칸인가 — 과목·선생님·강의실이 모두 없는 상태.
     *
     * <p>편성 화면이 칸을 지울 때 <b>내용을 비운 칸</b>을 보내므로, 그것을 「삭제」로 읽는
     * 판정이 필요하다. 상세는 {@code TimetableService.save} 참고.
     */
    public static boolean isBlank(String subject, Long teacherId, Long roomId) {
        return (subject == null || subject.isBlank()) && teacherId == null && roomId == null;
    }

    /**
     * ★ <b>실제 적용 강의실</b> — 셀 강의실이 비면 반 홈룸이다.
     *
     * <p>이 판정을 화면에 맡기면 <b>화면마다 다르게 떨어진다</b>(시간표 화면은 홈룸을
     * 알고 출석부 화면은 모른다). 서버가 한 곳에서 정하고 응답에 함께 실어 내린다.
     */
    public RoomMaster effectiveRoom() {
        return room != null ? room : classMaster.getRoom();
    }
}
