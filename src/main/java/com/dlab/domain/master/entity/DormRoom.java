package com.dlab.domain.master.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 기숙사 방.
 *
 * <p><b>사물함과 구조가 다르다.</b> 사물함은 1칸에 1명이라 {@code locker_master}에 배정 컬럼을
 * 직접 뒀지만, 기숙사는 한 방에 여러 명이 들어가므로 배정을 {@link DormAssignment}로 뺐다.
 * 방에 배정 컬럼을 두면 정원만큼 컬럼을 만들거나 방을 인원수만큼 쪼개야 한다.
 */
@Getter
@Entity
@Table(name = "dorm_room")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DormRoom extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(name = "year", nullable = false)
    private short year;

    @Column(length = 30)
    private String building;

    @Column(name = "room_no", nullable = false, length = 20)
    private String roomNo;

    @Column(nullable = false)
    private short capacity;

    /** 배정 가능 성별. {@code null}이면 제한 없음 — 혼숙 배정을 코드가 아니라 데이터로 막는다. */
    @Column(length = 1)
    private String gender;

    public DormRoom(Academy academy, short year, String building, String roomNo,
                    short capacity, String gender) {
        this.academy = academy;
        this.year = year;
        this.building = building;
        this.roomNo = roomNo;
        this.capacity = capacity;
        this.gender = gender;
    }

    public void updateRoom(String building, String roomNo, Short capacity, String gender) {
        if (building != null) {
            this.building = building;
        }
        if (roomNo != null) {
            this.roomNo = roomNo;
        }
        if (capacity != null) {
            this.capacity = capacity;
        }
        if (gender != null) {
            this.gender = gender;
        }
    }

    /** 이 방에 그 성별을 넣을 수 있는가. 방에 제한이 없으면 누구든 된다. */
    public boolean accepts(String studentGender) {
        return gender == null || gender.equalsIgnoreCase(studentGender);
    }
}
