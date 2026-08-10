package com.dlab.domain.survey.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.survey.entity.Survey;
import com.dlab.domain.survey.entity.SurveyParticipant;
import com.dlab.domain.survey.entity.SurveyQuestion;
import com.dlab.domain.survey.entity.SurveyQuestionOption;
import com.dlab.domain.survey.entity.SurveyQuestionType;
import com.dlab.domain.survey.entity.SurveyResponse;
import com.dlab.domain.survey.entity.SurveyScope;
import com.dlab.domain.survey.entity.SurveyType;
import com.dlab.domain.survey.repository.SurveyParticipantRepository;
import com.dlab.domain.survey.repository.SurveyRepository;
import com.dlab.domain.survey.repository.SurveyResponseRepository;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.ClassAssignment;
import com.dlab.domain.user.entity.ClassMaster;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.entity.Teacher;
import com.dlab.domain.user.repository.AcademyRepository;
import com.dlab.domain.user.repository.AccountRepository;
import com.dlab.domain.user.repository.ClassAssignmentRepository;
import com.dlab.domain.user.repository.ClassMasterRepository;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 설문 (F-4.11-3 · A-14).
 *
 * <h2>기간은 서버가 판정한다</h2>
 * 목록에 "마감" 표시를 실어 내리고, 제출 시에도 <b>다시</b> 확인한다. 화면 표시만 믿으면
 * 마감 직전에 연 화면으로 한참 뒤에 제출하는 경로가 열린다.
 *
 * <h2>★ 문항은 만든 뒤 바꾸지 않는다</h2>
 * 응답이 들어온 뒤 문항이 바뀌면 앞사람과 뒷사람이 <b>다른 질문에 답한 결과</b>가 한 집계에
 * 섞인다. 고칠 일이 생기면 마감하고 새로 낸다 — 그래야 어느 문항에 대한 응답인지가 남는다.
 *
 * <h2>범위별 작성 권한 (공지와 같은 기준)</h2>
 * 전 지점은 본사만, 지점은 지점관리자 이상, 반은 그 반 담임 또는 지점관리자다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SurveyService {

    private final SurveyRepository surveyRepository;
    private final SurveyResponseRepository responseRepository;
    private final SurveyParticipantRepository participantRepository;
    private final AccountRepository accountRepository;
    private final AcademyRepository academyRepository;
    private final ClassMasterRepository classMasterRepository;
    private final ClassAssignmentRepository classAssignmentRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final Clock clock;

    // ── 관리자: 생성·관리 ──────────────────────────────────

    /**
     * 설문 개설.
     *
     * <p>문항까지 <b>한 번에</b> 받는다. 설문만 만들고 문항을 나중에 붙이게 하면
     * 문항 없는 설문이 앱에 노출되는 순간이 생긴다.
     */
    @Transactional
    public Survey create(AuthPrincipal me, SurveyCommand command) {
        if (command.questions() == null || command.questions().isEmpty()) {
            throw new BusinessException(ErrorCode.SURVEY_QUESTION_EMPTY);
        }
        if (!command.closesAt().isAfter(command.opensAt())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "마감 시각이 시작 시각보다 뒤여야 합니다.");
        }

        Survey survey = switch (command.scope()) {
            case ALL -> {
                requireHeadOffice(me);
                yield Survey.ofAll(currentYear(), command.surveyType(), command.title(),
                        command.description(), command.anonymous(),
                        command.opensAt(), command.closesAt());
            }
            case BRANCH -> {
                requireBranchAdmin(me, command.academyId());
                var academy = academyRepository.findById(command.academyId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));
                yield Survey.ofBranch(academy, currentYear(), command.surveyType(),
                        command.title(), command.description(), command.anonymous(),
                        command.opensAt(), command.closesAt());
            }
            case CLASS -> {
                ClassMaster classMaster = classMasterRepository.findById(command.classId())
                        .filter(c -> !c.isDeleted())
                        .orElseThrow(() -> new BusinessException(ErrorCode.CLASS_NOT_FOUND));
                requireClassWriter(me, classMaster);
                yield Survey.ofClass(classMaster, currentYear(), command.surveyType(),
                        command.title(), command.description(), command.anonymous(),
                        command.opensAt(), command.closesAt());
            }
        };

        for (QuestionCommand q : command.questions()) {
            SurveyQuestion question = survey.addQuestion(
                    q.type(), q.title(), q.required(), q.minValue(), q.maxValue());

            if (q.type().isChoice()) {
                if (q.options() == null || q.options().isEmpty()) {
                    throw new BusinessException(ErrorCode.SURVEY_OPTION_EMPTY,
                            "'%s' 문항에 선택지가 없습니다.".formatted(q.title()));
                }
                q.options().forEach(question::addOption);
            }
        }

        Survey saved = surveyRepository.save(survey);
        log.info("설문 개설: id={}, 범위={}, 문항={}건, 익명={}",
                saved.getId(), saved.getScope(), command.questions().size(), saved.isAnonymous());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Survey> findForAdmin(AuthPrincipal me, Short year) {
        short targetYear = year != null ? year : currentYear();
        Long academyId = me.academyScopeFilter();
        return academyId == null
                ? surveyRepository.findForAdminAllAcademy(targetYear)
                : surveyRepository.findForAdmin(academyId, targetYear);
    }

    /**
     * 기간·안내문만 수정한다.
     *
     * <p>범위·대상·문항은 못 바꾼다(엔티티 참고).
     */
    @Transactional
    public Survey update(AuthPrincipal me, Long id, String title, String description,
                         Instant opensAt, Instant closesAt) {
        if (!closesAt.isAfter(opensAt)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "마감 시각이 시작 시각보다 뒤여야 합니다.");
        }
        Survey survey = requireWritable(me, id);
        survey.update(title, description, opensAt, closesAt);
        return survey;
    }

    /** 즉시 마감. 기간을 남겨두고 상태만 바꾸는 방식이 아니라 마감 시각을 당긴다. */
    @Transactional
    public Survey closeNow(AuthPrincipal me, Long id) {
        Survey survey = requireWritable(me, id);
        survey.closeNow(Instant.now(clock));
        log.info("설문 즉시 마감: id={}, 처리자={}", id, me.accountId());
        return survey;
    }

    /** 삭제(soft). 물리 삭제하면 "그때 무슨 설문을 돌렸나"에 답할 수 없다. */
    @Transactional
    public void delete(AuthPrincipal me, Long id) {
        requireWritable(me, id).markDeleted();
        log.info("설문 삭제: id={}, 처리자={}", id, me.accountId());
    }

    // ── 관리자: 집계 ─────────────────────────────────────

    /**
     * 결과 집계.
     *
     * <p>선택형은 <b>선택지별 응답 수</b>, 숫자형은 평균·최소·최대, 주관식은 원문 목록이다.
     *
     * <p><b>응답자는 내리지 않는다.</b> 익명 설문에는 애초에 없고, 실명 설문이라도 집계
     * 화면에 개인을 실을 이유가 없다 — 개별 응답이 필요하면 별도 화면·별도 권한이어야 한다.
     */
    @Transactional(readOnly = true)
    public SurveyResult results(AuthPrincipal me, Long surveyId) {
        Survey survey = requireReadable(me, surveyId);
        List<SurveyResponse> responses = responseRepository.findBySurvey(surveyId);

        List<QuestionResult> questionResults = new ArrayList<>();
        for (SurveyQuestion question : survey.activeQuestions()) {
            List<com.dlab.domain.survey.entity.SurveyAnswer> answers = responses.stream()
                    .flatMap(r -> r.activeAnswers().stream())
                    .filter(a -> a.getQuestion().getId().equals(question.getId()))
                    .toList();

            if (question.getQuestionType().isChoice()) {
                Map<Long, Long> counts = answers.stream()
                        .filter(a -> a.getOption() != null)
                        .collect(Collectors.groupingBy(a -> a.getOption().getId(),
                                Collectors.counting()));

                // 아무도 안 고른 선택지도 0으로 내린다 — 빠지면 화면이 선택지를 아예 못 그린다
                List<OptionCount> optionCounts = question.activeOptions().stream()
                        .map(o -> new OptionCount(o.getId(), o.getLabel(),
                                counts.getOrDefault(o.getId(), 0L)))
                        .toList();
                questionResults.add(QuestionResult.ofChoice(question, answers.size(), optionCounts));

            } else if (question.getQuestionType() == SurveyQuestionType.NUMBER) {
                List<BigDecimal> values = answers.stream()
                        .map(com.dlab.domain.survey.entity.SurveyAnswer::getNumberValue)
                        .filter(java.util.Objects::nonNull)
                        .toList();
                questionResults.add(QuestionResult.ofNumber(question, values));

            } else {
                List<String> texts = answers.stream()
                        .map(com.dlab.domain.survey.entity.SurveyAnswer::getTextValue)
                        .filter(java.util.Objects::nonNull)
                        .toList();
                questionResults.add(QuestionResult.ofText(question, texts));
            }
        }
        return new SurveyResult(survey, responses.size(), questionResults);
    }

    /** 제출자 목록. 익명이어도 <b>누가 냈는지</b>는 알 수 있다 — 미제출자 독려에 필요하다. */
    @Transactional(readOnly = true)
    public List<SurveyParticipant> participants(AuthPrincipal me, Long surveyId) {
        requireReadable(me, surveyId);
        return participantRepository.findBySurvey(surveyId);
    }

    // ── 앱 ──────────────────────────────────────────────

    /**
     * 앱 목록.
     *
     * <p>시작 전 설문은 빼고, <b>마감된 것은 남긴다</b> — 참여 여부를 확인할 수 있어야 하고,
     * 결과 공개 요건이 붙으면 여기 얹는다.
     */
    @Transactional(readOnly = true)
    public List<AppSurvey> feed(Long enrollmentId) {
        StudentEnrollment enrollment = requireEnrollment(enrollmentId);
        Long classId = classAssignmentRepository.findActiveFixedByEnrollmentId(enrollmentId)
                .map(a -> a.getClassMaster().getId())
                .orElse(null);

        Instant now = Instant.now(clock);
        return surveyRepository
                .findFeed(enrollment.getAcademy().getId(), classId, enrollment.getYear())
                .stream()
                .filter(s -> !now.isBefore(s.getOpensAt()))
                .map(s -> new AppSurvey(s, s.isOpenAt(now),
                        participantRepository.hasSubmitted(s.getId(), enrollmentId)))
                .toList();
    }

    /**
     * 상세 — 문항·선택지.
     *
     * <p><b>내게 배포된 설문인지 확인하고 내린다.</b> id만으로 열어주면 번호를 바꿔가며
     * 다른 반 설문을 열 수 있다.
     */
    @Transactional(readOnly = true)
    public AppSurvey detail(Long enrollmentId, Long surveyId) {
        return feed(enrollmentId).stream()
                .filter(s -> s.survey().getId().equals(surveyId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.SURVEY_NOT_FOUND));
    }

    /**
     * 응답 제출.
     *
     * <p>확인 순서가 <b>대상 → 기간 → 중복 → 답 검증</b>이다. 답부터 검증하면 마감된 설문에
     * "필수 문항 누락"이 먼저 뜨고, 사용자는 고쳐서 다시 냈다가 그제야 마감을 안다.
     *
     * <p>익명 설문이면 응답 행에 응답자를 남기지 않는다 — 중복은 참여 기록이 막는다.
     */
    @Transactional
    public SurveyResponse submit(Long enrollmentId, Long surveyId, List<AnswerCommand> answers) {
        AppSurvey target = detail(enrollmentId, surveyId);
        Survey survey = target.survey();
        Instant now = Instant.now(clock);

        if (!survey.isOpenAt(now)) {
            throw new BusinessException(ErrorCode.SURVEY_CLOSED);
        }
        if (participantRepository.hasSubmitted(surveyId, enrollmentId)) {
            throw new BusinessException(ErrorCode.SURVEY_ALREADY_SUBMITTED);
        }

        StudentEnrollment enrollment = requireEnrollment(enrollmentId);
        SurveyResponse response = SurveyResponse.of(survey, enrollment, now);
        fillAnswers(survey, response, answers);

        // 참여 기록이 중복 제출을 막는다. 유니크 제약이 최종 방어선이라
        // 동시에 두 번 눌러도 한 건만 남는다
        participantRepository.save(new SurveyParticipant(survey, enrollment, now));
        SurveyResponse saved = responseRepository.save(response);

        log.info("설문 응답 제출: 설문={}, 등록건={}, 익명={}",
                surveyId, enrollmentId, survey.isAnonymous());
        return saved;
    }

    /**
     * 내 응답 조회.
     *
     * <p><b>익명 설문은 없다</b> — 응답 행에 응답자가 없어서 되찾을 방법이 없고,
     * 그게 익명의 정의다. 앱은 제출 완료 표시까지만 한다.
     */
    @Transactional(readOnly = true)
    public SurveyResponse myResponse(Long enrollmentId, Long surveyId) {
        AppSurvey target = detail(enrollmentId, surveyId);
        if (target.survey().isAnonymous()) {
            throw new BusinessException(ErrorCode.SURVEY_ANSWER_INVALID,
                    "익명 설문은 응답 내용을 다시 볼 수 없습니다.");
        }
        return responseRepository.findMine(surveyId, enrollmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SURVEY_NOT_FOUND,
                        "아직 응답하지 않았습니다."));
    }

    // ── 답 검증 ──────────────────────────────────────────

    /**
     * 답을 문항에 맞춰 채운다.
     *
     * <p>유형이 맞는지, 이 설문의 문항인지, 그 문항의 선택지인지, 범위 안인지를 본다.
     * <b>남의 문항 id를 보내 다른 설문에 답을 심는 걸 막는 게 핵심</b>이다.
     */
    private void fillAnswers(Survey survey, SurveyResponse response,
                             List<AnswerCommand> answers) {
        Map<Long, SurveyQuestion> questions = survey.activeQuestions().stream()
                .collect(Collectors.toMap(SurveyQuestion::getId, Function.identity()));
        Set<Long> answered = new HashSet<>();

        for (AnswerCommand answer : answers) {
            SurveyQuestion question = questions.get(answer.questionId());
            if (question == null) {
                throw new BusinessException(ErrorCode.SURVEY_QUESTION_NOT_FOUND,
                        "이 설문의 문항이 아닙니다: " + answer.questionId());
            }

            switch (question.getQuestionType()) {
                case SINGLE_CHOICE, MULTI_CHOICE -> {
                    List<Long> optionIds = answer.optionIds() == null
                            ? List.of() : answer.optionIds();
                    if (optionIds.isEmpty()) {
                        continue;   // 미응답. 필수 여부는 아래에서 한꺼번에 본다
                    }
                    if (question.getQuestionType() == SurveyQuestionType.SINGLE_CHOICE
                            && optionIds.size() > 1) {
                        throw new BusinessException(ErrorCode.SURVEY_ANSWER_INVALID,
                                "단일 선택 문항에 여러 개를 골랐습니다.");
                    }
                    Map<Long, SurveyQuestionOption> options = question.activeOptions().stream()
                            .collect(Collectors.toMap(SurveyQuestionOption::getId,
                                    Function.identity()));
                    for (Long optionId : optionIds.stream().distinct().toList()) {
                        SurveyQuestionOption option = options.get(optionId);
                        if (option == null) {
                            throw new BusinessException(ErrorCode.SURVEY_OPTION_NOT_FOUND,
                                    "이 문항의 선택지가 아닙니다: " + optionId);
                        }
                        response.addChoice(question, option);
                    }
                }
                case TEXT -> {
                    if (answer.textValue() == null || answer.textValue().isBlank()) {
                        continue;
                    }
                    response.addText(question, answer.textValue().trim());
                }
                case NUMBER -> {
                    if (answer.numberValue() == null) {
                        continue;
                    }
                    if (!question.inRange(answer.numberValue())) {
                        throw new BusinessException(ErrorCode.SURVEY_ANSWER_INVALID,
                                "'%s' 문항의 입력 범위를 벗어났습니다.".formatted(question.getTitle()));
                    }
                    response.addNumber(question, answer.numberValue());
                }
            }
            answered.add(question.getId());
        }

        // 필수 누락은 마지막에 한 번에 본다 — 하나씩 알려주면 사용자가 여러 번 왕복한다
        List<String> missing = survey.activeQuestions().stream()
                .filter(SurveyQuestion::isRequired)
                .filter(q -> !answered.contains(q.getId()))
                .map(SurveyQuestion::getTitle)
                .toList();
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.SURVEY_REQUIRED_ANSWER_MISSING,
                    "필수 문항에 답하지 않았습니다: " + String.join(", ", missing));
        }
    }

    // ── 권한 ─────────────────────────────────────────────

    /** 수정·삭제. 만들 때와 같은 기준을 다시 건다 — 한 번만 검사하면 남의 설문을 고칠 수 있다. */
    private Survey requireWritable(AuthPrincipal me, Long id) {
        Survey survey = surveyRepository.findById(id)
                .filter(s -> !s.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.SURVEY_NOT_FOUND));

        switch (survey.getScope()) {
            case ALL -> requireHeadOffice(me);
            case BRANCH -> requireBranchAdmin(me, survey.getAcademy().getId());
            case CLASS -> requireClassWriter(me, survey.getClassMaster());
        }
        return survey;
    }

    /** 조회(집계). 지점만 맞으면 된다 — 결과는 관리자끼리 공유한다. */
    private Survey requireReadable(AuthPrincipal me, Long id) {
        Survey survey = surveyRepository.findById(id)
                .filter(s -> !s.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.SURVEY_NOT_FOUND));

        if (survey.getAcademy() != null && !me.canAccessAcademy(survey.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return survey;
    }

    /** 전 지점 설문은 본사만. 지점관리자가 실수로 전 지점에 돌리는 걸 막는다. */
    private void requireHeadOffice(AuthPrincipal me) {
        if (!me.allAcademy()) {
            throw new BusinessException(ErrorCode.SURVEY_SCOPE_FORBIDDEN,
                    "전 지점 설문은 본사만 낼 수 있습니다.");
        }
    }

    private void requireBranchAdmin(AuthPrincipal me, Long academyId) {
        if (!me.canAccessAcademy(academyId)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        if (!me.hasRole(Role.SUPER_ADMIN) && !me.hasRole(Role.BRANCH_ADMIN)) {
            throw new BusinessException(ErrorCode.SURVEY_SCOPE_FORBIDDEN,
                    "지점 설문은 지점관리자 이상만 낼 수 있습니다.");
        }
    }

    /**
     * 반 설문은 그 반 담임 또는 지점관리자.
     *
     * <p>지점관리자를 함께 허용한 건 공지와 같은 이유다 — 담임 미지정 반이 있으면
     * 담임만 허용할 경우 그 반은 설문을 아무도 못 낸다.
     */
    private void requireClassWriter(AuthPrincipal me, ClassMaster classMaster) {
        if (!me.canAccessAcademy(classMaster.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        if (me.hasRole(Role.SUPER_ADMIN) || me.hasRole(Role.BRANCH_ADMIN)
                || isHomeroomOf(me, classMaster)) {
            return;
        }
        throw new BusinessException(ErrorCode.SURVEY_SCOPE_FORBIDDEN,
                "반 설문은 그 반 담임 또는 지점관리자만 낼 수 있습니다.");
    }

    private boolean isHomeroomOf(AuthPrincipal me, ClassMaster classMaster) {
        Teacher homeroom = classMaster.getHomeroomTeacher();
        if (homeroom == null) {
            return false;
        }
        return accountRepository.findById(me.accountId())
                .map(Account::getTeacher)
                .map(t -> t.getId().equals(homeroom.getId()))
                .orElse(false);
    }

    private StudentEnrollment requireEnrollment(Long enrollmentId) {
        return enrollmentRepository.findById(enrollmentId)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));
    }

    private short currentYear() {
        return (short) LocalDate.now(clock).getYear();
    }

    // ── 입출력 ───────────────────────────────────────────

    /** 개설 요청. 범위에 맞는 대상만 채운다. */
    public record SurveyCommand(SurveyType surveyType, SurveyScope scope,
                                Long academyId, Long classId,
                                String title, String description, boolean anonymous,
                                Instant opensAt, Instant closesAt,
                                List<QuestionCommand> questions) {
    }

    public record QuestionCommand(SurveyQuestionType type, String title, boolean required,
                                  BigDecimal minValue, BigDecimal maxValue,
                                  List<String> options) {
    }

    /** 제출 요청 한 칸. 유형에 맞는 값만 채운다. */
    public record AnswerCommand(Long questionId, List<Long> optionIds,
                                String textValue, BigDecimal numberValue) {
    }

    /** 앱 표시용 — 설문 + 지금 응답 가능한지 + 내가 냈는지. */
    public record AppSurvey(Survey survey, boolean open, boolean submitted) {
    }

    public record SurveyResult(Survey survey, int responseCount, List<QuestionResult> questions) {
    }

    public record OptionCount(Long optionId, String label, long count) {
    }

    /**
     * 문항별 집계.
     *
     * <p>유형에 따라 채워지는 칸이 다르다 — 선택형은 {@code options},
     * 숫자형은 평균·최소·최대, 주관식은 {@code texts}다.
     */
    public record QuestionResult(Long questionId, String title, SurveyQuestionType type,
                                 long answerCount, List<OptionCount> options,
                                 BigDecimal average, BigDecimal min, BigDecimal max,
                                 List<String> texts) {

        static QuestionResult ofChoice(SurveyQuestion question, long answerCount,
                                       List<OptionCount> options) {
            return new QuestionResult(question.getId(), question.getTitle(),
                    question.getQuestionType(), answerCount, options,
                    null, null, null, List.of());
        }

        static QuestionResult ofNumber(SurveyQuestion question, List<BigDecimal> values) {
            BigDecimal average = values.isEmpty() ? null
                    : values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                            .divide(BigDecimal.valueOf(values.size()), 2,
                                    java.math.RoundingMode.HALF_UP);
            return new QuestionResult(question.getId(), question.getTitle(),
                    question.getQuestionType(), values.size(), List.of(),
                    average,
                    values.stream().min(BigDecimal::compareTo).orElse(null),
                    values.stream().max(BigDecimal::compareTo).orElse(null),
                    List.of());
        }

        static QuestionResult ofText(SurveyQuestion question, List<String> texts) {
            return new QuestionResult(question.getId(), question.getTitle(),
                    question.getQuestionType(), texts.size(), List.of(),
                    null, null, null, texts);
        }
    }
}
