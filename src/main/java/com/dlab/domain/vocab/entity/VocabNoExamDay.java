package com.dlab.domain.vocab.entity;

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
 * 시험 없는 날 (v1 5.1).
 *
 * <p>디랩 원문: <i>"시험 없는 날(공휴일, 모의고사 등): 지점 관리자가 날짜로 지정한다.
 * 그날은 회차가 공개되지 않고, <b>진도는 다음 시험일로 밀린다.</b>"</i>
 *
 * <p>⚠️ <b>{@code holiday} 테이블을 쓰지 않는다.</b> 그건 급식 가능일용이라 법정공휴일이
 * 전부 들어가고, 여기 필요한 것은 <b>「모의고사 때문에 시험을 안 보는 날」</b>까지 포함한
 * 운영 지정이다 — 교습일수가 {@code holiday} 를 쓰지 않는 것과 같은 이유다.
 */
@Entity
@Table(name = "vocab_no_exam_day")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VocabNoExamDay extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(nullable = false)
    private Short year;

    @Column(name = "exam_date", nullable = false)
    private LocalDate examDate;

    @Column(length = 100)
    private String reason;

    public VocabNoExamDay(Academy academy, Short year, LocalDate examDate, String reason) {
        this.academy = academy;
        this.year = year;
        this.examDate = examDate;
        this.reason = reason;
    }
}
