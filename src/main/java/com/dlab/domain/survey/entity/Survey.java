package com.dlab.domain.survey.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.ClassMaster;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 설문 (F-4.11-3 · A-14).
 *
 * <h2>기간은 서버가 판정한다</h2>
 * 열림/닫힘을 상태 컬럼으로 두지 않고 {@code opensAt}·{@code closesAt}과 현재 시각을
 * 비교한다 — 상태 컬럼이면 마감 시각이 지나도 <b>배치가 돌기 전까지 계속 열려 있다</b>.
 *
 * <h2>★ 익명은 응답자를 저장하지 않는 것이다</h2>
 * 이름만 화면에서 가리면 DB에는 누가 뭘 냈는지 그대로 남는다. 그래서 익명 설문은
 * 응답 행에 응답자를 비우고, 중복 제출 방지에 필요한 <b>참여 사실만</b>
 * {@link SurveyParticipant}에 따로 남긴다.
 */
@Getter
@Entity
@Table(name = "survey")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Survey extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 전 지점 설문({@link SurveyScope#ALL})이면 {@code null}이다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "academy_id")
    private Academy academy;

    @Column(nullable = false)
    private short year;

    @Enumerated(EnumType.STRING)
    @Column(name = "survey_type", nullable = false, length = 20)
    private SurveyType surveyType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SurveyScope scope;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "class_master_id")
    private ClassMaster classMaster;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false)
    private boolean anonymous;

    @Column(name = "opens_at", nullable = false)
    private Instant opensAt;

    @Column(name = "closes_at", nullable = false)
    private Instant closesAt;

    @OneToMany(mappedBy = "survey", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("seq ASC")
    private List<SurveyQuestion> questions = new ArrayList<>();

    private Survey(Academy academy, short year, SurveyType surveyType, SurveyScope scope,
                   String title, String description, boolean anonymous,
                   Instant opensAt, Instant closesAt) {
        this.academy = academy;
        this.year = year;
        this.surveyType = surveyType;
        this.scope = scope;
        this.title = title;
        this.description = description;
        this.anonymous = anonymous;
        this.opensAt = opensAt;
        this.closesAt = closesAt;
    }

    /** 전 지점 설문. 지점을 갖지 않는다. */
    public static Survey ofAll(short year, SurveyType surveyType, String title,
                               String description, boolean anonymous,
                               Instant opensAt, Instant closesAt) {
        return new Survey(null, year, surveyType, SurveyScope.ALL,
                title, description, anonymous, opensAt, closesAt);
    }

    public static Survey ofBranch(Academy academy, short year, SurveyType surveyType,
                                  String title, String description, boolean anonymous,
                                  Instant opensAt, Instant closesAt) {
        return new Survey(academy, year, surveyType, SurveyScope.BRANCH,
                title, description, anonymous, opensAt, closesAt);
    }

    public static Survey ofClass(ClassMaster classMaster, short year, SurveyType surveyType,
                                 String title, String description, boolean anonymous,
                                 Instant opensAt, Instant closesAt) {
        Survey survey = new Survey(classMaster.getAcademy(), year, surveyType, SurveyScope.CLASS,
                title, description, anonymous, opensAt, closesAt);
        survey.classMaster = classMaster;
        return survey;
    }

    /**
     * 문항 추가.
     *
     * <p>{@code seq}는 넘겨받지 않고 <b>추가된 순서로 매긴다</b> — 화면이 보낸 번호를 그대로
     * 쓰면 빠진 번호나 중복이 그대로 들어와 유니크 제약에 걸린다.
     */
    public SurveyQuestion addQuestion(SurveyQuestionType type, String title, boolean required,
                                      BigDecimal minValue, BigDecimal maxValue) {
        SurveyQuestion question = new SurveyQuestion(
                this, (short) (questions.size() + 1), type, title, required, minValue, maxValue);
        questions.add(question);
        return question;
    }

    public List<SurveyQuestion> activeQuestions() {
        return questions.stream()
                .filter(q -> !q.isDeleted())
                .sorted(Comparator.comparing(SurveyQuestion::getSeq))
                .toList();
    }

    /**
     * 지금 응답을 받는가.
     *
     * <p>마감 시각 <b>이후</b>는 받지 않는다. 경계(정각)는 여는 쪽에 준다 —
     * 여는 시각에 화면이 열렸는데 제출이 거절되면 사용자가 원인을 알 수 없다.
     */
    public boolean isOpenAt(Instant at) {
        return !at.isBefore(opensAt) && at.isBefore(closesAt);
    }

    /** 기간이 지났는가. 앱 목록에서 "마감" 표시에 쓴다. */
    public boolean isClosedAt(Instant at) {
        return !at.isBefore(closesAt);
    }

    /**
     * 내용 수정.
     *
     * <p><b>범위·대상·문항은 여기서 바꾸지 않는다.</b> 응답이 이미 들어온 뒤 문항이 바뀌면
     * 앞사람과 뒷사람이 서로 다른 질문에 답한 결과가 한 집계에 섞인다.
     */
    public void update(String title, String description, Instant opensAt, Instant closesAt) {
        this.title = title;
        this.description = description;
        this.opensAt = opensAt;
        this.closesAt = closesAt;
    }

    /** 즉시 마감. 마감 시각을 지금으로 당긴다. */
    public void closeNow(Instant now) {
        this.closesAt = now;
    }
}
