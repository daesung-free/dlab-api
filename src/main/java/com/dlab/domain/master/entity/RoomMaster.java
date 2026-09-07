package com.dlab.domain.master.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 강의실 — 수업이 이루어지는 공간 (F-4.10-1 기초관리).
 *
 * <h2>★ 자습 구역({@code study_area})과 다르다</h2>
 * 키오스크 백엔드가 자습 구역을 "독서실 구역(강의실)"이라고 부르지만 <b>별개다</b>.
 * 자습 구역은 좌석이 속하는 단위이고 키오스크 계약({@code getStudyAreaInfo})에 걸려 있다.
 * 여기는 <b>키오스크와 무관</b>하다 — 합치면 좌석 배정이 강의실에 딸려온다.
 *
 * <h2>연도가 없다</h2>
 * 물리 공간이라 기수가 바뀌어도 그대로다. {@link LockerMaster}와 같은 이유로
 * 전년도 복사 대상이 아니다.
 *
 * <h2>{@code code}를 두지 않는다</h2>
 * {@code roomNo}("201")가 이미 그 역할이다. 둘을 같이 두면 데스크가 어느 쪽으로 방을
 * 부르는지 갈린다 — 다른 마스터는 이름이 바뀌어서 코드가 필요하지만 방 번호는 안 바뀐다.
 */
@Getter
@Entity
@Table(name = "room_master")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoomMaster extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    /** 방 번호("201"). 지점 안에서 유일하다. */
    @Column(name = "room_no", nullable = false, length = 20)
    private String roomNo;

    /** 별칭("대강의실"). 번호만으로 안 통하는 방이 있어 선택으로 둔다. */
    @Column(name = "name", length = 50)
    private String name;

    /** 수용 인원. 모르면 비운다 — 0을 넣으면 "정원 0명"과 구분되지 않는다. */
    @Column(name = "capacity")
    private Short capacity;

    @Column(name = "memo", length = 200)
    private String memo;

    /**
     * 공사·용도변경으로 한동안 못 쓰는 방. {@code false}면 새로 배정할 수 없다.
     *
     * <p><b>삭제와 다르다</b> — 지우면 그 방에서 진행됐던 특강 기록의 근거가 끊긴다.
     */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    public RoomMaster(Academy academy, String roomNo, String name, Short capacity, String memo) {
        this.academy = academy;
        this.roomNo = roomNo;
        this.name = name;
        this.capacity = capacity;
        this.memo = memo;
    }

    public void update(String roomNo, String name, Short capacity, String memo) {
        this.roomNo = roomNo;
        this.name = name;
        this.capacity = capacity;
        this.memo = memo;
    }

    public void changeActive(boolean active) {
        this.active = active;
    }
}
