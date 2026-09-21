package com.dlab.domain.grade.service;

import com.dlab.domain.grade.entity.MockExamStudentKey;
import com.dlab.domain.grade.repository.MockExamStudentKeyRepository;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.ClassMaster;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 모의고사 수험번호 채번 (2026-09-21 답변서).
 *
 * <h2>왜 우리가 채번하는가</h2>
 * 연구소는 <b>우리 학번이 곧 모의고사 번호</b>라고 이해하고 있었는데, 우리 학번은
 * {@code 2026-0001} 형태의 <b>지점·연도 일련번호</b>라 반과 무관하다. 자료는
 * {@code 1001}(반 + 3자리 순번)로 들어오므로 둘을 잇는 값이 필요하다.
 *
 * <h2>★ 채번은 반 최초 배정 시 한 번뿐이다</h2>
 * 연구소가 <i>"최초 부여받은 학번은 절대 변경 불가"</i> 라고 명시했다. 반을 옮길 때마다
 * 번호가 바뀌면 <b>지난 회차 성적과 연결이 끊긴다.</b>
 *
 * <h2>채번과 동시에 업로드 키를 만들어 둔다</h2>
 * 업로드가 이름으로 학생을 찾던 이유는 <b>학교코드를 지점과 잇는 값이 없어서</b>였다.
 * 이제 둘 다 있으므로 <b>미리</b> 키를 만들어 두면, 성적 파일이 들어오는 순간 이름을
 * 보지 않고 매칭된다 — 동명이인 때문에 매번 빠지던 문제가 구조적으로 사라진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExamNumberService {

    /** 순번은 1~99다. 자료가 3자리라 100 이상은 표현되지 않는다 */
    private static final short MAX_SEQ = 99;

    private final StudentEnrollmentRepository enrollmentRepository;
    private final MockExamStudentKeyRepository keyRepository;
    private final Clock clock;

    /**
     * 반 배정 시 호출한다.
     *
     * <p><b>조용히 아무것도 하지 않는 경우가 셋 있다</b> — 이미 번호가 있거나, 반에
     * 모의고사 반 번호가 없거나, 순번이 다 찼을 때다. 셋 다 <b>배정 자체를 막을 이유는
     * 아니다</b> — 번호가 없으면 업로드가 이름 매칭으로 떨어질 뿐이다.
     */
    @Transactional
    public void assignOnClassAssigned(StudentEnrollment enrollment, ClassMaster classMaster) {
        if (enrollment.hasExamNo() || classMaster.getExamClassNo() == null) {
            return;
        }
        short classNo = classMaster.getExamClassNo();
        short next = (short) (enrollmentRepository.findMaxExamSeq(
                enrollment.getAcademy().getId(), enrollment.getYear(), classNo) + 1);
        if (next > MAX_SEQ) {
            // 막지 않는다 — 배정은 되어야 하고, 번호는 사람이 정리한 뒤 다시 붙일 수 있다
            log.warn("모의고사 순번이 가득 찼습니다: academyId={}, year={}, classNo={}",
                    enrollment.getAcademy().getId(), enrollment.getYear(), classNo);
            return;
        }

        enrollment.assignExamNo(classNo, next, Instant.now(clock));
        createUploadKey(enrollment, classNo);
    }

    /**
     * 업로드 키를 미리 만든다.
     *
     * <p>지점에 학교코드가 없으면 만들지 않는다 — 세 값이 다 있어야 자료의 행과 이어진다.
     */
    private void createUploadKey(StudentEnrollment enrollment, short classNo) {
        Academy academy = enrollment.getAcademy();
        String schoolCd = academy.getExamSchoolCd();
        if (schoolCd == null || schoolCd.isBlank()) {
            log.warn("지점에 모의고사 학교코드가 없어 업로드 키를 만들지 못했습니다: academyId={}",
                    academy.getId());
            return;
        }
        String classNoText = String.valueOf(classNo);
        String studentNo = enrollment.getExamStudentNo();

        // 사람이 이미 손으로 연결해 둔 칸이면 그대로 둔다 — 그 판단이 우리 추측보다 정확하다
        keyRepository.findByKey(academy.getId(), enrollment.getYear(),
                        schoolCd, classNoText, studentNo)
                .ifPresentOrElse(existing -> { },
                        () -> keyRepository.save(new MockExamStudentKey(
                                academy, enrollment.getYear(), schoolCd,
                                classNoText, studentNo, enrollment)));
    }
}
