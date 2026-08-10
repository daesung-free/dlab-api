package com.dlab.domain.qna.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.Teacher;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 상담실 가능 타임 (F-4.11-7 대면 · A-13).
 *
 * <p><b>★ "간격"을 스키마에 두지 않는다.</b> ▷[0803] 회신이 *"현재 15분 간격 운영, 변동 가능"*이라
 * 했는데, 간격을 컬럼이나 상수로 두면 바뀔 때 마이그레이션이 필요해진다.
 * 슬롯을 <b>시작·종료 시각을 가진 행</b>으로 두고 개설 API가 간격을 받아 행을 여러 개 만든다 —
 * 간격이 바뀌어도 데이터만 달라진다.
 */
@Getter
@Entity
@Table(name = "qna_offline_slot")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QnaOfflineSlot extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(nullable = false)
    private short year;

    @Column(name = "slot_date", nullable = false)
    private LocalDate slotDate;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    /** 상담 담당. {@code null}이면 미배정 — 슬롯만 먼저 열고 담당을 나중에 정하는 운영이 있다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "teacher_id")
    private Teacher teacher;

    @Column(length = 50)
    private String room;

    /** 슬롯당 인원. 1:1 상담이면 1이다. */
    @Column(nullable = false)
    private short capacity = 1;

    /**
     * 새 예약 차단.
     *
     * <p><b>기존 예약은 유지된다</b> — 삭제하면 이미 예약한 학생의 기록이 사라진다.
     */
    @Column(nullable = false)
    private boolean closed = false;

    @Column(length = 200)
    private String memo;

    public QnaOfflineSlot(Academy academy, short year, LocalDate slotDate,
                          LocalTime startTime, LocalTime endTime,
                          Teacher teacher, String room, short capacity) {
        this.academy = academy;
        this.year = year;
        this.slotDate = slotDate;
        this.startTime = startTime;
        this.endTime = endTime;
        this.teacher = teacher;
        this.room = room;
        this.capacity = capacity;
    }

    public void changeClosed(boolean closed) {
        this.closed = closed;
    }

    public void assign(Teacher teacher, String room, String memo) {
        if (teacher != null) {
            this.teacher = teacher;
        }
        if (room != null) {
            this.room = room;
        }
        if (memo != null) {
            this.memo = memo;
        }
    }

    /** 이미 지난 타임인가. 지난 슬롯은 예약도 취소도 받지 않는다. */
    public boolean isPast(LocalDate today, LocalTime now) {
        return slotDate.isBefore(today)
                || (slotDate.isEqual(today) && startTime.isBefore(now));
    }
}
