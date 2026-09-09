#!/usr/bin/env python3
"""도메인별 구현 현황을 코드에서 뽑는다.

손으로 적으면 다음 커밋에 바로 틀린다. 여기서 뽑는 것은 "있다/없다"뿐이고,
'왜 없는가'(블로커·미착수 사유)는 docs/status-notes.md 에 손으로 적어 합친다.
"""
import re, subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOMAIN = ROOT / "src/main/java/com/dlab/domain"
API = ROOT / "src/main/java/com/dlab/api"
MIG = ROOT / "src/main/resources/db/migration"
TEST = ROOT / "src/test"
NOTES = ROOT / "docs/status-notes.md"
OUT = ROOT / "docs/status.md"

MAPPING = re.compile(r"@(Get|Post|Put|Patch|Delete)Mapping")

# api/ 폴더명이 도메인명과 안 맞는 것들. 빠뜨리면 그 도메인이 "미착수"로 보인다 —
# 실제로 user 는 auth·signup·student·staff 로 흩어져 있어 3개로 잡혔었다.
ALIASES = {
    "user": ["user", "auth", "signup", "student", "staff", "staffcard", "appaccount",
             "academy", "clazz"],
    "payment": ["payment", "billing", "tuition"],
    "attendance": ["attendance", "absence"],
    "report": ["report", "home"],
}

def count_endpoints(base, domain):
    total = 0
    for name in ALIASES.get(domain, [domain]):
        d = base / name
        if d.is_dir():
            total += sum(len(MAPPING.findall(f.read_text(encoding="utf-8")))
                         for f in d.rglob("*.java"))
    return total

def count_files(base, domain, sub):
    d = base / domain / sub
    return len(list(d.glob("*.java"))) if d.is_dir() else 0

def tables_of(domain):
    """이 도메인 엔티티가 쓰는 테이블 이름."""
    d = DOMAIN / domain / "entity"
    if not d.is_dir():
        return []
    names = []
    for f in d.glob("*.java"):
        m = re.search(r'@Table\(name\s*=\s*"([^"]+)"', f.read_text(encoding="utf-8"))
        if m:
            names.append(m.group(1))
    return sorted(names)

def count_tests(domain):
    """그 도메인 패키지를 실제로 import 하는 테스트 파일 수.
    파일명으로 세면 AuthServiceTest 같은 게 user 로 안 잡힌다."""
    needle = f"com.dlab.domain.{domain}."
    return sum(1 for f in (TEST / "java").rglob("*Test.java")
               if needle in f.read_text(encoding="utf-8"))

def notes():
    """docs/status-notes.md 에서 '## <도메인>' 아래 한 줄씩 읽는다."""
    if not NOTES.exists():
        return {}
    out, key = {}, None
    for line in NOTES.read_text(encoding="utf-8").splitlines():
        if line.startswith("## "):
            key = line[3:].strip()
        elif key and line.strip():
            out.setdefault(key, []).append(line.strip())
    return out

def main():
    note = notes()
    domains = sorted(p.name for p in DOMAIN.iterdir() if p.is_dir())
    rev = subprocess.run(["git", "rev-parse", "--short", "HEAD"],
                         cwd=ROOT, capture_output=True, text=True).stdout.strip()

    rows = []
    for d in domains:
        rows.append((
            d,
            len(tables_of(d)),
            count_files(DOMAIN, d, "service"),
            count_endpoints(API / "app", d),
            count_endpoints(API / "admin", d),
            count_tests(d),
            " ".join(note.get(d, [])),
        ))

    total_app = sum(r[3] for r in rows)
    total_admin = sum(r[4] for r in rows)

    lines = [
        "# 구현 현황",
        "",
        f"`scripts/status-report.py` 가 코드에서 뽑는다. **직접 고치지 말 것** — 다음 실행에 덮어써진다.",
        f"사유·블로커는 `docs/status-notes.md` 에 적으면 마지막 열에 붙는다.",
        "",
        f"기준 커밋 `{rev}` · 마이그레이션 {len(list(MIG.glob('*.sql')))}개 · "
        f"앱 API {total_app}개 · 관리자 API {total_admin}개",
        "",
        "| 도메인 | 테이블 | 서비스 | 앱 API | 관리자 API | 테스트 | 비고 |",
        "|---|---:|---:|---:|---:|---:|---|",
    ]
    for d, t, s, a, ad, tc, n in rows:
        lines.append(f"| `{d}` | {t} | {s} | {a or '—'} | {ad or '—'} | {tc or '—'} | {n} |")

    lines += ["", "## 읽는 법", "",
              "- **앱 API 가 `—`** 인데 앱 요구사항이 있으면 미착수다.",
              "- **테이블만 있고 서비스가 0** 이면 스키마만 잡아둔 상태다.",
              "- 숫자는 있고 비고가 비어 있으면 별도 블로커 없이 도는 것이다.",
              ""]
    OUT.write_text("\n".join(lines), encoding="utf-8")
    print(f"{OUT} · 도메인 {len(rows)}개")

main()
