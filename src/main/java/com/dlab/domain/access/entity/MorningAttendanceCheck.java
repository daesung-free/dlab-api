package com.dlab.domain.access.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 오전 출근 확인 결과 — <b>하루 한 지점 한 건</b>.
 *
 * <p>검사 결과를 남기는 이유는 <b>「발송했는데 안 왔다」와 「검사가 안 돌았다」를 가려야</b>
 * 하기 때문이다. 기록이 없으면 그 둘이 구분되지 않고 아침마다 사람이 로그를 뒤지게 된다.
 */
@Getter
@Entity
@Table(name = "morning_attendance_check")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MorningAttendanceCheck extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private short year;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(name = "check_date", nullable = false)
    private LocalDate checkDate;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    /**
     * 그 시각까지 들어온 출근(IN) 건수.
     *
     * <p>건수를 남기는 이유는 <b>1과 0의 경계</b>에서 "정말 아무도 없었나"를 되짚을 수
     * 있어야 해서다 — 불린만 남기면 오판했을 때 근거가 없다.
     */
    @Column(name = "in_count", nullable = false)
    private int inCount;

    @Column(name = "alert_needed", nullable = false)
    private boolean alertNeeded;

    /**
     * 실제로 보냈는가.
     *
     * <p>⚠️ <b>알리고 연동 전에는 늘 {@code false}</b>다. 그 사실이 기록에 남아야
     * "왜 문자가 안 왔나"에 답할 수 있다 — 안 남기면 발송 실패와 미연동이 같아 보인다.
     */
    @Column(name = "alert_sent", nullable = false)
    private boolean alertSent = false;

    /**
     * 보낼 대상 계정 수.
     *
     * <p>★ <b>{@code 0}이면 권한을 가진 계정이 없다는 뜻</b>이고 그건 설정 문제다 —
     * 검사는 돌았는데 받을 사람이 없는 상태를 화면이 드러내야 한다.
     */
    @Column(name = "recipient_count", nullable = false)
    private int recipientCount = 0;

    public MorningAttendanceCheck(Academy academy, short year, LocalDate checkDate,
                                  Instant checkedAt, int inCount, int recipientCount) {
        this.academy = academy;
        this.year = year;
        this.checkDate = checkDate;
        this.checkedAt = checkedAt;
        this.inCount = inCount;
        this.alertNeeded = inCount == 0;
        this.recipientCount = recipientCount;
    }

    /** 발송에 성공했을 때만 켠다. 알리고가 붙으면 발송 코드가 호출한다. */
    public void markSent() {
        this.alertSent = true;
    }
}
