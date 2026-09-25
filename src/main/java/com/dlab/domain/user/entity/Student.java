package com.dlab.domain.user.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 학생 — <b>사람</b> 쪽 (영구, 연도 무관).
 *
 * <p>학번·RFID는 여기 없다. 그것들은 {@link StudentEnrollment}(등록 건)에 붙는다.
 * 독학재수라 1년 단위 코호트이므로 <b>연도를 넘는 학생 연속성을 가정하지 말 것</b>.
 * 대신 삼수로 재등록하면 같은 사람에 등록 행만 추가되므로 동일인 추적이 공짜로 된다.
 *
 * <p>{@code academyId}·{@code year}가 없는 것은 의도된 예외다 — 지점과 연도는 등록 건의 속성이다.
 */
@Getter
@Entity
@com.dlab.domain.audit.Audited("학생")
// ★ @Audited 만으로는 안 남는다 — 리스너를 함께 붙여야 콜백이 온다.
//   이게 빠져 있어서 학생 이름·연락처·영문명 수정이 감사 로그에 아예 없었다
//   (등록 건은 붙어 있어 재원 상태 변경만 남고 있었다)
@jakarta.persistence.EntityListeners(com.dlab.domain.audit.AuditEntityListener.class)
@Table(name = "student")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Student extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 학부모 자녀연결용 고유ID. 마이페이지에 상시 노출된다.
     * 사람에 붙으므로 재등록해도 바뀌지 않는다.
     * 이 값을 아는 사람이면 본인확인 없이 연결 가능하다 — 클라이언트가 감수한 부분이라
     * 추가 검증 로직을 임의로 만들지 말 것.
     */
    @Column(name = "unique_code", nullable = false, unique = true, length = 20)
    private String uniqueCode;

    @Column(nullable = false, length = 20)
    private String name;

    /** ★ 값은 감사 로그에 남기지 않는다 — 바뀐 사실만 남는다. */
    @com.dlab.domain.audit.AuditMasked
    @Column(length = 20)
    private String phone;

    /** 암호화 여부 미정 — 동명이인 구분 조회조건으로 쓰이면 인덱스를 못 탄다. */
    @com.dlab.domain.audit.AuditMasked
    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(length = 1)
    private String gender;

    @Column(name = "school_name", length = 64)
    private String schoolName;

    /** ★ 민감 필드. 전화·생년월일과 같은 등급으로 다룬다(마스킹·상위 관리자 전용). */
    @com.dlab.domain.audit.AuditMasked
    @Column(length = 200)
    private String address;

    /** 영문명(선택). 성적표·증명서 영문 표기용이다. */
    @Column(name = "english_name", length = 100)
    private String englishName;

    /** 고교 졸업연도(선택). N수 차수와 따로 받는다 — 차수는 학원이 세는 값이고 이건 서류 값이다. */
    @Column(name = "graduation_year")
    private Short graduationYear;

    @Column(name = "search_name_normalized", length = 20)
    private String searchNameNormalized;

    /**
     * 앱 온보딩 단계 (A-2). <b>서버의 이 값이 단일 진실</b>이고 앱이 여기 따라 화면을 분기한다.
     *
     * <p>승인 여부는 여기 없다 — {@code account.status}가 따로 관리한다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "onboarding_status", nullable = false, length = 20)
    private OnboardingStatus onboardingStatus = OnboardingStatus.REGISTERED;

    public Student(String uniqueCode, String name, String phone) {
        this.uniqueCode = uniqueCode;
        this.name = name;
        this.phone = phone;
    }

    /**
     * 사람 정보 수정.
     *
     * <p><b>{@code null}인 인자는 "변경하지 않음"이다</b> — 빈 값으로 덮어쓰지 않는다.
     * 엑셀 업로드에서 마스킹된 연락처가 오면 호출자가 {@code null}로 바꿔 넘기고,
     * 그 결과 기존 번호가 그대로 남는다({@code common/privacy/Masking}).
     */
    public void updateProfile(String name, String phone, LocalDate birthDate,
                              String gender, String schoolName, String address) {
        if (name != null) {
            this.name = name;
        }
        if (phone != null) {
            this.phone = phone;
        }
        if (birthDate != null) {
            this.birthDate = birthDate;
        }
        if (gender != null) {
            this.gender = gender;
        }
        if (schoolName != null) {
            this.schoolName = schoolName;
        }
        if (address != null) {
            this.address = address;
        }
    }

    /**
     * 온보딩 다음 단계로 전진.
     *
     * <p><b>단계를 건너뛸 수 없다.</b> 각 단계 완료 처리가 값을 직접 대입하면
     * OT를 안 했는데 {@code SCHEDULE_SET}인 계정이 생긴다.
     *
     * @param expected 지금 있어야 하는 단계. 다르면 아무 일도 하지 않는다 —
     *                 학부모 연결이 두 번 들어와도 단계가 두 칸 뛰지 않는다
     */
    public void advanceOnboarding(OnboardingStatus expected) {
        if (this.onboardingStatus == expected) {
            this.onboardingStatus = expected.next();
        }
    }

    /**
     * 영문명·졸업연도. {@code null}은 "변경 없음", <b>빈 문자열은 지움</b>이다(영문명).
     * 졸업연도를 지우려면 {@code clearGraduationYear}를 쓴다.
     */
    public void updateExtra(String englishName, Short graduationYear, boolean clearGraduationYear) {
        if (englishName != null) {
            this.englishName = englishName.isBlank() ? null : englishName.trim();
        }
        if (clearGraduationYear) {
            this.graduationYear = null;
        } else if (graduationYear != null) {
            this.graduationYear = graduationYear;
        }
    }
}
