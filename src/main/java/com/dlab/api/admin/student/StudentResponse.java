package com.dlab.api.admin.student;

import com.dlab.common.privacy.PersonalDataPolicy;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.user.entity.*;
import com.dlab.domain.user.service.StudentListEnricher;

import java.time.LocalDate;
import java.util.List;

/**
 * 학생 응답.
 *
 * <p>{@code studentId}(사람)와 {@code enrollmentId}(등록 건)를 <b>둘 다 내려준다</b> —
 * 상담·신상기록부는 사람 단위, 출결·청구는 등록 건 단위라 클라이언트가 둘을 구분해야 한다.
 *
 * <h2>다른 테이블에 있는 값은 {@link StudentListEnricher}가 붙인다</h2>
 * 반·담임·좌석·장학은 등록 건과 1:N인 별도 테이블이라 여기서 직접 꺼내면 <b>행마다 쿼리가
 * 나간다</b>. 그래서 이 record는 이미 모아둔 {@code Extras}를 읽기만 한다 —
 * 엔티티를 타고 들어가는 코드를 여기에 쓰지 말 것.
 *
 * <h2>★ 개인정보는 권한에 따라 마스킹된다</h2>
 * 전화·주소·생년월일은 실행가이드 3.2가 <b>상위 관리자 전용</b>으로 지정한 값이다
 * ({@link PersonalDataPolicy}). 이 엔드포인트는 {@code STAFF}도 호출하므로 판정을 거쳐
 * 내리고, 화면이 "번호가 잘못 저장됐다"고 오인하지 않도록 {@code masked}를 함께 싣는다.
 *
 * @param birthDate        <b>문자열</b>이다 — 마스킹되면 {@code 2007-**-**}이라 날짜 타입에
 *                         담기지 않는다. 원본일 때도 같은 타입이어야 화면이 분기하지 않는다
 * @param academyId        지점 id. 이름만 내리면 화면이 지점으로 거르거나 다른 API 를 호출할 때
 *                         이름을 다시 id 로 되짚어야 한다 — 동명 지점이 생기면 그 되짚기가 깨진다
 * @param academyName      지점명. 본사 계정이 전 지점을 한 화면에서 보므로 코드가 아니라 이름이다
 * @param className        고정반 이름. 미배정이면 {@code null}
 * @param homeroomTeacher  담임(담당선생님) 이름. 반을 통해 나온다 — 반 미배정이거나
 *                         반에 담임이 없으면 {@code null}
 * @param seatCd           현재 좌석 코드. 미배정이면 {@code null}
 * @param scholarshipTypes 장학 유형. <b>여러 건일 수 있어 목록</b>이고, 없으면 빈 목록이다
 * @param homeroomOverridden 담임 예외 지정 여부. {@code true} 면 {@code homeroomTeacher} 가
 *                           반 담임이 아니라 지정된 선생님이다
 * @param homeroomOverride   담임 예외 지정 상세 — {@code PUT .../homeroom-override} 응답과 같은 모양이다.
 *                           지정이 없으면 {@code null}
 * @param masked           개인정보가 가려졌는지. 화면이 "원본 보기" 안내를 띄우는 근거다
 */
public record StudentResponse(
        Long enrollmentId,
        Long studentId,
        String uniqueCode,
        String studentNo,
        String name,
        String phone,
        /**
         * 학부모 대표 연락처 — 출결 현황의 {@code guardianPhone}과 같은 값이다.
         * 보호자가 여럿이면 승인자 → 관계 순서로 첫 번째. 없으면 비어 있다.
         * 학생 연락처와 같이 <b>권한에 따라 마스킹</b>된다({@code masked}).
         */
        String guardianPhone,
        String address,
        String birthDate,
        /**
         * 성별 {@code M}/{@code F}.
         *
         * <p>★ <b>등록에서는 받는데 조회에 없었다.</b> 넣은 값을 다시 꺼낼 방법이 없어
         * 화면에서는 저장됐는지조차 확인되지 않았다.
         */
        String gender,
        String schoolName,
        /** 영문명(선택) */
        String englishName,
        /** 고교 졸업연도(선택) */
        Short graduationYear,
        short year,
        GradeType grade,
        /** N수 차수 — 1=재수, 2=삼수. {@code grade}가 N_SU 가 아니면 비어 있다 */
        Short retakeCount,
        TrackType track,
        EnrollmentStatus enrollmentStatus,
        LocalDate admissionDate,
        Long academyId,
        String academyName,
        String className,
        String homeroomTeacher,
        String seatCd,
        List<String> scholarshipTypes,
        boolean homeroomOverridden,
        AdminStudentController.HomeroomOverrideView homeroomOverride,
        boolean masked
) {

    public static StudentResponse from(StudentEnrollment e, StudentListEnricher.Extras extras,
                                       AuthPrincipal me) {
        Student s = e.getStudent();
        Long id = e.getId();
        boolean masked = !PersonalDataPolicy.canViewRaw(me);

        return new StudentResponse(
                id,
                s.getId(),
                s.getUniqueCode(),
                e.getStudentNo(),
                s.getName(),
                PersonalDataPolicy.phone(me, s.getPhone()),
                PersonalDataPolicy.phone(me, extras.guardianPhone(id)),
                PersonalDataPolicy.address(me, s.getAddress()),
                PersonalDataPolicy.birthDate(me, s.getBirthDate()),
                s.getGender(),
                s.getSchoolName(),
                s.getEnglishName(),
                s.getGraduationYear(),
                e.getYear(),
                e.getGrade(),
                e.getRetakeCount(),
                e.getTrack(),
                e.getEnrollmentStatus(),
                e.getAdmissionDate(),
                e.getAcademy().getId(),
                e.getAcademy().getName(),
                extras.className(id),
                extras.homeroomTeacher(id),
                extras.seatCd(id),
                extras.scholarships(id),
                e.getHomeroomOverride() != null,
                // 지정된 학생만 선생님 이름을 읽는다 — 드문 경우라 목록에서 학생마다 쿼리가 나가지 않는다
                e.getHomeroomOverride() == null ? null
                        : AdminStudentController.HomeroomOverrideView.from(e),
                masked);
    }
}
