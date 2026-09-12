package com.dlab.domain.user.repository;

import com.dlab.domain.user.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StudentRepository extends JpaRepository<Student, Long> {

    /** 학부모 자녀연결용 고유ID 조회. */
    Optional<Student> findByUniqueCode(String uniqueCode);

    /**
     * 같은 사람 찾기 — <b>이름·생년월일·연락처가 모두 같아야</b> 같은 사람이다.
     *
     * <p>학생은 "사람 + 등록 건" 2단이라, 재접수는 사람을 새로 만드는 게 아니라
     * <b>기존 사람에 등록 건을 붙이는</b> 것이 맞다. 그런데 신규 접수가 늘 새 사람을
     * 만들고 있어서, 저장 버튼을 두 번 누르면 사람까지 두 명 생겼다.
     *
     * <p>★ <b>연락처가 다르면 다른 사람</b>이다. 이름·생년월일만 맞다고 합치면
     * 동명이인이 한 사람으로 병합된다 — 중복 생성보다 되돌리기 어렵다.
     *
     * <p>셋 중 하나라도 비면 호출하지 않는다. 신원을 특정할 수 없는 상태라
     * 여기서 무엇을 돌려주든 근거가 없다.
     */
    Optional<Student> findByNameAndBirthDateAndPhoneAndDeletedFalse(
            String name, java.time.LocalDate birthDate, String phone);
}
