package com.dlab.domain.master.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.approval.entity.ApprovalItem;
import com.dlab.domain.approval.repository.ApprovalItemRepository;
import com.dlab.domain.master.entity.AdmissionType;
import com.dlab.domain.master.entity.CourseType;
import com.dlab.domain.master.entity.Curriculum;
import com.dlab.domain.master.entity.DepartmentMaster;
import com.dlab.domain.master.repository.AdmissionTypeRepository;
import com.dlab.domain.master.repository.CourseTypeRepository;
import com.dlab.domain.master.repository.CurriculumRepository;
import com.dlab.domain.master.repository.DepartmentMasterRepository;
import com.dlab.domain.penalty.entity.PenaltyItem;
import com.dlab.domain.penalty.entity.PenaltyRule;
import com.dlab.domain.penalty.repository.PenaltyItemRepository;
import com.dlab.domain.penalty.repository.PenaltyRuleRepository;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.ClassMaster;
import com.dlab.domain.user.entity.Teacher;
import com.dlab.domain.user.repository.AcademyRepository;
import com.dlab.domain.user.repository.ClassMasterRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 전년도 복사 (요구사항 F-4.10-1).
 *
 * <p>매년 초 기초 데이터를 전부 다시 세팅하는 작업을 자동화한다. 레거시 운영에서 가장 크게
 * 불편했던 지점이 이것이다.
 *
 * <p><b>★ 단순 {@code INSERT SELECT}로 끝내면 안 된다.</b> 마스터끼리 FK로 물려 있어서,
 * 그대로 복사하면 <b>새 연도 행이 옛 연도 행을 가리키게</b> 된다. 실제로 {@code penalty_rule}이
 * {@code penalty_item}을 참조하는데, 그냥 복사하면 2027년 규칙이 2026년 항목을 가리킨다.
 * 그러면 2026년 항목을 지우는 순간 2027년 규칙이 깨지고, 점수를 고치면 과거 연도까지 같이 바뀐다.
 * 그래서 <b>복사하면서 ID 매핑을 들고 다니며 참조를 새 연도 것으로 갈아끼운다.</b>
 * (참조가 없는 {@code period_master} 하나만 예외적으로 INSERT SELECT다 — 아래 참고.)
 *
 * <p>복사 순서는 참조하는 쪽이 나중에 오도록 한다:
 * <pre>
 *   학과 · 전형 · 교시 · 승인정책 (서로 독립)
 *   과정 → 반 → 커리큘럼   (반이 과정을, 커리큘럼이 반을 참조)
 *   상벌점 항목 → 상벌점 규칙 (규칙이 항목을 참조)
 * </pre>
 *
 * <p><b>멱등하지 않다</b> — 대상 연도에 이미 데이터가 있으면 거부한다. 두 번 돌리면 기초
 * 데이터가 두 벌이 되는데, 예컨대 반이 중복되면 학생 배정이 어느 쪽으로 갔는지 알 수 없어진다.
 * 다시 하려면 대상 연도를 비우고 해야 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class YearlySnapshotService {

    private final AcademyRepository academyRepository;
    private final DepartmentMasterRepository departmentRepository;
    private final CourseTypeRepository courseTypeRepository;
    private final CurriculumRepository curriculumRepository;
    private final AdmissionTypeRepository admissionTypeRepository;
    private final ClassMasterRepository classMasterRepository;
    private final ApprovalItemRepository approvalItemRepository;
    private final PenaltyItemRepository penaltyItemRepository;
    private final PenaltyRuleRepository penaltyRuleRepository;

    @PersistenceContext
    private EntityManager em;

    /** 복사 결과. 무엇이 몇 건 넘어갔는지 보여줘야 관리자가 확인하고 넘어갈 수 있다. */
    public record SnapshotResult(short fromYear, short toYear, Map<String, Integer> copied) {

        public int total() {
            return copied.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    @Transactional
    public SnapshotResult copy(Long academyId, short fromYear, short toYear, AuthPrincipal principal) {
        if (!principal.canAccessAcademy(academyId)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        if (fromYear == toYear) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "복사 대상 연도가 원본과 같습니다.");
        }
        Academy academy = academyRepository.findById(academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));

        verifyTargetEmpty(academyId, toYear);

        Map<String, Integer> copied = new LinkedHashMap<>();
        copied.put("department", copyDepartments(academy, fromYear, toYear));
        copied.put("admissionType", copyAdmissionTypes(academy, fromYear, toYear));
        copied.put("period", copyPeriods(academyId, fromYear, toYear));

        // 과정 → 반 순서. 반이 과정을 참조하므로 매핑을 넘겨받는다.
        Map<Long, CourseType> courseMapping = copyCourseTypes(academy, fromYear, toYear);
        copied.put("courseType", courseMapping.size());
        Map<Long, ClassMaster> classMapping = copyClasses(academy, fromYear, toYear, courseMapping);
        copied.put("class", classMapping.size());
        // 반 → 커리큘럼 순서. 커리큘럼이 반을 참조하므로 세 번째 갈아끼움이다.
        copied.put("curriculum", copyCurriculums(academy, fromYear, toYear, classMapping));
        copied.put("approvalItem", copyApprovalItems(academy, fromYear, toYear));

        // 상벌점은 항목 → 규칙 순서. 규칙이 항목을 참조하므로 매핑을 넘겨받아야 한다.
        Map<Long, PenaltyItem> itemMapping = copyPenaltyItems(academy, fromYear, toYear);
        copied.put("penaltyItem", itemMapping.size());
        copied.put("penaltyRule", copyPenaltyRules(academy, fromYear, toYear, itemMapping));

        SnapshotResult result = new SnapshotResult(fromYear, toYear, copied);
        log.info("전년도 복사 완료: academy={}, {} → {}, 합계 {}건 {}",
                academyId, fromYear, toYear, result.total(), copied);
        return result;
    }

    /**
     * 대상 연도가 비어 있는지 확인한다.
     *
     * <p>복사되는 표를 전부 확인한다 — 일부만 보면, 예컨대 학과만 손으로 만들어 둔 연도에
     * 복사가 통과해서 학과만 두 벌이 되는 상태가 만들어진다.
     */
    private void verifyTargetEmpty(Long academyId, short toYear) {
        boolean hasData =
                !departmentRepository.search(academyId, toYear).isEmpty()
                        || !classMasterRepository.search(academyId, toYear).isEmpty()
                        || countPeriods(academyId, toYear) > 0
                        || !courseTypeRepository
                                .findByAcademyIdAndYearAndDeletedFalseOrderBySortOrderAscNameAsc(
                                        academyId, toYear).isEmpty()
                        || !curriculumRepository.findAllOfYear(academyId, toYear).isEmpty()
                        || !admissionTypeRepository
                                .findByAcademyIdAndYearAndDeletedFalseOrderBySortOrderAscNameAsc(
                                        academyId, toYear).isEmpty()
                        || !approvalItemRepository.findByAcademyIdAndYearAndDeletedFalse(academyId, toYear).isEmpty()
                        || !penaltyItemRepository
                                .findByAcademyIdAndYearAndDeletedFalseOrderByItemNameAsc(academyId, toYear).isEmpty()
                        || !penaltyRuleRepository.findAllOfYear(academyId, toYear).isEmpty();

        if (hasData) {
            throw new BusinessException(ErrorCode.SNAPSHOT_TARGET_NOT_EMPTY,
                    "%d년에 이미 기초 데이터가 있습니다. 비운 뒤 다시 시도하세요.".formatted(toYear));
        }
    }

    private int copyDepartments(Academy academy, short fromYear, short toYear) {
        List<DepartmentMaster> sources = departmentRepository.search(academy.getId(), fromYear);
        sources.forEach(src -> {
            DepartmentMaster copy = departmentRepository.save(
                    new DepartmentMaster(academy, toYear, src.getName()));
            copy.markCopiedFrom(src.getId());
        });
        return sources.size();
    }

    /**
     * 교시 복사.
     *
     * <p><b>여기만 네이티브 INSERT SELECT다.</b> {@code period_master}는 아직 엔티티가 없고
     * (교시 편집 화면은 다른 담당자 몫이다), 이 표는 다른 연도별 마스터를 참조하지 않아
     * 갈아끼울 FK가 없다. 그래서 엔티티를 먼저 만들어 작업이 겹치는 대신 표만 그대로 복사한다.
     * 엔티티가 생기면 위 학과·반과 같은 형태로 옮기면 된다.
     */
    private int copyPeriods(Long academyId, short fromYear, short toYear) {
        return em.createNativeQuery("""
                        INSERT INTO period_master
                            (academy_id, year, period_no, name, start_time, end_time, copied_from_id)
                        SELECT academy_id, :toYear, period_no, name, start_time, end_time, id
                        FROM period_master
                        WHERE academy_id = :academyId AND year = :fromYear AND is_deleted = FALSE
                        """)
                .setParameter("academyId", academyId)
                .setParameter("fromYear", fromYear)
                .setParameter("toYear", toYear)
                .executeUpdate();
    }

    private int countPeriods(Long academyId, short toYear) {
        Number count = (Number) em.createNativeQuery("""
                        SELECT COUNT(*) FROM period_master
                        WHERE academy_id = :academyId AND year = :year AND is_deleted = FALSE
                        """)
                .setParameter("academyId", academyId)
                .setParameter("year", toYear)
                .getSingleResult();
        return count.intValue();
    }

    /**
     * 반 복사.
     *
     * <p>담임은 그대로 가져오되 <b>퇴사자는 비운다</b> — 퇴사한 선생님이 담임으로 남으면
     * 그 반 학생의 승인 요청이 아무에게도 가지 않는다(§3 에스컬레이션 대상은 담임에서 도출된다).
     */
    /** @return 옛 반 ID → 새 반. 커리큘럼이 참조를 갈아끼울 때 쓴다. */
    private Map<Long, ClassMaster> copyClasses(Academy academy, short fromYear, short toYear,
                                               Map<Long, CourseType> courseMapping) {
        Map<Long, ClassMaster> mapping = new HashMap<>();
        for (ClassMaster src : classMasterRepository.search(academy.getId(), fromYear)) {
            ClassMaster copy = classMasterRepository.save(new ClassMaster(
                    academy, toYear, src.getName(), src.getClassType(),
                    activeTeacherOrNull(src.getHomeroomTeacher())));
            copy.markCopiedFrom(src.getId());
            // ★ 과정 참조를 새 연도 것으로 갈아끼운다. 그냥 두면 새 연도 반이 옛 과정을 가리킨다.
            if (src.getCourseType() != null) {
                copy.assignCourseType(courseMapping.get(src.getCourseType().getId()));
            }
            mapping.put(src.getId(), copy);
        }
        return mapping;
    }

    /** 커리큘럼 복사 — <b>반 참조를 새 연도 반으로 갈아끼운다.</b> */
    private int copyCurriculums(Academy academy, short fromYear, short toYear,
                                Map<Long, ClassMaster> classMapping) {
        List<Curriculum> sources = curriculumRepository.findAllOfYear(academy.getId(), fromYear);
        for (Curriculum src : sources) {
            ClassMaster newClass = src.getClassMaster() == null
                    ? null
                    : classMapping.get(src.getClassMaster().getId());
            Curriculum copy = curriculumRepository.save(new Curriculum(
                    academy, toYear, src.getName(), newClass, src.getSortOrder()));
            copy.markCopiedFrom(src.getId());
        }
        return sources.size();
    }

    /** @return 옛 과정 ID → 새 과정. 반이 참조를 갈아끼울 때 쓴다. */
    private Map<Long, CourseType> copyCourseTypes(Academy academy, short fromYear, short toYear) {
        Map<Long, CourseType> mapping = new HashMap<>();
        List<CourseType> sources = courseTypeRepository
                .findByAcademyIdAndYearAndDeletedFalseOrderBySortOrderAscNameAsc(academy.getId(), fromYear);
        for (CourseType src : sources) {
            CourseType copy = courseTypeRepository.save(
                    new CourseType(academy, toYear, src.getName(), src.getSortOrder()));
            copy.markCopiedFrom(src.getId());
            mapping.put(src.getId(), copy);
        }
        return mapping;
    }

    /** 전형은 등록 건이 참조하는데, 등록 건은 연도마다 새로 만들어지므로 갈아끼울 대상이 없다. */
    private int copyAdmissionTypes(Academy academy, short fromYear, short toYear) {
        List<AdmissionType> sources = admissionTypeRepository
                .findByAcademyIdAndYearAndDeletedFalseOrderBySortOrderAscNameAsc(academy.getId(), fromYear);
        sources.forEach(src -> {
            AdmissionType copy = admissionTypeRepository.save(
                    new AdmissionType(academy, toYear, src.getName(), src.getSortOrder()));
            copy.markCopiedFrom(src.getId());
        });
        return sources.size();
    }

    private Teacher activeTeacherOrNull(Teacher teacher) {
        if (teacher == null || teacher.isResigned() || teacher.isDeleted()) {
            return null;
        }
        return teacher;
    }

    private int copyApprovalItems(Academy academy, short fromYear, short toYear) {
        List<ApprovalItem> sources =
                approvalItemRepository.findByAcademyIdAndYearAndDeletedFalse(academy.getId(), fromYear);
        sources.forEach(src -> {
            ApprovalItem copy = approvalItemRepository.save(new ApprovalItem(
                    academy, toYear, src.getRequestType(), src.getApproverType(),
                    src.getTimeoutMinutes(), src.getEscalationApproverType()));
            copy.markCopiedFrom(src.getId());
        });
        return sources.size();
    }

    /** @return 옛 항목 ID → 새 항목. 규칙이 참조를 갈아끼울 때 쓴다. */
    private Map<Long, PenaltyItem> copyPenaltyItems(Academy academy, short fromYear, short toYear) {
        Map<Long, PenaltyItem> mapping = new HashMap<>();
        List<PenaltyItem> sources = penaltyItemRepository
                .findByAcademyIdAndYearAndDeletedFalseOrderByItemNameAsc(academy.getId(), fromYear);
        for (PenaltyItem src : sources) {
            PenaltyItem copy = penaltyItemRepository.save(new PenaltyItem(
                    academy, toYear, src.getItemName(), src.getPointValue(), src.getCategory()));
            copy.markCopiedFrom(src.getId());
            mapping.put(src.getId(), copy);
        }
        return mapping;
    }

    /**
     * 규칙 복사 — <b>참조를 새 연도 항목으로 갈아끼운다.</b>
     *
     * <p>여기가 "단순 INSERT SELECT 금지"의 핵심이다. 그냥 복사하면 새 연도 규칙이
     * 옛 연도 항목을 가리켜, 옛 항목을 지우거나 점수를 바꾸면 새 연도가 함께 망가진다.
     *
     * <p>{@code active}는 복사하지 않는다 — 새 {@link PenaltyRule}은 항상 꺼진 채로 만들어진다.
     * 규칙 매핑 자체가 아직 미확정(I-5)이라, 연도가 바뀌자마자 자동 부여가 켜지면 안 된다.
     */
    private int copyPenaltyRules(Academy academy, short fromYear, short toYear,
                                 Map<Long, PenaltyItem> itemMapping) {
        int count = 0;
        for (PenaltyRule src : penaltyRuleRepository.findAllOfYear(academy.getId(), fromYear)) {
            PenaltyItem newItem = itemMapping.get(src.getPenaltyItem().getId());
            if (newItem == null) {
                // 참조하던 항목이 이미 삭제된 규칙. 갈아끼울 대상이 없으므로 건너뛴다.
                log.warn("규칙 복사 건너뜀 — 대상 항목 없음: ruleId={}, itemId={}",
                        src.getId(), src.getPenaltyItem().getId());
                continue;
            }
            PenaltyRule copy = penaltyRuleRepository.save(new PenaltyRule(
                    academy, toYear, src.getTriggerType(), src.getTriggerCondition(), newItem));
            copy.markCopiedFrom(src.getId());
            count++;
        }
        return count;
    }
}
