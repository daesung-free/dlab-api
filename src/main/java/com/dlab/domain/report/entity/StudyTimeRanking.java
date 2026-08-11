package com.dlab.domain.report.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 순공시간 랭킹 (F-4.11-6, 앱 A-3).
 *
 * <h2>여기는 사전집계가 맞다</h2>
 * 통계 대시보드(F-4.11-11)는 관리자 몇 명이 가끔 보므로 실시간 집계로 뒀지만,
 * 랭킹은 <b>앱 홈이라 전교생이 매일 여러 번 연다</b>. 그때마다 전 지점 × 기간을
 * GROUP BY 하면 같은 답을 수천 번 다시 계산한다 — 값은 출결 확정 후 하루 한 번만 바뀐다.
 *
 * <p><b>순위를 저장한다.</b> 동점 처리를 배치 한 곳에서만 하면 화면마다 갈리지 않는다.
 */
@Getter
@Entity
@Table(name = "study_time_ranking")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudyTimeRanking extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** <b>전체 랭킹이면 {@code null}</b>이다. 지점 랭킹이면 그 지점. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "academy_id")
    private Academy academy;

    @Column(nullable = false)
    private short year;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    @Enumerated(EnumType.STRING)
    @Column(name = "period_type", nullable = false, length = 10)
    private RankingPeriod periodType;

    /** 기간의 시작일. 주간은 월요일, 월간은 1일. */
    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "study_minutes", nullable = false)
    private int studyMinutes;

    /** 1부터. 동점이면 같은 등수다(1, 1, 3). */
    @Column(name = "ranking", nullable = false)
    private int ranking;

    /**
     * @param enrollment <b>프록시로 넣는다</b> — 배치가 수천 건을 만드는데 실제로 읽으면
     *                   행마다 SELECT가 한 번씩 더 나간다. 그래서 {@code year}를 밖에서 받는다
     */
    public StudyTimeRanking(Academy academy, StudentEnrollment enrollment, short year,
                            RankingPeriod periodType, LocalDate periodStart,
                            int studyMinutes, int ranking) {
        this.academy = academy;
        this.enrollment = enrollment;
        this.year = year;
        this.periodType = periodType;
        this.periodStart = periodStart;
        this.studyMinutes = studyMinutes;
        this.ranking = ranking;
    }
}
