package com.dlab.domain.lecture.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 특강·설명회 마스터 (F-4.10-4 기초 설정).
 *
 * <p>결제는 붙어 있지 않다 — 0803 답변서가 *"특강 신청+결제 가능 여부 검토 중"*이고
 * {@code payment} 도메인 자체가 없다. {@link #fee}는 <b>안내용</b>이다.
 */
@Getter
@Entity
@Table(name = "lecture")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Lecture extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(nullable = false)
    private short year;

    @Enumerated(EnumType.STRING)
    @Column(name = "lecture_type", nullable = false, length = 20)
    private LectureType lectureType = LectureType.LECTURE;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * 앱 노출 여부 (0803 "개설 시에만 노출").
     * <b>{@link #status}와 다른 축</b>이다 — 접수를 닫아도 목록에는 보일 수 있다.
     */
    @Column(nullable = false)
    private boolean visible = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LectureStatus status = LectureStatus.DRAFT;

    /** {@code null}이면 무제한. 설명회는 정원을 안 두는 경우가 있다. */
    private Integer capacity;

    @Column(name = "apply_from")
    private Instant applyFrom;

    @Column(name = "apply_to")
    private Instant applyTo;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    /** ⚠️ 안내용. 이 값으로 수납이 일어나지 않는다. */
    @Column(nullable = false)
    private int fee = 0;

    public Lecture(Academy academy, short year, LectureType lectureType, String name) {
        this.academy = academy;
        this.year = year;
        this.lectureType = lectureType == null ? LectureType.LECTURE : lectureType;
        this.name = name;
    }

    /** {@code null}은 "변경하지 않음"이다. */
    public void update(String name, String description, Integer capacity,
                       Instant applyFrom, Instant applyTo,
                       LocalDate startDate, LocalDate endDate, Integer fee) {
        if (name != null) {
            this.name = name;
        }
        if (description != null) {
            this.description = description;
        }
        if (capacity != null) {
            this.capacity = capacity;
        }
        if (applyFrom != null) {
            this.applyFrom = applyFrom;
        }
        if (applyTo != null) {
            this.applyTo = applyTo;
        }
        if (startDate != null) {
            this.startDate = startDate;
        }
        if (endDate != null) {
            this.endDate = endDate;
        }
        if (fee != null) {
            this.fee = fee;
        }
    }

    public void changeStatus(LectureStatus status) {
        this.status = status;
    }

    public void changeVisible(boolean visible) {
        this.visible = visible;
    }

    /** 정원 없음 = 무제한. */
    public boolean hasCapacity() {
        return capacity != null;
    }

    /**
     * 지금 신청을 받는가.
     *
     * <p>세 가지를 <b>모두</b> 본다 — 상태가 {@code OPEN}이고, 접수 기간 안이고, 삭제되지 않았을 것.
     * 접수 기간을 안 보면 마감된 특강에 계속 신청이 들어온다.
     */
    public boolean acceptsApplicationAt(Instant now) {
        if (isDeleted() || !status.acceptsApplication()) {
            return false;
        }
        if (applyFrom != null && now.isBefore(applyFrom)) {
            return false;
        }
        return applyTo == null || !now.isAfter(applyTo);
    }
}
