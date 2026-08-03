package com.dlab.domain.user.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 학부모. 자녀 수만큼 계정을 나누지 않고 계정 1개에 자녀 여러 명을 연결한다.
 *
 * <p>{@code academyId}가 없는 것은 의도된 예외다 — 학부모는 지점 종속이 아니라 자녀를 따라간다.
 */
@Getter
@Entity
@Table(name = "parent_guardian")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ParentGuardian extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String name;

    @Column(nullable = false, unique = true, length = 20)
    private String phone;

    /** 부/모 구분 (DSA 호환 getParentHpList의 p_gb 대응). */
    @Column(length = 1)
    private String gender;

    public ParentGuardian(String name, String phone, String gender) {
        this.name = name;
        this.phone = phone;
        this.gender = gender;
    }
}
