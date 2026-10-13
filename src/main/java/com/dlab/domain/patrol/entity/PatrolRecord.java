package com.dlab.domain.patrol.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 순찰 기록 한 건 (2026-10-13 디랩 피드백 1-3).
 *
 * <p>지금은 <b>학생 정보를 종이에 인쇄해 체크한 뒤 DSA 에 다시 입력</b>한다 — 같은 일을
 * 두 번 한다. 태블릿에서 좌석을 눌러 바로 넣는다.
 *
 * <h2>★★ 좌석이 아니라 학생에 붙인다</h2>
 * 좌석은 중간에 바뀌는데(재배치) 순찰 기록은 <b>그때 그 학생</b>의 상태다. 좌석으로만
 * 남기면 <b>재배치 뒤 기록이 엉뚱한 학생에게 붙는다.</b>
 *
 * <h2>★ 회차를 합치지 않는다</h2>
 * 같은 학생이 1회차에 「졸음」, 3회차에 「화장실」일 수 있다. 한 벌로 들면
 * <b>마지막 순찰만 남는다.</b>
 */
@Getter
@Entity
@Table(name = "patrol_mark")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PatrolRecord extends BaseEntity {

    /** 회차는 1~5다(요구사항 29쪽). DB CHECK 와 같은 값이어야 한다 */
    public static final short MIN_ROUND = 1;
    public static final short MAX_ROUND = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private short year;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(name = "patrol_date", nullable = false)
    private LocalDate patrolDate;

    @Column(name = "round_no", nullable = false)
    private short roundNo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    /**
     * 찍은 시점의 좌석번호.
     *
     * <p>★ <b>참조가 아니라 기록이다</b> — 「그때 어느 자리였나」를 남긴다. 좌석이 지워지거나
     * 재배치돼도 순찰 기록은 그대로 남아야 하므로 FK 를 걸지 않는다.
     */
    @Column(name = "seat_cd", length = 20)
    private String seatCd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PatrolMark mark;

    public PatrolRecord(StudentEnrollment enrollment, LocalDate patrolDate, short roundNo,
                        String seatCd, PatrolMark mark) {
        this.enrollment = enrollment;
        this.academy = enrollment.getAcademy();
        this.year = enrollment.getYear();
        this.patrolDate = patrolDate;
        this.roundNo = roundNo;
        this.seatCd = seatCd;
        this.mark = mark;
    }

    /**
     * 다시 찍었다.
     *
     * <p>★ <b>덮어쓴다.</b> 순찰을 다시 돌 수 있고, 두 행이 남으면 「그 회차에 무엇이었나」에
     * 답이 둘이 된다. 좌석도 갱신한다 — 그 사이 자리를 옮겼을 수 있다.
     */
    public void remark(PatrolMark mark, String seatCd) {
        this.mark = mark;
        this.seatCd = seatCd;
    }
}
