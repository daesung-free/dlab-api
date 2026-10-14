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

    /**
     * 순번은 반마다 1~99다 (2026-10-13 피드백 1-1).
     *
     * <p>⚠️ <b>100번째 학생은 번호가 없다.</b> 넘칠 때 어떻게 할지는 아직 정해지지 않았다 —
     * 지금은 <b>배정은 되게 하고 번호만 비워 둔다</b>(아래 참고).
     */
    private static final short MAX_SEQ = StudentEnrollment.MAX_SEQ;

    private final StudentEnrollmentRepository enrollmentRepository;
    private final jakarta.persistence.EntityManager entityManager;
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
        lockNumbering(enrollment.getAcademy().getId(), enrollment.getYear(), classNo);
        short next = (short) (enrollmentRepository.findMaxExamSeq(
                enrollment.getAcademy().getId(), enrollment.getYear(), classNo) + 1);
        if (next > MAX_SEQ) {
            // 막지 않는다 — 배정은 되어야 하고, 번호는 사람이 정리한 뒤 다시 붙일 수 있다
            log.warn("모의고사 순번이 가득 찼습니다: academyId={}, year={}, classNo={}",
                    enrollment.getAcademy().getId(), enrollment.getYear(), classNo);
            return;
        }

        enrollment.assignExamNo(classNo, next, Instant.now(clock));
        // ★★ **학번도 같은 값이다** (2026-10-13 피드백 1-1). 디랩이 「반배정 시 번호가
        //    반별로 자동배정」을 요구했고, 그 번호가 곧 학번이다 — 두 벌로 들면
        //    「1001」이 두 가지 뜻을 갖고 키패드 출결·성적 매칭이 서로 다른 값을 본다
        enrollment.assignStudentNoFromExamNo();
        // ★ flush 해서 충돌을 **이 자리에서** 잡는다. 안 하면 커밋 시점에 터져
        //   재시도 지점 바깥으로 예외가 새어나간다(접수 쪽에서 같은 일을 겪었다)
        enrollmentRepository.flush();
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

    /**
     * 그 반의 다음 번호 미리보기.
     *
     * <p>⚠️ <b>예약이 아니다.</b> 배정 시점에 다시 계산하므로 두 사람이 동시에 화면을 열면
     * 같은 번호가 보인다 — 화면에 "예정"이라고 적어야 한다.
     *
     * @return 다음 번호. 반에 모의고사 반 번호가 없거나 <b>99명이 찼으면 {@code null}</b> 이다 —
     *         그때는 화면이 「번호를 더 줄 수 없습니다」를 보여야 한다
     */
    @Transactional(readOnly = true)
    public String previewNext(ClassMaster classMaster) {
        if (classMaster.getExamClassNo() == null) {
            return null;
        }
        short classNo = classMaster.getExamClassNo();
        short next = (short) (enrollmentRepository.findMaxExamSeq(
                classMaster.getAcademy().getId(), classMaster.getYear(), classNo) + 1);
        return next > MAX_SEQ ? null : StudentEnrollment.formatExamNo(classNo, next);
    }

    /**
     * 반 단위 채번 직렬화.
     *
     * <h2>★★ 채번 경합이 접수에서 반 배정으로 옮겨왔다</h2>
     * 전에는 접수 시점에 학번을 발급했고 그래서 {@code StudentService} 에 재시도가 붙어
     * 있었다. 2026-10-13 피드백 1-1 로 <b>반 배정 시점</b> 발급이 되면서 경합도 여기로
     * 왔다 — <b>여러 직원이 같은 반에 동시에 배정하면</b> 둘 다 같은 「최대값 + 1」을 읽고
     * 한 쪽이 유니크 위반으로 터진다.
     *
     * <h2>★ 재시도가 아니라 잠금으로 막는다</h2>
     * 유니크 위반은 트랜잭션을 rollback-only 로 만들어 <b>같은 트랜잭션 안에서 재시도할 수
     * 없다</b>(접수 쪽이 새 트랜잭션을 여는 이유가 그것이다). 여기는 반 배정 트랜잭션
     * 안쪽이라 그 구조를 쓸 수 없다 — 대신 <b>읽기 전에 잠가</b> 경합 자체를 없앤다.
     *
     * <p><b>애플리케이션 락이 아니라 DB 자문 잠금</b>이다. 다중 인스턴스에서도 같은 키로
     * 직렬화되고, 트랜잭션이 끝나면 자동으로 풀린다(`_xact_` 접미사).
     *
     * <p>잠금 범위는 <b>(지점, 연도, 반)</b>이다. 지점 전체로 잠그면 모집 시즌에 배정이
     * 줄을 서고, 더 좁힐 수는 없다 — 순번이 그 세 값 안에서 매겨진다.
     */
    private void lockNumbering(Long academyId, short year, short classNo) {
        // String.hashCode 는 명세로 고정돼 있어 인스턴스가 달라도 같은 값이 나온다
        long key = (academyId + "|" + year + "|exam|" + classNo).hashCode();
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(?1)")
                .setParameter(1, key)
                .getSingleResult();
    }
}
