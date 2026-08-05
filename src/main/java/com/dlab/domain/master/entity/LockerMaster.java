package com.dlab.domain.master.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사물함.
 *
 * <p>배정 대상이 <b>등록 건</b>이다 — 그 해 배정이므로 기수가 바뀌면 다시 배정한다.
 * 좌석과 달리 이력 테이블 없이 컬럼 하나로 현재 배정만 들고 있다(V1 스키마).
 */
@Getter
@Entity
@Table(name = "locker_master")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LockerMaster extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(name = "locker_no", nullable = false, length = 20)
    private String lockerNo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_enrollment_id")
    private StudentEnrollment assignedEnrollment;

    public LockerMaster(Academy academy, String lockerNo) {
        this.academy = academy;
        this.lockerNo = lockerNo;
    }

    public void assign(StudentEnrollment enrollment) {
        this.assignedEnrollment = enrollment;
    }

    public void release() {
        this.assignedEnrollment = null;
    }

    public boolean isOccupied() {
        return assignedEnrollment != null;
    }
}
