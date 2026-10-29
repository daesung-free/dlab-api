package com.dlab.domain.notification.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.privacy.Masking;
import com.dlab.domain.notification.entity.*;
import com.dlab.domain.notification.repository.NotificationManualPreviewRepository;
import com.dlab.domain.notification.repository.NotificationManualSendRepository;
import com.dlab.domain.notification.repository.NotificationTemplateRepository;
import com.dlab.domain.user.entity.*;
import com.dlab.domain.user.repository.AccountRepository;
import com.dlab.domain.user.repository.ClassAssignmentRepository;
import com.dlab.domain.user.repository.ClassMasterRepository;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 직원이 때를 골라 보내는 알림 (디랩 1016 「직원 발송」 4종).
 *
 * <h2>왜 필요했나</h2>
 * 지금까지 알림은 <b>이벤트가 일어나야 나가는 구조</b>뿐이었다. 템플릿이 이벤트당 하나라
 * (event_code UNIQUE) 「수동 발송 전용 템플릿」이라는 것이 서버에 없었고, 1016 문구 13종
 * 중 4종(대기자·급식 마감·등록비 안내·등록비 미납)이 전부 직원 발송이다.
 *
 * <h2>★★ 미리보기 → 발송 2단계가 설계의 전부다</h2>
 * <ul>
 *   <li><b>미리보기는 아무것도 보내지 않는다.</b> 대상·변수를 확정해 스냅샷으로 저장하고
 *       {@code previewId} 를 돌려준다</li>
 *   <li><b>발송은 {@code previewId} 만 받는다.</b> 조건을 다시 싣게 하면 화면이 미리보기
 *       뒤에 대상을 바꿔도 서버는 모르고, <b>담당자가 본 것과 다른 사람에게 나간다</b></li>
 *   <li><b>{@code previewId} 는 한 번만 먹는다.</b> 버튼 두 번·새로고침으로 같은 학부모에게
 *       두 통이 가면 안 된다 — 알림톡은 <b>건당 과금</b>이다. 코드가 아니라
 *       {@code notification_manual_send} 의 {@code UNIQUE (preview_id)} 가 막는다</li>
 * </ul>
 *
 * <h2>★ 발송은 기존 경로를 그대로 탄다</h2>
 * {@link NotificationService#send} 를 부른다 — 비활성·문구 미확정·심사 미통과·
 * <b>알림톡 템플릿 코드 미등록</b> 게이트를 우회하지 않는다. 우회하면 심사받지 않은 문구가
 * 학부모에게 나가고, 그건 카카오가 거절하거나 받는 사람이 다른 문구를 받는다는 뜻이다.
 *
 * <h2>⚠️ 예약 발송은 만들지 않았다</h2>
 * 「마감 3일 전」은 수동 발송이 아니라 배치가 할 일이고 디랩 확인 대기다.
 * 나중에 얹으면 {@code previewId} 를 들고 기다리는 구조가 되어 <b>유효기간과 충돌</b>한다 —
 * 그래서 예약 필드를 스키마에 미리 두지도 않았다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManualNotificationService {

    private final NotificationTemplateRepository templateRepository;
    private final NotificationManualPreviewRepository previewRepository;
    private final NotificationManualSendRepository sendRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final ClassAssignmentRepository classAssignmentRepository;
    private final ClassMasterRepository classMasterRepository;
    private final AccountRepository accountRepository;
    private final NotificationOptOutPolicy optOutPolicy;
    private final RecipientAllowlist allowlist;
    private final GuardianRecipientResolver recipientResolver;
    private final NotificationService notificationService;
    private final Clock clock;

    // ── 응답 모양 (프런트와 합의된 계약이다 — 바꾸지 말 것) ─────────────

    /**
     * @param phone <b>마스킹된</b> 번호다. 발송 화면에서 전체 번호를 볼 이유가 없다(§7).
     *              {@link Masking} 를 쓰므로 서울 {@code 02} 처럼 국번이 2자리인 번호도
     *              없는 번호처럼 보이지 않는다
     */
    public record RecipientView(Long enrollmentId, String studentName, String studentNo,
                                String className, String receiverName, String phone) {
    }

    /**
     * @param reason  고정된 사유 코드. 화면이 묶어서 셀 수 있어야 한다
     * @param message 사람이 읽는 문장. 코드만 주면 화면마다 다른 말로 번역된다
     */
    public record BlockedView(Long enrollmentId, String studentName,
                              ManualBlockReason reason, String message) {
    }

    public record RenderedView(String title, String body) {
    }

    /**
     * @param blocked          ★ <b>반드시 채운다.</b> 「30명에게 보냅니다」라고 했는데
     *                         28명에게 가면 담당자는 영영 모른다
     * @param missingVariables 템플릿이 요구하는데 직원이 안 채운 변수. 화면이 입력란을
     *                         붉게 표시한다 — 그대로 보내면 발송 경로가 전부 건너뛴다
     */
    public record PreviewResult(String previewId, int targetCount,
                                List<RecipientView> recipients,
                                RenderedView rendered,
                                List<BlockedView> blocked,
                                List<String> missingVariables) {
    }

    public record SendResult(int requested, int skipped, List<Long> logIds) {
    }

    /** 미리보기 요청. {@code academyId} 는 컨트롤러가 이미 검증한 값을 받는다. */
    public record PreviewCommand(Long templateId, Long academyId, ManualSendScope scope,
                                 Long classId, List<Long> enrollmentIds,
                                 Map<String, String> variables) {
    }

    // ── 미리보기 ────────────────────────────────────────────────

    /**
     * 대상을 확정하고 문구를 렌더해 보여준다. <b>아무것도 보내지 않는다.</b>
     */
    @Transactional
    public PreviewResult preview(PreviewCommand command) {
        NotificationTemplate template = templateRepository.findById(command.templateId())
                .filter(t -> !t.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_TEMPLATE_NOT_FOUND));

        // ★ 수동 발송 대상인지 서버가 판정한다. 이벤트 이름으로 추측하게 두면
        //   템플릿이 늘 때마다 화면을 고쳐야 한다
        if (!template.isManual()) {
            throw new BusinessException(ErrorCode.MANUAL_SEND_TEMPLATE_NOT_MANUAL);
        }
        // ★★ 수신자는 템플릿이 정한다. 발송할 때 고르게 하지 않는다 —
        //    수신자는 그 문구의 일부다. 「{studentName} 학생의 등록비가 미납 상태입니다」를
        //    학생 본인에게 보내면 문장이 어긋나고, 같은 템플릿이 날마다 다른 사람에게 간
        //    기록이 남으면 나중에 「누가 받았나」에 답할 수 없다.
        //    ROUTED(승인 주체 설정에 따라 실행 시점에 갈림)·ADMIN(권한으로 정해짐)은
        //    애초에 대상을 여기서 정할 수 없으므로 거절한다
        if (template.getRecipientType() != RecipientType.STUDENT
                && template.getRecipientType() != RecipientType.PARENT
                && template.getRecipientType() != RecipientType.BOTH) {
            throw new BusinessException(ErrorCode.MANUAL_SEND_RECIPIENT_NOT_SUPPORTED);
        }

        short year = (short) LocalDate.now(clock).getYear();
        List<StudentEnrollment> candidates = resolveCandidates(command, year);

        List<ManualSendTarget> targets = new ArrayList<>();
        List<RecipientView> recipients = new ArrayList<>();
        List<BlockedView> blocked = new ArrayList<>();

        Map<Long, String> classNames = classNames(candidates);

        // ★★ 공통 안내는 한 사람에게 한 통만 보낸다.
        //
        // 「11월 급식 신청이 10월 28일에 마감됩니다」 는 학생마다 다르지 않아, 자녀가
        // 둘인 학부모는 **똑같은 문장을 두 통** 받는다. 알림톡은 건당 과금이고 학부모에게는
        // 같은 안내가 반복되는 것으로 보인다.
        //
        // ⚠️ 학생 개인에 관한 알림에는 걸지 않는다 — 출결·미납은 자녀마다 내용이 달라
        // **두 통이 맞다.** 거기서 묶으면 한 자녀의 알림이 사라진다.
        Set<Object> alreadyReceiving = template.isStudentSpecific() ? null : new HashSet<>();

        for (StudentEnrollment enrollment : candidates) {
            // ⚠️ 다른 지점이 섞여 들어오는 경로는 SELECTED 다. 조용히 빼지 않고 드러낸다 —
            //    담당자가 명단을 만든 근거(엑셀·검색)가 잘못됐다는 뜻이다
            if (!enrollment.getAcademy().getId().equals(command.academyId())) {
                blocked.add(block(enrollment, ManualBlockReason.OTHER_ACADEMY));
                continue;
            }
            if (!enrollment.isCurrent()
                    || enrollment.getEnrollmentStatus() != EnrollmentStatus.ENROLLED) {
                blocked.add(block(enrollment, ManualBlockReason.NOT_ENROLLED));
                continue;
            }

            List<Receiver> receivers = receiversOf(enrollment, template.getRecipientType());
            if (receivers.isEmpty()) {
                // ★ 사유가 담당자가 할 일을 정한다. 보호자 수신인데 「앱 가입이 필요합니다」를
                //   주면 ★ 엉뚱한 일을 하게 된다 — 보호자는 번호만 있어도 받으므로(#290)
                //   할 일은 연락처 등록이다.
                blocked.add(block(enrollment, noReceiverReason(template.getRecipientType())));
                continue;
            }
            for (Receiver receiver : receivers) {
                // 공통 안내에서 이미 받는 사람이면 건너뛴다. ★ blocked 에 넣지 않는다 —
                // 「빠진 사람」이 아니라 「이미 받는 사람」이고, 섞으면 담당자가
                // 안 간 줄로 읽는다
                // ⚠️ 계정이 없는 보호자는 accountId 가 null 이다. 번호로 묶는다 —
                //    null 로 묶으면 계정 없는 보호자가 **한 명만 받는다**
                Object dedupeKey = receiver.accountId() != null
                        ? receiver.accountId() : receiver.phone();
                if (alreadyReceiving != null && dedupeKey != null
                        && !alreadyReceiving.add(dedupeKey)) {
                    continue;
                }
                if (receiver.phone() == null || receiver.phone().isBlank()) {
                    blocked.add(block(enrollment, ManualBlockReason.NO_PHONE));
                    continue;
                }
                // ★ 발송 경로(NotificationService.dispatch)와 **같은 판정**을 쓴다.
                //   따로 조회하면 조건이 갈려 「미리보기엔 빠졌는데 실제로는 갔다」가 된다
                // ⚠️ 계정이 없으면 수신 설정이 없다 — 거부 판정이 성립하지 않는다.
                //    필수 알림은 어차피 거부되지 않으므로 통과시키는 것이 맞다
                if (receiver.accountId() != null
                        && optOutPolicy.optedOut(receiver.accountId(), template.getEventCode())) {
                    blocked.add(block(enrollment, ManualBlockReason.OPTED_OUT));
                    continue;
                }
                // ★ 이관 기간 수신 허용목록. 위와 같은 이유로 발송 경로와 같은 곳을 쓴다
                if (!allowlist.allows(receiver.phone())) {
                    blocked.add(block(enrollment, ManualBlockReason.NOT_IN_ALLOWLIST));
                    continue;
                }
                targets.add(new ManualSendTarget(enrollment.getId(),
                        enrollment.getStudent().getId(), receiver.accountId(),
                        receiver.name(), receiver.phone()));
                recipients.add(new RecipientView(enrollment.getId(),
                        enrollment.getStudentName(), enrollment.getStudentNo(),
                        classNames.get(enrollment.getId()), receiver.name(),
                        // ★ 직접 자르지 않는다 — 공통 마스킹이 국번을 재판별한다
                        Masking.phone(receiver.phone())));
            }
        }

        if (targets.isEmpty()) {
            // ★ 여기서 미리보기 행을 만들지 않는다. 만들면 0명에게 보내는 previewId 가
            //   돌아다니고, 담당자는 「발송됨」을 보고 나갔다고 믿는다
            //
            // ★★ 다만 ★ 왜 0명인지는 응답에 싣는다. 사유 없이 「발송 대상이 없습니다」만
            //    주면 담당자는 ★ 무엇을 고쳐야 하는지 알 방법이 없다 — 연락처를 넣어야
            //    하는지, 앱 가입을 안내해야 하는지, 수신거부를 푼 것인지, 허용목록
            //    때문인지가 전부 같은 문장으로 보인다. 2026-10-27 에 프런트가 그 자리에서
            //    막혀, 계정이 없어서 빠진 것인지 허용목록 때문인지 구분하지 못했다.
            throw new BusinessException(ErrorCode.MANUAL_SEND_NO_TARGET,
                    noTargetMessage(blocked), new NoTargetView(blocked));
        }

        Map<String, String> variables = command.variables() == null
                ? Map.of() : new LinkedHashMap<>(command.variables());

        NotificationManualPreview preview = previewRepository.save(
                new NotificationManualPreview(UUID.randomUUID(), template,
                        candidates.get(0).getAcademy(), year, command.scope(),
                        command.classId(), variables, targets, Instant.now(clock)));

        return new PreviewResult(preview.getPreviewId().toString(), targets.size(),
                recipients, render(template, variables, recipients),
                blocked, missingVariables(template, variables));
    }

    // ── 발송 ───────────────────────────────────────────────────

    /**
     * 미리보기에 확정된 명단에 보낸다.
     *
     * <p>★ <b>미리보기를 거치지 않으면 400 이다.</b> 조건을 본문에 다시 받지 않는 것이
     * 이 게이트의 전부다 — 취소 창을 만들지 않은 이유도 같다. 알림톡은 보내는 즉시 나가고
     * 되돌릴 수 없으니, 되돌리는 장치가 아니라 <b>나가기 전에 보는 장치</b>를 둔다.
     */
    @Transactional
    public SendResult send(String previewId, Long academyScope) {
        UUID uuid;
        try {
            uuid = UUID.fromString(previewId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.MANUAL_SEND_PREVIEW_NOT_FOUND);
        }

        NotificationManualPreview preview = previewRepository.findByPreviewId(uuid)
                .orElseThrow(() -> new BusinessException(ErrorCode.MANUAL_SEND_PREVIEW_NOT_FOUND));

        // ⚠️ 지점 범위를 여기서도 본다. 미리보기를 만든 계정과 보내는 계정이 다를 수 있고,
        //    previewId 만 알면 남의 지점 학생에게 보낼 수 있으면 안 된다
        if (academyScope != null && !preview.getAcademy().getId().equals(academyScope)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        if (preview.isExpired(Instant.now(clock))) {
            // ★ 서버가 조용히 갱신해 주지 않는다 — 창을 열어 둔 사이 퇴원한 학생이 섞인다
            throw new BusinessException(ErrorCode.MANUAL_SEND_PREVIEW_EXPIRED);
        }
        if (sendRepository.existsByPreviewId(preview.getId())) {
            throw new BusinessException(ErrorCode.MANUAL_SEND_ALREADY_SENT);
        }

        NotificationManualSend sendRecord =
                new NotificationManualSend(preview, preview.getAcademy(), preview.getYear());
        try {
            // ★★ 1회성은 이 INSERT 가 보장한다. 위 existsBy 는 깔끔한 409 를 위한 것이고,
            //    동시 요청은 UNIQUE 만이 막는다(uq_penalty_point_idempotency 와 같은 판단)
            sendRepository.saveAndFlush(sendRecord);
        } catch (DataIntegrityViolationException e) {
            log.info("수동 발송 중복 요청 차단: previewId={}", previewId);
            throw new BusinessException(ErrorCode.MANUAL_SEND_ALREADY_SENT);
        }

        NotificationTemplate template = preview.getTemplate();
        List<Long> logIds = new ArrayList<>();
        int skipped = 0;

        for (ManualSendTarget target : preview.getTargets()) {
            // ★ 계정이 없는 보호자(앱 미설치)는 번호로 보낸다
            Account recipient = target.accountId() == null ? null
                    : accountRepository.findById(target.accountId()).orElse(null);
            if (target.accountId() != null && (recipient == null || recipient.isDeleted())) {
                // 미리보기 뒤에 계정이 사라진 경우. 이력도 남길 대상이 없다
                skipped++;
                continue;
            }
            StudentEnrollment enrollment =
                    enrollmentRepository.findById(target.enrollmentId()).orElse(null);
            if (enrollment == null) {
                skipped++;
                continue;
            }

            // ★ forStudent 가 studentName 을 자동으로 채운다 — 수신자마다 다른 값이라
            //   직원이 입력할 수 없고, 빠지면 다자녀 학부모가 「이거 누구 얘기지」를 겪는다
            // ★ 발송 경로의 dedupKey 도 함께 건다. 미리보기 1회성이 1차 방어고,
            //   이건 같은 묶음 안에서 같은 수신자가 두 줄일 때를 막는다.
            //   ⚠️ 계정이 없으면 번호를 키에 쓴다 — null 로 두면 계정 없는 보호자끼리
            //      같은 키가 되어 **한 명만 받는다**
            String dedupKey = "MANUAL:%s:%s".formatted(preview.getPreviewId(),
                    target.accountId() != null ? target.accountId() : target.receiverPhone());

            NotificationCommand command = recipient != null
                    ? NotificationCommand.forStudent(
                            template.getEventCode(), recipient, enrollment.getStudent(),
                            preview.getAcademy(), preview.getYear(), preview.getVariables(),
                            dedupKey)
                    : NotificationCommand.forStudentPhone(
                            template.getEventCode(), target.receiverPhone(),
                            target.receiverName(), enrollment.getStudent(),
                            preview.getAcademy(), preview.getYear(), preview.getVariables(),
                            dedupKey);

            NotificationLog sent = notificationService.send(command);
            if (sent == null) {
                skipped++;
                continue;
            }
            logIds.add(sent.getId());
            if (sent.getStatus() != NotificationStatus.SENT) {
                // 문구 미확정·심사 미통과·템플릿 코드 미등록 — 이력은 SKIPPED 로 남는다
                skipped++;
            }
        }

        int requested = preview.getTargets().size();
        sendRecord.recordResult(requested, skipped);
        log.info("수동 발송 실행: event={}, previewId={}, 대상={}, 미발송={}",
                template.getEventCode(), previewId, requested, skipped);
        return new SendResult(requested, skipped, logIds);
    }

    // ── 대상 해석 ───────────────────────────────────────────────

    /**
     * 범위 → 등록 건.
     *
     * <p>⚠️ 나중에 축(대기자 등)을 더할 때 고치는 곳은 <b>여기 하나</b>다.
     */
    private List<StudentEnrollment> resolveCandidates(PreviewCommand command, short year) {
        List<StudentEnrollment> candidates = switch (command.scope()) {
            case BRANCH -> enrollmentRepository.findCurrentByAcademyAndYear(
                    command.academyId(), year);
            case CLASS -> {
                if (command.classId() == null) {
                    throw new BusinessException(ErrorCode.INVALID_REQUEST, "반을 지정해야 합니다.");
                }
                ClassMaster classMaster = classMasterRepository.findById(command.classId())
                        .filter(c -> !c.isDeleted())
                        .orElseThrow(() -> new BusinessException(ErrorCode.CLASS_NOT_FOUND));
                // ⚠️ 반 id 만 믿으면 다른 지점 반을 넣어 그 지점 학생에게 보낼 수 있다
                if (!classMaster.getAcademy().getId().equals(command.academyId())) {
                    throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
                }
                yield classAssignmentRepository.findActiveByClassId(command.classId()).stream()
                        .map(ClassAssignment::getEnrollment)
                        .toList();
            }
            case SELECTED -> {
                if (command.enrollmentIds() == null || command.enrollmentIds().isEmpty()) {
                    throw new BusinessException(ErrorCode.INVALID_REQUEST, "학생을 선택해야 합니다.");
                }
                yield enrollmentRepository.findWithStudentByIds(
                        new LinkedHashSet<>(command.enrollmentIds()));
            }
        };
        if (candidates.isEmpty()) {
            // ★ 여기는 걸러진 것이 아니라 ★ 애초에 고른 학생이 없는 경우다 — 사유 목록이
            //   비어 있으므로 그렇게 말해 준다. 위와 같은 문장을 주면 담당자가 수신 설정을
            //   들여다보게 된다
            throw new BusinessException(ErrorCode.MANUAL_SEND_NO_TARGET,
                    "조건에 맞는 학생이 없습니다. 지점·학년·반 조건을 확인해 주세요.");
        }
        return candidates;
    }

    /** 반 이름. 목록이 수백 명이라 학생마다 조회하지 않고 한 번에 받는다. */
    private Map<Long, String> classNames(List<StudentEnrollment> enrollments) {
        List<Long> ids = enrollments.stream().map(StudentEnrollment::getId).toList();
        Map<Long, String> names = new HashMap<>();
        for (ClassAssignment assignment
                : classAssignmentRepository.findActiveFixedByEnrollmentIds(ids)) {
            names.put(assignment.getEnrollment().getId(),
                    assignment.getClassMaster().getName());
        }
        return names;
    }

    /** 수신 계정 한 명. */
    private record Receiver(Long accountId, String name, String phone) {
    }

    /**
     * 템플릿의 수신자 축 → 실제 계정.
     *
     * <p>★ 학부모는 <b>연결된 전원</b>이다. 부·모 양쪽에 알림이 가는 것이 확정된 구조라
     * (2026-10-12 회신) 한 명만 고르면 다른 한 분이 못 받는다.
     */
    private List<Receiver> receiversOf(StudentEnrollment enrollment, RecipientType type) {
        List<Receiver> receivers = new ArrayList<>();
        if (type == RecipientType.STUDENT || type == RecipientType.BOTH) {
            accountRepository.findByStudentId(enrollment.getStudent().getId())
                    .filter(a -> !a.isDeleted())
                    .ifPresent(a -> receivers.add(new Receiver(a.getId(),
                            enrollment.getStudentName(), enrollment.getStudent().getPhone())));
        }
        if (type == RecipientType.PARENT || type == RecipientType.BOTH) {
            // ★★ 앱을 설치하지 않은 보호자도 수신자다 — 번호만 있으면 보낸다.
            //    해석은 GuardianRecipientResolver 한 곳이고 미등원 배치·출결 알림과
            //    **같은 판정**이다. 갈리면 「미리보기엔 빠졌는데 실제로는 갔다」가 된다.
            for (var recipient : recipientResolver.of(enrollment.getStudent().getId())) {
                receivers.add(new Receiver(recipient.accountId(),
                        recipient.name(), recipient.phone()));
            }
        }
        return receivers;
    }

    /**
     * 대상이 0명일 때 ★ 왜 그런지.
     *
     * <p>{@code blocked} 는 미리보기 성공 응답과 <b>같은 모양</b>이다 — 화면이 두 가지
     * 파싱을 들고 있지 않게 한다. {@code summary} 는 사유별 인원이고, 화면이
     * <b>「연락처 없음 3명 · 허용목록 2명」</b>처럼 한 줄로 쓸 수 있다.
     */
    public record NoTargetView(List<BlockedView> blocked,
                               Map<ManualBlockReason, Long> summary) {
        NoTargetView(List<BlockedView> blocked) {
            this(blocked, blocked.stream().collect(Collectors.groupingBy(
                    BlockedView::reason, LinkedHashMap::new, Collectors.counting())));
        }
    }

    /**
     * 「발송 대상이 없습니다」 뒤에 붙는 ★ 가장 많은 사유.
     *
     * <p>사유가 섞여 있을 때 전부 늘어놓으면 토스트에 들어가지 않는다 — <b>가장 많은 것
     * 하나</b>를 말하고 나머지는 {@code blocked} 에서 보게 한다.
     */
    private String noTargetMessage(List<BlockedView> blocked) {
        if (blocked.isEmpty()) {
            return null;
        }
        Map.Entry<ManualBlockReason, Long> top = blocked.stream()
                .collect(Collectors.groupingBy(BlockedView::reason, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElseThrow();
        return "발송 대상이 없습니다. " + top.getKey().message()
                + " (" + top.getValue() + "명)";
    }

    /**
     * 수신자가 없을 때의 사유 — ★ <b>수신 유형에 따라 다르다.</b>
     *
     * <p>학생 수신은 자기 계정으로만 받으므로 <b>계정이 없다</b>가 맞고, 보호자 수신은
     * <b>번호만 있어도 받으므로</b> 계정이 이유가 될 수 없다. {@code BOTH} 는 둘 다
     * 비었다는 뜻이고, <b>보호자 쪽이 담당자가 먼저 할 일</b>이라 그쪽을 알린다.
     */
    private ManualBlockReason noReceiverReason(RecipientType type) {
        return type == RecipientType.STUDENT
                ? ManualBlockReason.NO_ACCOUNT
                : ManualBlockReason.NO_GUARDIAN;
    }

    private BlockedView block(StudentEnrollment enrollment, ManualBlockReason reason) {
        return new BlockedView(enrollment.getId(), enrollment.getStudentName(),
                reason, reason.message());
    }

    /**
     * 미리보기 문구.
     *
     * <p>★ {@code studentName} 은 <b>첫 수신자 이름으로</b> 채워 보여준다. 수신자마다 값이
     * 다르니 비워 두면 담당자가 「변수가 안 들어갔다」로 읽고, 실제 발송은 수신자별로
     * 제대로 채워진다({@code forStudent}).
     */
    private RenderedView render(NotificationTemplate template, Map<String, String> variables,
                                List<RecipientView> recipients) {
        Map<String, String> sample = new LinkedHashMap<>(variables);
        sample.putIfAbsent(NotificationTemplate.REQUIRED_STUDENT_NAME,
                recipients.isEmpty() ? "" : recipients.get(0).studentName());
        return new RenderedView(replace(template.getTitleTemplate(), sample),
                replace(template.getBodyTemplate(), sample));
    }

    private String replace(String text, Map<String, String> variables) {
        String result = text;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    /**
     * 직원이 안 채운 변수.
     *
     * <p>{@code studentName} 은 <b>빼고</b> 센다 — 서버가 수신자별로 채우는 값이라
     * 목록에 넣으면 담당자가 채울 수 없는 칸을 요구받는다.
     */
    private List<String> missingVariables(NotificationTemplate template,
                                          Map<String, String> variables) {
        return template.requiredVariableSet().stream()
                .filter(name -> !NotificationTemplate.REQUIRED_STUDENT_NAME.equals(name))
                .filter(name -> {
                    String value = variables.get(name);
                    return value == null || value.isBlank();
                })
                .toList();
    }
}
