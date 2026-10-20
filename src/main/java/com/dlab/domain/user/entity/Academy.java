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
     * 지각 판정 기준시각 — <b>이 시각 이후 첫 태깅이 지각이다</b> (디랩 1019).
     *
     * <p>★ {@link #attendanceDeadline} 과 <b>다른 축</b>이다. 한 칸으로 두면 기준시각이
     * 08:20 인 지점에서 07:55 에 온 학생이 <b>출결상 정상 등원인데 학부모에게는 지각
     * 알림이 간다.</b>
     *
     * <p>★★ <b>비어 있으면 {@link #attendanceDeadline} 을 쓴다</b>({@link #lateAfterOrDeadline}).
     * 07:50 을 기본값으로 박으면 <b>값이 안 들어온 지점에서 08시에 오는 학생이 전부 지각으로
     * 찍힌다</b> — 디랩이 준 07:50·08:20 은 아직 한 건도 들어오지 않았고 11개 지점이 전부
     * 09:00 이다. 모르면 추측값을 쓰지 않고 <b>기존 동작을 유지</b>한다.
     */
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
     * <p>지각 기준이 비어 있으면 등원 기준시각으로 떨어진다. 그래야 값이 안 들어온 지점이
     * <b>지금과 똑같이 동작</b>한다.
     */
    public LocalTime lateAfterOrDeadline() {
        return lateAfter != null ? lateAfter : attendanceDeadline;
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
