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

    /**
     * getDlabList의 세 번째 필드. V2에서 추가됐다.
     * nullable인 이유는 기존 행 때문이며, 비어 있으면 {@link #getFullNmOrFallback()}이
     * acadNm으로 대체한다 — 키오스크가 null을 못 읽는다.
     */
    @Column(name = "full_nm", length = 100)
    private String fullNm;

    /** 키오스크 백엔드 stores.store_code 대조용. */
    @Column(name = "store_code", length = 20)
    private String storeCode;

    /**
     * 홈페이지 입학예약 학원코드(분당 F · 일산 I · 동탄 T …).
     *
     * <p>★ <b>키오스크 {@code acadCd}(31·32…)와 다른 체계다.</b> 같은 지점을 부르는 이름이
     * 둘이라 섞으면 엉뚱한 지점에 저장된다.
     */
    @Column(name = "dlab_cd", length = 2)
    private String dlabCd;

    /**
     * 등원 기준시각(지점 공통, 학생별 아님). <b>이 시각에 미등원 감지 배치가 돈다.</b>
     *
     * <p>⚠️ <b>지각 판정에는 쓰지 않는다</b> — 그쪽은 {@link #lateAfter} 다. 전에는 이 한 칸이
     * 둘을 같이 했는데, 디랩이 준 시각이 달라(지각 07:50 / 결석 08:20) 나눴다.
     */
    @Column(name = "attendance_deadline", nullable = false)
    private LocalTime attendanceDeadline;

    /**
     * ★★ 학생이 <b>하루에 신청할 수 있는 질의응답 건수</b> (디랩 1029 8-1).
     *
     * <h2>★ 「하루」는 슬롯 날짜다 — 신청 시각이 아니다</h2>
     * ⚠️ 신청 시각으로 세면 <b>밤 11시에 내일·모레 것을 몰아 잡을 수 있고</b>,
     * 반대로 <b>같은 날 상담 두 개를 잡아도 어제 신청했으면 통과한다.</b>
     *
     * <p>★ <b>지점 설정이다</b> — 지점마다 상담 인력이 달라 같은 값일 이유가 없다.
     * ⚠️ 코드에 2를 박지 말 것(1029 지침).
     *
     * <p>★ <b>직원 대리 예약은 이 상한을 건너뛴다</b> — 상한은 학생 셀프 신청을
     * 고르게 나누기 위한 것이다.
     */
    @Column(name = "qna_daily_limit", nullable = false)
    private short qnaDailyLimit = 2;

    /**
     * 지각 판정 기준시각 — <b>이 시각 이후 첫 태깅이 지각이다</b> (디랩 1019).
     *
     * <p>★ {@link #attendanceDeadline} 과 <b>다른 축</b>이다. 한 칸으로 두면 기준시각이
     * 08:20 인 지점에서 07:55 에 온 학생이 <b>출결상 정상 등원인데 학부모에게는 지각
     * 알림이 간다.</b>
     *
     * <p>★★ <b>전 지점 공통 07:50 이다</b> (디랩 2026-10-21 확정:
     * <i>"지각 기준시각(07시50분)은 전 지점 공통 입니다"</i>).
     *
     * <p>⚠️ <b>그래도 지점별 칸으로 둔다.</b> 지금은 공통이지만 갈릴 수 있고, 그때 칸이
     * 없으면 마이그레이션을 다시 써야 한다. 값만 채운다({@code V20261021220000}).
     *
     * <p>★ 비어 있을 때 {@link #attendanceDeadline} 으로 떨어지던 동작을 <b>없앴다.</b>
     * 그때는 07:50 이 추측값이었으므로 기존 동작을 유지하는 것이 맞았지만, 지금은
     * 디랩이 준 확정값이다 — 폴백을 두면 값이 빈 지점에서 지각 기준이 09:00 이 되어
     * <b>07:55 에 온 학생이 「정상 등원」으로 찍히고 학부모는 지각 알림을 못 받는다.</b>
     * 오류가 나지 않고 조용히 틀린다.
     */
    /** 지각 기준 확정값 (디랩 2026-10-21, 전 지점 공통) */
    public static final LocalTime DEFAULT_LATE_AFTER = LocalTime.of(7, 50);

    @Column(name = "late_after")
    private LocalTime lateAfter;

    /**
     * 모의고사 자료의 학교코드({@code 99700}~{@code 99711}).
     *
     * <p>★ <b>{@code acadCd} 와 다른 체계다.</b> 김포·동탄이 서로 뒤집혀 있어
     * (34→99702, 33→99703) <b>순서로 유추할 수 없다.</b> 비어 있으면 그 지점은
     * 성적 업로드에서 키 매칭이 안 되고 이름 매칭으로 떨어진다.
     */
    @Column(name = "exam_school_cd", length = 10)
    private String examSchoolCd;

    @Column(nullable = false)
    private boolean active;

    public Academy(String acadCd, String acadNm, LocalTime attendanceDeadline) {
        this.acadCd = acadCd;
        this.acadNm = acadNm;
        this.attendanceDeadline = attendanceDeadline;
        this.active = true;
    }

    /**
     * 지각 기준시각 변경.
     *
     * <p>★ {@code updateInfo} 와 따로 둔다 — 등원 기준시각은 <b>미등원 배치</b>가,
     * 이 값은 <b>지각 판정</b>이 쓴다. 한 화면에서 같이 받더라도 서로 다른 값이므로
     * 한쪽을 고치며 다른 쪽을 덮어쓰지 않게 메서드를 가른다.
     */
    public void changeLateAfter(LocalTime lateAfter) {
        if (lateAfter != null) {
            this.lateAfter = lateAfter;
        }
    }

    /** 지각 기준을 비워 등원 기준시각으로 되돌린다. */
    public void clearLateAfter() {
        this.lateAfter = null;
    }

    /**
     * 지각 판정에 쓸 기준시각 — <b>판정부는 반드시 이걸로 읽는다.</b>
     *
     * <p>★ 비어 있으면 <b>확정값 07:50</b> 을 쓴다 — 등원 기준시각으로 떨어뜨리지 않는다.
     * 떨어뜨리면 그 지점에서 07:55 에 온 학생이 정상 등원으로 찍혀 <b>지각 알림이 아예
     * 나가지 않는다.</b>
     */
    public LocalTime lateAfterOrDeadline() {
        return lateAfter != null ? lateAfter : DEFAULT_LATE_AFTER;
    }

    /** 로그·알림에서 쓰는 표시용 이름. */
    public String getName() {
        return acadNm;
    }

    /** DSA getDlabList의 full_nm. 미입력 지점은 짧은명으로 대체한다. */
    public String getFullNmOrFallback() {
        return fullNm == null || fullNm.isBlank() ? acadNm : fullNm;
    }

    /**
     * 기본정보 수정.
     *
     * <p><b>{@code acadCd}와 {@code storeCode}는 바꾸지 않는다.</b> 대성전산이 부여한 값이고
     * 키오스크가 이 값으로 인증·매칭한다 — 고치면 그 지점 연동이 조용히 끊긴다.
     *
     * <p>{@code attendanceDeadline}은 <b>지각 판정 기준</b>이라 바꾸면 그 시점부터
     * 판정이 달라진다. 이미 확정된 과거 출결은 저장값이라 영향받지 않는다.
     */
    public void updateInfo(String acadNm, String fullNm, LocalTime attendanceDeadline) {
        this.acadNm = acadNm;
        this.fullNm = fullNm;
        this.attendanceDeadline = attendanceDeadline;
    }

    /**
     * 활성·비활성.
     *
     * <p><b>끄면 그 지점 전체가 멈춘다</b> — 키오스크 토큰 발급부터 막힌다.
     * 그래서 본사만 할 수 있게 서비스에서 제한한다.
     */
    public void changeActive(boolean active) {
        this.active = active;
    }
}
