package com.dlab.domain.admission.repository;

import com.dlab.domain.admission.entity.AdmissionReservationStatusLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AdmissionReservationStatusLogRepository
        extends JpaRepository<AdmissionReservationStatusLog, Long> {

    @Query("""
            SELECT l FROM AdmissionReservationStatusLog l
            WHERE l.reservation.id = :reservationId AND l.deleted = false
            ORDER BY l.createdAt DESC, l.id DESC
            """)
    List<AdmissionReservationStatusLog> findByReservationId(Long reservationId);

    /**
     * 학생이 공개 링크에서 누른 응답.
     *
     * <p>★ <b>답을 따로 저장하지 않고 이력에서 읽는다.</b> 직원이 나중에 상태를 덮어도
     * {@code toStatus} 는 바뀌지 않으므로 「학생이 무엇을 눌렀는가」가 그대로 남는다 —
     * 답을 별도 컬럼에 또 두면 둘이 어긋날 수 있다.
     *
     * <p>DB 에 부분 유니크 제약이 걸려 있어 실제로는 0건 또는 1건이다
     * ({@code uq_admission_status_log_student_answer}).
     */
    @Query("""
            SELECT l FROM AdmissionReservationStatusLog l
            WHERE l.reservation.id = :reservationId
              AND l.actorType = com.dlab.domain.admission.entity.StatusActorType.STUDENT
              AND l.deleted = false
            ORDER BY l.createdAt ASC, l.id ASC
            """)
    List<AdmissionReservationStatusLog> findStudentAnswers(Long reservationId);
}
