package com.dlab.domain.payment.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 교습비 가격 마스터 (지점 × 연도 × 학년 × 좌석유형).
 *
 * <h2>★ 교습비와 독서실비를 한 값으로 합치지 않는다</h2>
 * 750,000원은 <b>660,000(교습비) + 90,000(독서실비)</b>이고 둘은 성질이 다르다:
 * <ul>
 *   <li>할인은 <b>교습비에만</b> 붙는다 — 독서실비는 할인이 없다</li>
 *   <li>환불이 <b>교습비는 구간</b>(1/3까지 2/3, 1/2까지 1/2, 이후 없음),
 *       <b>독서실비는 일할</b>이다</li>
 * </ul>
 * 합쳐 두면 퇴원 정산에서 다시 가를 방법이 없다.
 *
 * <p>{@code academy}가 {@code null}이면 전 지점 공통이다. 조회는 <b>지점 행이 있으면
 * 그것만</b> 쓴다 — 합치면 같은 상품이 두 번 나온다.
 *
 * <p>연도가 키에 들어간다. 내년 인상 계획이 있고, <b>과거 청구가 소급해서 바뀌면 안 된다.</b>
 */
@Getter
@Entity
@Table(name = "tuition_price")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TuitionPrice extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code null} = 전 지점 공통. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "academy_id")
    private Academy academy;

    @Column(name = "year", nullable = false)
    private short year;

    @Enumerated(EnumType.STRING)
    @Column(name = "grade_type", nullable = false, length = 10)
    private GradeType gradeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "seat_type", nullable = false, length = 10)
    private SeatType seatType;

    /** 할인 대상. 환불은 구간 방식이다. */
    @Column(name = "tuition_fee", nullable = false)
    private int tuitionFee;

    /** 할인 없음. 환불은 일할이다. */
    @Column(name = "study_room_fee", nullable = false)
    private int studyRoomFee;

    public TuitionPrice(Academy academy, short year, GradeType gradeType, SeatType seatType,
                        int tuitionFee, int studyRoomFee) {
        this.academy = academy;
        this.year = year;
        this.gradeType = gradeType;
        this.seatType = seatType;
        this.tuitionFee = tuitionFee;
        this.studyRoomFee = studyRoomFee;
    }

    /** 전 지점 공통 가격. */
    public static TuitionPrice common(short year, GradeType gradeType, SeatType seatType,
                                      int tuitionFee, int studyRoomFee) {
        return new TuitionPrice(null, year, gradeType, seatType, tuitionFee, studyRoomFee);
    }

    /** 월 결제 총액. 표시용이고 <b>저장하지 않는다</b> — 두 값에서 언제나 파생된다. */
    public int monthlyTotal() {
        return tuitionFee + studyRoomFee;
    }

    public void updateFees(int tuitionFee, int studyRoomFee) {
        this.tuitionFee = tuitionFee;
        this.studyRoomFee = studyRoomFee;
    }

    public boolean isCommon() {
        return academy == null;
    }
}
