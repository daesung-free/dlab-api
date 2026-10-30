# 개인정보 보존기간 — 테이블 대응표 (초안)

**작성** 2026-10-30 · 기준 코드 `develop 0e58041` · 운영 DB 실측(테이블 139개)

> ★ **요청 3항의 산출물이다.** 보존기간을 「퇴원 후 N년」 하나로 정할 수 없고 **자료 종류마다
> 다르므로**, 먼저 **우리 테이블이 어느 종류에 해당하는지**를 정해야 한다.

⚠️ **아래 분류는 개발팀 판단이다.** 법 해석이 필요한 칸은 ★로 표시했다 — **확정 전에는
파기 배치를 만들지 않는다.**

---

## 0. 먼저 — 요청 1항 확인

★ **「퇴원 후 1년」 고정 파기 배치는 만들지 않았고, 만들고 있지도 않다.**

```
파기 배치                   ★ 없음
AppWithdrawalService        주석에 「정책 확정 후 배치로」만 적혀 있다
FileAttachmentService       「정책 미확정」으로 남아 있다
AdminAuditLogController     ★ audit.retention-years (기본 3) — 설정값으로 뺐다
```

★ **감사 로그는 설정값으로 뺐다**(2026-10-30) — `audit.retention-years`, 기본 **3년**.
⚠️ 파기가 아니라 **조회 기간**이다. 1년으로 박혀 있던 동안 **1년 넘은 이력이 화면에서 안
보였는데**, 데이터는 남아 있으므로 **올린 만큼 다시 보인다.**

---

## 1. 개인을 알아볼 수 있는 값을 **직접 가진** 테이블 — 7개

★ 요청 4항(「이름·연락처만 지우고 금액·일자·건수는 남긴다」)의 **실제 대상**이다.

| 테이블 | 개인정보 컬럼 | 자료 종류 | 기준일 |
|---|---|---|---|
| `student` | `name` `phone` `birth_date` `address` | ★ 수강생 대장 **3년** | 마지막 퇴원일 |
| `parent_guardian` | `name` `phone` | ★ 수강생 대장 **3년** | 연결된 학생의 마지막 퇴원일 |
| `employee` | `name` `phone` `email` | **직원 명부 — 계속 보존** | — |
| `teacher` | `name` `phone` `email` | **직원 명부 — 계속 보존** | — |
| `admission_reservation` | `student_name` `student_tel` `parent_tel` `birth` `zip` `addr1` `addr2` | ★★ **아래 3-1 참고** | 신청일 |
| `notification_log` | `recipient_name` `recipient_phone` | ★★ **아래 3-2 참고** | 발송일 |
| `door_code_request` | `phone_masked` | 해당 없음 | 요청일 |

★ **나머지 132개 테이블은 이름·연락처를 갖지 않는다** — 학생 FK 로만 사람과 이어진다.
그래서 **`student`·`parent_guardian` 두 행만 익명화하면** 나머지는 **금액·일자·건수만 남은
통계 자료**가 된다.

⚠️ **그것이 요청 4항이 성립하는 이유다.** 테이블을 하나하나 지우는 작업이 아니다.

---

## 2. 자료 종류별 대응

### 2-1. 수강생 대장 — **3년** (학원법 별표 2)

```
student · student_enrollment · student_status_log · class_assignment
attendance_tagging_log · attendance_daily_status · attendance_modification
absence_reason · morning_attendance_check
seat_assignment · seat_leave_log
parent_guardian · student_guardian_link
```

★ **출결을 수강생 대장에 넣었다** — 「수강 사실」을 증명하는 자료이고, 학원법이 요구하는
장부의 실질이 그것이다. ⚠️ **법 해석 확인이 필요한 칸이다.**

### 2-2. 교습비 영수증 원부 · 수입·지출 · 대금결제 — **5년**

```
billing · billing_item · payment_transaction · payment_request
cash_receipt · refund · refund_calculation · retroactive_charge
tuition · tuition_month · tuition_price · billing_standard · discount_policy
```

★ **세 종류가 같은 테이블에 걸친다**(영수증 원부 5년 · 수입·지출 5년 · 대금결제 5년).
**연수가 같아 실무상 하나로 묶인다.**

⚠️ **`billing` 은 섞여 있다** — 교습비와 **급식비**가 한 테이블이다(`billing_type`).
급식비는 교습비 영수증 원부가 아니라 **대금결제 기록**이다. ★ 연수가 같아 지금은 문제가
없지만, **다르게 정해지면 `billing_type` 으로 갈라야 한다.**

### 2-3. 계약 · 청약철회 — **5년** (전자상거래법)

```
terms · term_agreement · approver_preference
meal_order · meal_order_item · meal_order_window · meal_policy · meal_closure
lecture_application
```

