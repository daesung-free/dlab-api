# 연구소 전 지점 입학예약 조회

`account.all_admission_read`는 기본 `FALSE`이며 화면과 API에는 노출하지 않는다.
`READONLY` 역할만 가진 계정에 한해 입학예약 목록·상세·메모·상태이력과 지정 지점의
중복찾기를 전 지점에서 조회할 수 있다. 등록·수정·공유 링크 발급과 다른 업무는 기존 권한을 따른다.
다른 역할을 추가하면 이 설정은 효력이 없다. 로그인 ID나 이름 변경과 무관하게 계정에 보관된다.

## 배포 후 지정

먼저 계정의 역할이 `READONLY` 하나이며 허용 메뉴가 `waiting`(대기자 관리)인지 확인한다.
해당 계정이 맞는지 조회한 후 트랜잭션 안에서 지정한다. 비밀번호는 필요 없다.

```sql
BEGIN;
SELECT id, login_id, status, all_admission_read
FROM account WHERE login_id = 'labview' AND is_deleted = FALSE;

UPDATE account a
SET all_admission_read = TRUE, updated_at = now()
WHERE a.login_id = 'labview' AND a.is_deleted = FALSE AND a.status = 'ACTIVE'
  AND (SELECT count(*) FROM account_role ar WHERE ar.account_id = a.id) = 1
  AND EXISTS (
    SELECT 1 FROM account_role ar JOIN role r ON r.id = ar.role_id
    WHERE ar.account_id = a.id AND r.name = 'READONLY'
  )
RETURNING id, login_id, all_admission_read;
-- 대상이 1건인지 확인한 뒤 COMMIT. 예상과 다르면 ROLLBACK.
COMMIT;
```

해제는 해당 계정의 `all_admission_read = FALSE`로 변경한다. 권한 값은 JWT에 넣지 않고
입학예약 조회 때마다 DB에서 읽으므로 재로그인·서버 재시작 없이 다음 요청부터 반영된다.
DB를 이전할 때 이 컬럼도 함께 옮긴다. 계정을 새로 만들면 기본값은 다시 `FALSE`다.

## 확인

- 허용 전에는 소속 지점 목록만 보이고 다른 지점 상세는 403이다.
- 허용 후에는 전 지점 목록 및 다른 지점 상세·메모·상태이력을 조회할 수 있다.
- 상태 변경·메모 작성·삭제·학생 전환·공유 링크 발급은 계속 403이다.
- 허용 해제 후 같은 토큰으로 조회해도 소속 지점 범위로 돌아온다.
