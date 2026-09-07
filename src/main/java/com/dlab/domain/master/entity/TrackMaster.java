package com.dlab.domain.master.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 계열(인문/자연/예체/공통).
 *
 * <p><b>지점·연도가 없다</b> — 전 지점 공통 고정 참조값이라 전년도 복사 대상도 아니다
 * (의도된 예외, docs/entity-design.md §0-1). 그래서 {@link MasterAttributes}를 쓰면서도
 * 코드 유니크에 지점·연도 축이 없다.
 *
 * <p>⚠️ <b>이 표를 참조하는 곳이 아직 없다.</b> 학생 계열은
 * {@code StudentEnrollment.track}(enum {@code TrackType})이라 여기에 행을 더해도
 * 학생 쪽 선택지가 늘지 않는다. 요구사항정의서 오픈이슈 #51("계열 마스터가 기초 관리
 * 범위인지")이 그 건이다 — 확정되기 전까지 관리 화면용 표로만 쓴다.
 */
@Getter
@Entity
@Table(name = "track_master")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TrackMaster extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * ⚠️ {@code unique = true}를 붙이지 않는다 — 컬럼 레벨 유니크는 soft delete 된
     * 행까지 이름을 붙잡아, 지웠던 계열을 다시 만들 수 없게 된다. 제약은
     * {@code V20260907_1000}의 부분 인덱스가 건다.
     */
    @Column(nullable = false, length = 20)
    private String name;

    /** 코드 · 비고 · 사용여부. 다른 기초 마스터와 같은 세 칸이다. */
    @Embedded
    private MasterAttributes attributes = MasterAttributes.empty();

    public TrackMaster(String name) {
        this.name = name;
    }

    public TrackMaster(String name, String code, String memo) {
        this.name = name;
        this.attributes = new MasterAttributes(code, memo);
    }

    public void rename(String name) {
        this.name = name;
    }

    public String getCode() {
        return attributes.getCode();
    }

    public String getMemo() {
        return attributes.getMemo();
    }

    public boolean isActive() {
        return attributes.isActive();
    }

    /** {@code null}은 "안 바꿈"이 아니라 "지움"이다 — 화면은 항상 현재 값을 실어 보낸다. */
    public void updateAttributes(String code, String memo) {
        this.attributes.update(code, memo);
    }

    public void changeActive(boolean active) {
        this.attributes.changeActive(active);
    }
}