★ **약관 동의 이력을 여기 넣었다** — *「그때 어떤 문구에 동의했는가」*가 계약 기록이다.
⚠️ **약관 동의는 지우면 근거가 사라진다** — 익명화 대상이 아니라 **보존 대상**이다.

### 2-4. 소비자 불만 · 분쟁처리 — **3년**

```
qna_offline_reservation · qna_offline_slot
notice(1:1 성격 글) · survey_response · survey_answer
```

⚠️ **「불만·분쟁」에 해당하는 자료가 우리 쪽에 명확히 없다.** CS 상담은 **구글시트로 유지**하기로
했고(CLAUDE.md §2), 질의응답은 학습 질문이다. ★ **해당 없음으로 볼지 확인이 필요하다.**

### 2-5. 직원 명부 — **계속 보존**

```
employee · teacher · account(직원분) · account_role · account_menu
staff_attendance · staff_attendance_edit
```

⚠️ **`account` 는 섞여 있다** — 학생·학부모·직원이 **한 테이블**이다(`account_type`).
★ **직원은 계속 보존, 학생·학부모는 3년**이라 **`account_type` 으로 갈라야 한다.**

### 2-6. 표시 · 광고 — **6개월**

```
notice(공지·배너) · notice_target · notice_read
```

★ **마케팅 동의로 보낸 광고성 발송 기록**이 여기다. ⚠️ **지금 그 기록이 따로 없다** —
`notification_log` 에 정보성과 광고성이 섞여 있다(3-2).

### 2-7. ★ 해당 없음 — **법정 보존 근거 없음, 퇴원 후 바로 파기**

★ **앱이 수집한 정보와 내부 메모**다. 요청 3항이 가장 중요하다고 한 칸이다.

```
학습·앱 수집
  learning_plan · learning_plan_item · learning_plan_option
  daily_routine · daily_routine_result · daily_report_feedback
  study_time_ranking · patrol_mark
  vocab_*(12개 — 단어 마스터 제외, 학생 응답분)
  push_token · notification_preference · favorite_menu · saved_search

내부 메모·상담
  student_memo · consult_log · consult_log_tag · consult_reservation · consult_slot
  admission_reservation_memo · admission_duplicate_decision

방화벽·좌석·기기
  firewall_request · firewall_restriction · firewall_violation
  door_code_request · branch_door_code · kiosk_device
  public_share_token · signup_rejection

승인·일정
  approval_request · regular_schedule · regular_schedule_item · timetable_cell
```

⚠️ **상벌점(`penalty_point`)과 성적(`student_exam_score`)은 판단이 갈린다** — 수강 기록의
일부로 보면 3년이고, 앱 수집 정보로 보면 즉시 파기다. ★ **확인이 필요한 칸이다.**

---

## 3. ★★ 특별히 봐야 하는 둘

### 3-1. `admission_reservation` — **계약이 성립하지 않은 사람**이다

```
입학상담 신청자 · 대기예약자 — ★ 아직 학생이 아니다
동의 3종: 개인정보 수집·이용 / 마케팅 목적 / 광고성 정보 수신
```

★ **퇴원일이 없다** — 기준일이 **신청일**이어야 한다. 그리고 **등록되지 않은 사람의 자료는
법정 보존 근거가 없다** → 해당 없음으로 읽힌다.

⚠️ **그런데 마케팅 동의를 받은 건은 다르다** — 동의 유효기간 동안 보존해야 한다.
★ **「등록 안 됨 + 마케팅 미동의」와 「마케팅 동의」를 갈라야 한다.**

★ **전환된 건**(학생이 된 사람)은 **수강생 대장 쪽**으로 넘어간다.

### 3-2. `notification_log` — **정보성과 광고성이 섞여 있다**

```
recipient_name · recipient_phone 을 ★ 직접 갖는다 (학생 FK 가 아니다)
수신자 종류가 섞인다 — 학생 · 학부모 · ★ 급식업체 · 대기자
```

⚠️ **그래서 학생 익명화로는 안 지워진다.** 번호가 그 테이블에 박혀 있다.

★ **종류별로 다르다** — 정보성(출결·미등원·미납)은 **분쟁 대비 3년**으로 볼 만하고,
광고성(특강 안내 등)은 **6개월**이다. ⚠️ **지금 그 구분이 데이터에 없다** —
`notification_template` 에 **광고성 여부 축을 더해야** 갈 수 있다.

---

## 3-3. ★ 입학동의서 최종본(261029)이 정한 기간 — **자료별로 다르다**

★ 동의서 본문에 기간이 적혀 있다. **그 기간이 우리가 받은 가장 구체적인 값**이다.

