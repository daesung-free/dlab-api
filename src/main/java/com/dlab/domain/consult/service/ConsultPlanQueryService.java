package com.dlab.domain.consult.service;

import com.dlab.domain.consult.entity.ConsultLog;
import com.dlab.domain.consult.entity.ParentShare;
import com.dlab.domain.consult.repository.ConsultLogRepository;
import com.dlab.domain.user.entity.StudentEnrollment;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 앱 — 지난 상담의 「함께 정한 계획」 (시안 4.8-③).
 *
 * <h2>★ 상담 내용은 내리지 않는다</h2>
 * 시안이 <b>학생과 함께 정한 계획만</b> 보여주기로 했다. 상담일지 본문은 담임이 학생과 나눈
 * 이야기를 그대로 적은 것이라, 앱에 나가면 담임이 일지를 조심해서 쓰게 되고 기록 가치가
 * 떨어진다.
 *
 * <h2>★ 학부모는 담임이 공개한 상담만 본다</h2>
 * 관리자 웹은 상담마다 학부모 공개 범위({@link ParentShare})를 정하고 <b>기본값이 비공개</b>다.
 * 시안은 "학부모도 같은 구성" 인데, 그대로 따르면 담임이 비공개로 둔 상담의 계획까지
 * 학부모에게 간다. <b>공개로 지정한 상담만</b> 내린다 — 공개 설정을 무시하면 그 설정이
 * 의미를 잃는다.
 */
@Service
@RequiredArgsConstructor
public class ConsultPlanQueryService {

    private final ConsultLogRepository consultLogRepository;

    /**
     * @param forParent 학부모가 보는가. {@code true} 면 공개 지정된 상담만 나온다
     */
    @Transactional(readOnly = true)
    public List<Plan> plans(StudentEnrollment enrollment, boolean forParent) {
        return consultLogRepository.findByEnrollment(enrollment.getId()).stream()
                .filter(log -> log.getActionPlan() != null && !log.getActionPlan().isBlank())
                .filter(log -> !forParent || log.getParentShare() != ParentShare.NONE)
                .map(Plan::of)
                .toList();
    }

    /**
     * @param actionDone  다음 상담에서 이행을 확인했는가
     * @param nextDueDate 다음 상담 예정일
     */
    public record Plan(Long consultId, LocalDate consultedAt, String consultType,
                       String teacherName, String actionPlan, boolean actionDone,
                       LocalDate nextDueDate) {

        static Plan of(ConsultLog log) {
            return new Plan(log.getId(), log.getConsultedAt(), log.getConsultType().name(),
                    log.getTeacher() == null ? null : log.getTeacher().getName(),
                    log.getActionPlan(), log.isActionDone(), log.getNextDueDate());
        }
    }
}
