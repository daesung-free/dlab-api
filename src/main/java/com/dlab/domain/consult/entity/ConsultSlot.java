package com.dlab.domain.consult.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.Teacher;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 담임 상담 가능 일정 (F-4.11-4, 0803 답변서 신규).
 *
 * <p><b>주체가 담임이다.</b> 질의응답 대면 슬롯({@code QnaOfflineSlot})은 "상담실"이 주체라
 * 담당이 없을 수 있지만, 상담은 학생이 <b>자기 담임 슬롯만</b> 잡아야 한다 — 그래서
 * {@code teacher}가 필수다. FK가 곧 "담당선생님 보장"이라 배정마다 역할을 검사하지 않아도 된다.
 *
 * <p><b>간격을 스키마에 두지 않는다.</b> 개설 API가 시작·종료·간격을 받아 행을 여러 개 만든다 —
 * 간격이 바뀌어도 데이터만 달라진다.
 */
@Getter
@Entity
@Table(name = "consult_slot")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConsultSlot extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(nullable = false)
    private short year;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "teacher_id", nullable = false)
    private Teacher teacher;

    @Column(name = "slot_date", nullable = false)
    private LocalDate slotDate;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(nullable = false)
    private short capacity = 1;

    /**
     * 노출 여부.
     *
     * <p><b>기본이 꺼짐이다.</b> 요구사항이 "설정 및 노출"로 두 단계라, 만들자마자 보이면
     * 담임이 일정을 짜는 중간 상태가 그대로 학생에게 노출된다.
     *
     * <p><b>마감도 이 값을 내려서 한다.</b> 별도 {@code closed}를 두면 "안 켠 것"과 "닫은 것"이
     * 학생 화면에서 똑같이 안 보이는데 상태만 둘이 된다. 이미 잡힌 예약은 내려도 유효하다.
     */
    @Column(nullable = false)
    private boolean published = false;

    @Column(length = 50)
    private String place;

    @Column(length = 200)
    private String memo;

    public ConsultSlot(Academy academy, short year, Teacher teacher, LocalDate slotDate,
                       LocalTime startTime, LocalTime endTime, short capacity, String place) {
        this.academy = academy;
        this.year = year;
        this.teacher = teacher;
        this.slotDate = slotDate;
        this.startTime = startTime;
        this.endTime = endTime;
        this.capacity = capacity;
        this.place = place;
    }

    public void changePublished(boolean published) {
        this.published = published;
    }

    public void update(String place, String memo) {
        if (place != null) {
            this.place = place;
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
