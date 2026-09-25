package com.dlab.domain.admission.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 홈페이지 입학예약 신청 (F-4.2-1, 규격서 3.3).
 *
 * <h2>★ 학생이 아니다</h2>
 * 아직 학원생이 아니라 상담·심사 중인 지원자다. {@code student_enrollment}에 넣으면
 * 재원생 통계·키오스크 동기화·확정 배치에 전부 섞인다. 합격 처리 시점에 관리자가
 * 학생으로 전환한다(F-4.1-4).
 *
 * <h2>필드는 규격서 그대로다</h2>
 * 홈페이지가 보내는 값을 우리가 고를 수 없다. 공통코드 참조값(전형·지원기준·유입경로·
 * 출신학원)은 <b>목록을 아직 못 받아</b> 코드 숫자만 보관한다.
 */
@Getter
@Entity
@Table(name = "admission_reservation")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdmissionReservation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(nullable = false)
    private short year;

    /**
     * 홈페이지에 돌려주는 학생고유코드.
     *
     * <p><b>{@code id}를 그대로 쓰지 않는다</b> — 외부에 나가는 식별자라 내부 순번이
     * 드러나면 지원자 수가 추정된다.
     */
    @Column(name = "rsv_cd", nullable = false, length = 20)
    private String rsvCd;

    @Column(name = "student_name", nullable = false, length = 20)
    private String studentName;

    @Column(name = "student_tel", nullable = false, length = 20)
    private String studentTel;

    @Column(name = "parent_tel", nullable = false, length = 20)
    private String parentTel;

    /** {@code M} / {@code F}. */
    @Column(length = 1)
    private String gender;

    /** {@code yyyyMMdd}. */
    @Column(length = 8)
    private String birth;

    /** 인문 1 · 자연 2 · 예체능 3 · 공통 9. */
    @Column(name = "geyul_gb")
    private Short geyulGb;

    /** 등원 희망일 {@code yyyyMMdd}. */
    @Column(name = "adm_dt", length = 8)
    private String admDt;

    @Column(name = "pre_test")
    private Integer preTest;

    @Column(name = "admi_st")
    private Integer admiSt;

    @Column(name = "find_gb")
    private Integer findGb;

    @Column(name = "find_txt", length = 100)
    private String findTxt;

    @Column(name = "sch_cd")
    private Integer schCd;

    /** 일반고 91 · 특목/자사고 92. */
    @Column(name = "nasin_st")
    private Short nasinSt;

    @Column(name = "nasin_sc", precision = 4, scale = 2)
    private BigDecimal nasinSc;

    @Column(name = "uni_nm", length = 50)
    private String uniNm;

    @Column(name = "uni_gd")
    private Short uniGd;

    /** 성적 기준미달 81 · 기타 82. */
    @Column(name = "intr_st")
    private Short intrSt;

    @Column(name = "intr_txt", length = 200)
    private String intrTxt;

    @Column(length = 10)
    private String zip;

    @Column(length = 200)
    private String addr1;

    @Column(length = 200)
    private String addr2;

    @Column(name = "sch_cd_high")
    private Integer schCdHigh;

    @Column(name = "sch_nm_high", length = 50)
    private String schNmHigh;

    @Column(name = "agree_ad", nullable = false)
    private boolean agreeAd;

    @Column(name = "promo_ad", nullable = false)
    private boolean promoAd;

    /** 고1 {@code 1} · 고2 {@code 2} · 고3 {@code 3} · N수생 {@code N}. */
    @Column(name = "std_grade", nullable = false, length = 1)
    private String stdGrade;

    /**
     * 상담 진행 상태 (2026-09-18 답변서).
     *
     * <p>★ <b>재적 상태와 다른 축이다.</b> 두 축이 만나는 지점은 {@code CONFIRMED} 하나뿐이고
     * 거기서 학생으로 전환한다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "consult_status", nullable = false, length = 20)
    private ConsultStatus consultStatus = ConsultStatus.CALL_NEEDED;

    /** 합격 처리로 학생이 되면 그 등록 건. 전환 전에는 {@code null}. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "enrollment_id")
    private StudentEnrollment enrollment;

    @Builder
    public AdmissionReservation(Academy academy, short year, String rsvCd,
                                String studentName, String studentTel, String parentTel,
                                String gender, String birth, Short geyulGb, String admDt,
                                Integer preTest, Integer admiSt, Integer findGb, String findTxt,
                                Integer schCd, Short nasinSt, BigDecimal nasinSc,
                                String uniNm, Short uniGd, Short intrSt, String intrTxt,
                                String zip, String addr1, String addr2,
                                Integer schCdHigh, String schNmHigh,
                                boolean agreeAd, boolean promoAd, String stdGrade) {
        this.academy = academy;
        this.year = year;
        this.rsvCd = rsvCd;
        this.studentName = studentName;
        this.studentTel = studentTel;
        this.parentTel = parentTel;
        this.gender = gender;
        this.birth = birth;
        this.geyulGb = geyulGb;
        this.admDt = admDt;
        this.preTest = preTest;
        this.admiSt = admiSt;
        this.findGb = findGb;
        this.findTxt = findTxt;
        this.schCd = schCd;
        this.nasinSt = nasinSt;
        this.nasinSc = nasinSc;
        this.uniNm = uniNm;
        this.uniGd = uniGd;
        this.intrSt = intrSt;
        this.intrTxt = intrTxt;
        this.zip = zip;
        this.addr1 = addr1;
        this.addr2 = addr2;
        this.schCdHigh = schCdHigh;
        this.schNmHigh = schNmHigh;
        this.agreeAd = agreeAd;
        this.promoAd = promoAd;
        this.stdGrade = stdGrade;
    }

    /** 합격 → 학생 전환. */
    public void linkEnrollment(StudentEnrollment enrollment) {
        this.enrollment = enrollment;
    }

    public void changeStatus(ConsultStatus status) {
        this.consultStatus = status;
    }

    /** 이미 학생으로 전환됐는가. 두 번 전환하면 학번이 두 개 생긴다 */
    public boolean converted() {
        return enrollment != null;
    }
}