| 자료 | 기간 | 기준일 | 해당 약관 |
|---|---|---|---|
| 진학지도 자료 | **등록일 ~ 다음 해 3월 31일** | 등록일 | `ADMISSION_GUIDE` |
| 증명서 발급용 정보 | **5년** | 발급일 | `CERTIFICATE` |
| 마케팅 수신 동의 | **동의일부터 2년** | 동의일 | `MARKETING` |
| 민감정보(건강·사유 증빙) | **목적 달성 또는 퇴원 시까지** | 퇴원일 | `SENSITIVE` |
| 급식업체 제공 정보 | **급식 이용 종료 및 정산·환불 완료 시까지** | ★ 사건 | `MEAL_THIRD_PARTY` |

⚠️ **「진학지도」는 연 단위가 아니라 날짜다** — 「다음 해 3월 31일」이라 **등록일이 언제든
그 해 입시가 끝나는 날**이다. `retain_years` 로 표현할 수 없다.

⚠️ **「급식업체 제공」과 「민감정보」는 사건 기준이다** — 날짜 계산이 아니라
**정산·환불 완료**, **퇴원**을 보고 지운다. ★ 환불계좌(4장)와 같은 성격이다.

★ **마케팅 2년은 이미 구현돼 있다**(2026-10-30) — `TermsStatus.expired` 가 그 판정이고,
만료되면 광고성 발송에서 빠진다. ⚠️ **그러나 「파기」는 아니다** — 동의 이력은 남는다.

---

## 4. 요청 5항 — 전체와 **별도로** 둘 것

| 자료 | 기간 | 지금 상태 |
|---|---|---|
| **급식 환불계좌** | ★ 「환불 완료 시까지」 | ⚠️ **아직 수집하지 않는다** — 컬럼이 없다 |
| **감사 로그**(`audit_log`) | ★ **3년** | ★ **설정값으로 뺐다**(2026-10-30) — `audit.retention-years`, 기본 3 |

★ **환불계좌는 「기간」이 아니라 「사건」 기준**이다 — 날짜 계산이 아니라 **환불 완료 플래그**를
보고 지운다. ⚠️ **다른 자료와 같은 배치로 돌릴 수 없다.**

---

## 5. 설계 방향 — 요청 2항

★ **기간 숫자를 코드에 넣지 않는다.** 표로 관리한다.

```
retention_policy
  data_category   수강생대장 · 교습비영수증 · 수입지출 · 직원명부 ·
                  계약청약철회 · 대금결제 · 불만분쟁 · 표시광고 · 해당없음
  base_date_type  퇴원일 · 결제일 · 계약일 · 민원종결일 · 신청일 · ★ 사건(환불완료)
  retain_years    ★ 설정값 (0 = 즉시, NULL = 계속 보존)
  action          ANONYMIZE(식별값만 지움) · DELETE(행 삭제)
  active          ★ 기본 FALSE — 명시적으로 켜기 전엔 아무것도 지우지 않는다
```

★ `penalty_rule`·`scholarship_cancel_rule` 과 **같은 방식**이다 — 법 해석이 바뀌어도
**행만 고친다.**

⚠️ **`action` 기본값은 `ANONYMIZE` 다.** 요청 4항이 *「금액·일자·건수는 통계용으로 남긴다」*
이고, **행을 지우면 정산과 과거 통계가 소급해서 바뀐다.**

### ★★ 파기는 되돌릴 수 없다 — 그래서 셋을 먼저 만든다

```
① 미리보기   ★ 「무엇이 몇 건 지워지는가」를 먼저 보여준다 (알림톡 미리보기와 같은 구조)
② 실행 이력   언제 · 어느 정책으로 · 몇 건을 지웠는가
③ active 기본 꺼짐
```

⚠️ **미리보기 없이 배치를 돌리면 안 된다.** 잘못 지운 것은 **복구할 수 없고**, 익명화는
**감사 로그에도 남지 않는다**(지운 값 자체가 사라진다).

---

## 6. 확인이 필요한 칸 — ★ 다섯

| # | 무엇 | 왜 우리가 못 정하나 |
|---|---|---|
| 1 | 출결을 **수강생 대장(3년)**으로 보는가 | 학원법 별표 2 의 「수강생 대장」 범위 해석 |
| 2 | **상벌점·성적**이 수강 기록인가 앱 수집 정보인가 | 3년과 즉시 파기로 갈린다 |
| 3 | `admission_reservation` — **미등록자 + 마케팅 동의** 처리 | 동의 유효기간과 법정 보존 근거가 다른 축이다 |
| 4 | `notification_log` — **정보성/광고성 구분을 만들 것인가** | 3년과 6개월로 갈리고, 지금 데이터에 축이 없다 |
| 5 | **「불만·분쟁」에 해당하는 자료가 우리에게 있는가** | CS 상담은 구글시트로 유지하기로 했다 |

★ **1·2 가 가장 크다** — 그 둘이 **테이블 대부분의 운명을 정한다.**
