package com.dlab.domain.user.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

/**
 * 지점.
 *
 * <p><b>최소 형태다.</b> 레거시 지점 스키마(DB_INFO.TB_ACADEMY_INFO)가 미확보 상태라
 * 키오스크 계약에 맞춘 확정은 V2에서 다른 담당자가 한다. 이 테이블만 V1에 남긴 이유는
 * {@code academy_id}를 대부분의 테이블이 FK로 참조하기 때문이다 — V1에서 빼면
 * 참조 무결성을 하나도 걸 수 없다.
 */
@Getter
@Entity
@Table(name = "academy")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Academy extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** DSA 호환 getDlabList 3필드. */
    @Column(name = "acad_cd", nullable = false, unique = true, length = 20)
    private String acadCd;

    @Column(name = "acad_nm", nullable = false, length = 100)
    private String acadNm;

    /** 등원 기준시각(지점 공통, 학생별 아님). 이 시각에 미등원 감지 배치가 돈다. */
    @Column(name = "attendance_deadline", nullable = false)
    private LocalTime attendanceDeadline;

    @Column(nullable = false)
    private boolean active;

    public Academy(String acadCd, String acadNm, LocalTime attendanceDeadline) {
        this.acadCd = acadCd;
        this.acadNm = acadNm;
        this.attendanceDeadline = attendanceDeadline;
        this.active = true;
    }

    /** 로그·알림에서 쓰는 표시용 이름. */
    public String getName() {
        return acadNm;
    }
}
