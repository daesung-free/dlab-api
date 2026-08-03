package com.dlab.domain.user.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 학생 — <b>사람</b> 쪽 (영구, 연도 무관).
 *
 * <p>학번·RFID는 여기 없다. 그것들은 {@link StudentEnrollment}(등록 건)에 붙는다.
 * 독학재수라 1년 단위 코호트이므로 <b>연도를 넘는 학생 연속성을 가정하지 말 것</b>.
 * 대신 삼수로 재등록하면 같은 사람에 등록 행만 추가되므로 동일인 추적이 공짜로 된다.
 *
 * <p>{@code academyId}·{@code year}가 없는 것은 의도된 예외다 — 지점과 연도는 등록 건의 속성이다.
 */
@Getter
@Entity
@Table(name = "student")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Student extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 학부모 자녀연결용 고유ID. 마이페이지에 상시 노출된다.
     * 사람에 붙으므로 재등록해도 바뀌지 않는다.
     * 이 값을 아는 사람이면 본인확인 없이 연결 가능하다 — 클라이언트가 감수한 부분이라
     * 추가 검증 로직을 임의로 만들지 말 것.
     */
    @Column(name = "unique_code", nullable = false, unique = true, length = 20)
    private String uniqueCode;

    @Column(nullable = false, length = 20)
    private String name;

    @Column(length = 20)
    private String phone;

    /** 암호화 여부 미정 — 동명이인 구분 조회조건으로 쓰이면 인덱스를 못 탄다. */
    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(length = 1)
    private String gender;

    @Column(name = "school_name", length = 64)
    private String schoolName;

    @Column(name = "search_name_normalized", length = 20)
    private String searchNameNormalized;

    public Student(String uniqueCode, String name, String phone) {
        this.uniqueCode = uniqueCode;
        this.name = name;
        this.phone = phone;
    }
}
