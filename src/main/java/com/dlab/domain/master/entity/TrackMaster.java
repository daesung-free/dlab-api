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
 * (의도된 예외, docs/entity-design.md §0-1).
 */
@Getter
@Entity
@Table(name = "track_master")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TrackMaster extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String name;

    public TrackMaster(String name) {
        this.name = name;
    }

    public void rename(String name) {
        this.name = name;
    }
}
