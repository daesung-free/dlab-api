package com.dlab.domain.profile.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 신상기록부 작성본 (F-4.11-9).
 *
 * <h2>★★ 사람이 아니라 등록 건에 붙는다</h2>
 * {@code docs/entity-design.md} S 는 <i>"신상 정보는 재등록해도 바뀌지 않는다"</i>며
 * {@code student_id} 로 적어 뒀지만, <b>여기 담는 것은 신상 그 자체가 아니라 그해의 상태</b>다 —
 * 건강·생활습관·목표대학은 1년 사이에 실제로 바뀐다. 사람에 붙이면 작년에 쓴 것이 올해
 * 「작성 완료」로 보여 <b>담임이 다시 받지 않는다</b>(§3 2단 구조).
 *
 * <h2>★ 내신은 여기 없다</h2>
 * {@code student_grade_submission.main_subject_average} <b>한 칸이 정본</b>이다. 입학서류
 * 실물에 「내신성적(주요교과평균)」 칸이 하나뿐이라 그렇게 설계됐다 — 여기에 과목별로
 * 쪼개 넣으면 <b>같은 값이 두 곳에 생기고 장학 취소 판정이 어느 쪽을 보는지 정해지지 않는다</b>.
 *
 * <h2>⚠️ 건강상태는 민감정보다</h2>
 * 수집 동의 문구가 <b>아직 미확정</b>이라 저장 구조만 두고 <b>조회를 상위 관리자로 제한</b>한다
 * ({@code PersonalDataPolicy}). 동의 여부 플래그를 여기 두지 않은 것은 의도적이다 —
 * 동의는 {@code terms}/{@code term_agreement} 가 <b>이력</b>으로 관리하고, 여기 플래그를
 * 두면 「그때 어떤 문구에 동의했나」의 답이 두 곳으로 갈린다.
 */
@Getter
@Entity
@Table(name = "student_profile")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudentProfile extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private short year;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    /**
     * 어느 양식으로 썼나.
     *
     * <p>★ <b>비어 있을 수 있다.</b> I-17 미확정이라 <b>양식 행 없이도 작성이 돌아야</b> 한다 —
     * 지금 프론트 화면은 고정 항목이다. 양식이 확정된 뒤 작성분만 채워진다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "form_id")
    private StudentProfileForm form;

    /** 건강상태 자유 서술 (알레르기·질환·복용약 등). ⚠️ 민감정보 */
    @Column(name = "health_note", length = 2000)
    private String healthNote;

    /** 건강상태 선택 항목. 목록이 미확정이라 JSON 이다. ⚠️ 민감정보 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "health_json", columnDefinition = "jsonb")
    private String healthJson;

    /** 생활습관 자유 서술 (수면·식사·학습). */
    @Column(name = "life_note", length = 2000)
    private String lifeNote;

    /** 생활습관 선택 항목. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "life_json", columnDefinition = "jsonb")
    private String lifeJson;

    /** 양식이 정의한 나머지 항목의 답. 양식이 바뀌어도 스키마를 고치지 않는다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "answers_json", columnDefinition = "jsonb")
    private String answersJson;

    /**
     * 최초 작성 시각.
     *
     * <p>{@code created_at} 으로 대신하지 않는다 — <b>소급 입력</b>이 실제로 있다(종이로
     * 받아둔 것을 데스크가 며칠 뒤 넣는다). {@code effective_date} 와 {@code created_at} 을
     * 가른 것과 같은 판단이다.
     */
    @Column(name = "written_at", nullable = false)
    private Instant writtenAt;

    // ★ 목표대학을 컬렉션으로 매핑하지 않는다. 매핑하면 JPA 가 「목록에서 뺀 줄」을
    //   물리 삭제(orphanRemoval)하거나 soft delete 된 줄까지 끌고 오게 되는데, 뺀 줄은
    //   남아야 한다 — 「그때 무엇을 목표로 썼나」가 상담 근거이고 합격증 파일이 그 줄에
    //   매달려 있다. 조회·교체는 TargetUniversityRepository 가 한다.

    public StudentProfile(StudentEnrollment enrollment, StudentProfileForm form,
                          Instant writtenAt) {
        this.enrollment = enrollment;
        this.academy = enrollment.getAcademy();
        this.year = enrollment.getYear();
        this.form = form;
        this.writtenAt = writtenAt;
    }

    /**
     * 내용 교체.
     *
     * <p>★ <b>보내지 않은 칸은 비워진다</b>(부분 갱신이 아니다). 화면이 신상기록부 한 장을
     * 통째로 보내는 모양이고, 부분 갱신으로 두면 <b>「지운 것」과 「안 보낸 것」을 구분할
     * 방법이 없다</b> — 담임이 잘못 쓴 건강 메모를 지울 수 없게 된다.
     */
    public void overwrite(StudentProfileForm form, String healthNote, String healthJson,
                          String lifeNote, String lifeJson, String answersJson) {
        this.form = form;
        this.healthNote = healthNote;
        this.healthJson = healthJson;
        this.lifeNote = lifeNote;
        this.lifeJson = lifeJson;
        this.answersJson = answersJson;
    }

    /** 소급 입력 — 종이로 받아둔 날짜를 넣는다. */
    public void changeWrittenAt(Instant writtenAt) {
        this.writtenAt = writtenAt;
    }
}
