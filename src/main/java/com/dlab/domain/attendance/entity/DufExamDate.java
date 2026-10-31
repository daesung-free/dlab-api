package com.dlab.domain.attendance.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * ★★ 더프리미엄 모의고사 <b>응시일</b> (디랩 1029 4-4).
 *
 * <h2>★ 쓰임은 둘이다</h2>
 * ① 더프 조퇴 신청을 <b>그 날에만</b> 받는다 — ⚠️ 응시일이 아닌 날에 신청하면
 * <b>일반 사유 조퇴로 안내</b>한다.<br>
 * ② <b>전일(-1)·당일(-2)</b> 벌점을 가르는 기준이다.
 *
 * <h2>★ {@code academy} 가 비면 전 지점 공통</h2>
 * 1029 지침: <b>「지점별인지 전국 공통인지는 디랩에 안 물었습니다 — 지점 nullable 로
 * 두면 둘 다 받습니다」</b>
 *
 * <p>★ <b>더프는 월례고사</b>라 매월 있다 — {@code note} 에 회차 이름을 적어 두면
 * 데스크가 목록에서 고르기 쉽다.
 */
@Entity
@Getter
@Table(name = "duf_exam_date")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DufExamDate extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ★ 비면 전 지점 공통. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "academy_id")
    private Academy academy;

    @Column(name = "year", nullable = false)
    private short year;

    @Column(name = "exam_date", nullable = false)
    private LocalDate examDate;

    @Column(length = 100)
    private String note;

    public DufExamDate(Academy academy, short year, LocalDate examDate, String note) {
        this.academy = academy;
        this.year = year;
        this.examDate = examDate;
        this.note = note;
    }

    public void change(LocalDate examDate, String note) {
        this.examDate = examDate;
        this.note = note;
    }
}
