package com.dlab.domain.university.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 대학 마스터 (디랩 1021 피드백 6-1).
 *
 * <p>⚠️ <b>캠퍼스는 이름에 들어 있다</b> — 「가천대(글로벌-성남)」·「연세대(미래-원주)」·
 * 「한양대(ERICA-안산)」. 별도 캠퍼스 축을 두지 말 것.
 *
 * <p>⚠️ <b>코드는 문자열이다</b> — {@code '001'} 처럼 0으로 채워져 있어 숫자로 읽으면
 * 앞의 0이 날아간다.
 *
 * <p>★ 연도별로 파일이 새로 온다(원본 파일명이 {@code 2026더프모...20260223}).
 * 그래서 {@code (year, code)} 가 키다 — {@code academy_id} 가 없는 <b>전 지점 공통</b>
 * 마스터다.
 */
@Entity
@Table(name = "university")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class University extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Short year;

    @Column(nullable = false, length = 10)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    public University(Short year, String code, String name) {
        this.year = year;
        this.code = code;
        this.name = name;
    }

    /** 같은 코드의 이름이 바뀐 경우에만 쓴다 — 재업로드 때 행을 늘리지 않는다. */
    public void rename(String name) {
        this.name = name;
    }
}
