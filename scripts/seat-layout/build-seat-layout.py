#!/usr/bin/env python3
"""배치표 PNG 39장 + 시트2 번호표 → seat-layout.json 생성기.

★ 왜 손으로 적지 않고 생성하는가
  격자의 통로·기둥(`skips`)이 2,140석 전체에서 1,000칸이 넘는다. 손으로 옮기면
  한 칸만 어긋나도 그 뒤의 번호가 전부 한 칸씩 밀리고, 틀린 것을 화면에서
  알아보기가 매우 어렵다. 그래서 **빈칸은 PNG 픽셀에서 읽는다.**

  사람이 판단한 것은 둘뿐이다 —
    ① 격자를 어디서 자르는가(`GRIDS`)  ② 번호가 붙는 방향(columnMajor·reverseX·reverseY)
  둘 다 PNG 의 번호를 눈으로 읽어 정했고, **좌석 수로 교차검증**된다
  (구역별 좌석 수가 시트2와 1석이라도 다르면 이 스크립트가 멈춘다).

★ 색 (배치표 PNG 공통)
    (38,198,218)  하늘색 = 통로          → 좌석 없음
    (206,212,218) 회색   = 배치 불가(기둥) → 좌석 없음
    흰 칸                                 → 좌석
  디랩 답변(2026-10-22)대로 **통로와 기둥을 구분하지 않는다**(확인항목 C-2).

★ 입력 PNG 는 `md/` 에 있고 git 밖이다.
    python3 scripts/seat-layout/build-seat-layout.py [--png-dir <경로>]
  Pillow 가 필요하다(`pip3 install pillow`).
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import date

try:
    from PIL import Image
except ImportError:  # pragma: no cover
    sys.exit("Pillow 가 필요합니다: pip3 install pillow")

CYAN = (38, 198, 218)
GRAY = (206, 212, 218)
BORDER = (212, 218, 227)

DEFAULT_PNG_DIR = os.path.expanduser(
    "~/Documents/work/dlab-api/md/좌석배치/좌석배치표")

CLASSROOM, FOCUS, SINGLE = "CLASSROOM", "FOCUS", "SINGLE_ROOM"


# ── 지점 ────────────────────────────────────────────────────────────────────
# acad_cd 는 CLAUDE.md §2 확정분. 31~34 다음이 42 로 건너뛴다(연속이라 가정 말 것).
ACADEMIES = {
    "분당": "31", "일산": "32", "동탄": "33", "김포": "34",
    "부천": "42", "이매": "43", "광명": "44", "목동": "45", "송파": "46",
    "대전": "47", "대구": "48",
}


# ── 구역별 좌석형태 (시트2 원문 · 검증 완료) ──────────────────────────────────
# 「시작~끝 형태」 그대로다. 양 끝을 포함한다.
STYLES = {
    ("분당", "A"): [(1, 71, CLASSROOM)],
    ("분당", "B"): [(101, 159, CLASSROOM), (160, 168, FOCUS)],
    ("분당", "C"): [(201, 227, CLASSROOM)],
    ("분당", "D"): [(301, 315, SINGLE)],

    ("일산", "A"): [(1, 8, FOCUS), (9, 48, CLASSROOM), (49, 56, FOCUS)],
    ("일산", "B"): [(101, 108, FOCUS), (109, 135, CLASSROOM)],
    ("일산", "C"): [(201, 215, FOCUS), (216, 223, CLASSROOM), (224, 247, FOCUS)],

    ("동탄", "A"): [(1, 7, FOCUS), (8, 50, CLASSROOM)],
    ("동탄", "B"): [(101, 155, CLASSROOM)],
    ("동탄", "C"): [(201, 239, CLASSROOM), (240, 250, FOCUS), (251, 256, CLASSROOM)],

    ("동탄별관", "A"): [(1, 34, CLASSROOM)],
    ("동탄별관", "B"): [(101, 141, CLASSROOM)],
    ("동탄별관", "C"): [(201, 225, CLASSROOM)],

    ("김포", "A"): [(1, 15, FOCUS), (16, 51, CLASSROOM)],
    ("김포", "B"): [(101, 135, CLASSROOM)],
    ("김포", "C"): [(201, 207, CLASSROOM), (208, 230, FOCUS)],

    ("부천", "A"): [(1, 85, CLASSROOM), (86, 92, FOCUS)],
    ("부천", "B"): [(101, 139, CLASSROOM)],
    ("부천", "C"): [(201, 223, CLASSROOM), (224, 231, FOCUS)],

    ("이매", "A"): [(1, 8, FOCUS), (9, 37, CLASSROOM)],
    ("이매", "B"): [(101, 130, CLASSROOM), (131, 136, FOCUS)],
    ("이매", "C"): [(201, 209, FOCUS), (210, 245, CLASSROOM), (246, 261, FOCUS)],

    ("광명", "A"): [(1, 38, CLASSROOM), (39, 55, FOCUS)],
    ("광명", "B"): [(101, 148, CLASSROOM), (149, 154, FOCUS)],
    ("광명", "C"): [(201, 212, FOCUS), (213, 253, CLASSROOM), (254, 267, FOCUS),
                   (268, 283, CLASSROOM), (284, 287, FOCUS)],

    ("목동", "A"): [(1, 42, CLASSROOM), (43, 56, FOCUS), (57, 64, SINGLE),
                   (65, 66, CLASSROOM), (67, 75, SINGLE)],
    ("목동", "B"): [(101, 115, FOCUS), (116, 189, CLASSROOM)],
    ("목동", "C"): [(201, 217, FOCUS), (218, 265, CLASSROOM), (266, 277, FOCUS)],
    ("목동", "D"): [(301, 335, CLASSROOM), (336, 351, FOCUS)],

    ("송파", "A"): [(1, 3, FOCUS), (4, 24, CLASSROOM), (25, 30, FOCUS),
                   (31, 59, CLASSROOM), (60, 64, FOCUS)],
    ("송파", "B"): [(101, 113, FOCUS), (114, 136, CLASSROOM), (137, 144, FOCUS),
                   (145, 167, CLASSROOM), (168, 172, FOCUS)],
    ("송파", "C"): [(201, 203, FOCUS), (204, 225, CLASSROOM), (226, 236, FOCUS),
                   (237, 248, CLASSROOM), (249, 263, FOCUS)],

    ("대전", "A"): [(1, 27, FOCUS), (28, 75, CLASSROOM)],
    ("대전", "B"): [(101, 150, CLASSROOM)],
    ("대전", "C"): [(201, 278, CLASSROOM)],

    ("대구", "A"): [(1, 6, SINGLE), (7, 57, CLASSROOM), (58, 68, FOCUS)],
    ("대구", "B"): [(101, 105, SINGLE), (106, 150, CLASSROOM), (151, 161, FOCUS)],
    ("대구", "C"): [(201, 206, SINGLE), (207, 257, CLASSROOM), (258, 268, FOCUS)],
    ("대구", "D"): [(301, 305, SINGLE), (306, 350, CLASSROOM), (351, 361, FOCUS)],
}


# ── 격자 자르기 · 번호 방향 ──────────────────────────────────────────────────
#
# 한 줄 = (행범위, 열범위, columnMajor, reverseX, reverseY, 시작번호, 예상좌석수)
# 행·열은 PNG 격자 기준 1부터다. 빈칸(통로·기둥)은 PNG 에서 자동으로 뺀다.
#
# ★ 격자를 쪼갠 구역이 있다 — 아래쪽에 가로로 한 줄 붙은 곳, 번호가 중간에 방향을
#   바꾸는 곳이다. 한 번의 호출로는 그 번호가 안 나온다.
GRIDS = {
    # ── 분당 — 전부 한 격자, 세로 우선 ──
    ("분당", "A"): [((1, 7), (1, 17), True, False, False, 1, 71)],
    ("분당", "B"): [((1, 9), (1, 16), True, False, False, 101, 68)],
    ("분당", "C"): [((1, 7), (1, 6), True, False, False, 201, 27)],
    ("분당", "D"): [((1, 4), (1, 6), True, False, False, 301, 15)],

    ("일산", "A"): [((1, 8), (1, 15), True, False, False, 1, 56)],
    ("일산", "B"): [((1, 8), (1, 10), True, False, False, 101, 35)],
    ("일산", "C"): [((1, 8), (1, 14), True, False, False, 201, 47)],

    # 동탄 본관 A — 1~7 은 맨 왼쪽 세로줄, 8~14 는 맨 아랫줄, 15 부터 다시 세로줄
    ("동탄", "A"): [((3, 9), (1, 1), True, False, False, 1, 7),
                   ((10, 10), (3, 9), False, False, False, 8, 7),
                   ((1, 8), (3, 9), True, False, False, 15, 36)],
    # 동탄 본관 B — 아랫줄(149~155)만 오른쪽에서 왼쪽으로 붙는다
    ("동탄", "B"): [((1, 8), (1, 9), True, False, False, 101, 48),
                   ((10, 10), (3, 9), False, True, False, 149, 7)],
    ("동탄", "C"): [((1, 11), (1, 9), True, False, False, 201, 50),
                   ((12, 12), (3, 8), False, False, False, 251, 6)],

    ("동탄별관", "A"): [((1, 8), (1, 7), True, False, False, 1, 34)],
    ("동탄별관", "B"): [((1, 8), (1, 9), True, False, False, 101, 41)],
    # ★ 가로·세로 둘 다 역방향 — 1201 이 오른쪽 아래에서 왼쪽 위로 간다
    ("동탄별관", "C"): [((1, 6), (1, 12), True, True, True, 201, 25)],

    ("김포", "A"): [((1, 10), (1, 1), True, False, False, 1, 10),
                   ((12, 12), (1, 5), False, False, False, 11, 5),
                   ((1, 8), (3, 9), True, False, False, 16, 36)],
    ("김포", "B"): [((1, 7), (1, 7), True, False, False, 101, 35)],
    ("김포", "C"): [((3, 6), (1, 2), True, False, False, 201, 7),
                   ((1, 1), (1, 11), False, False, False, 208, 11),
                   ((8, 8), (1, 12), False, True, False, 219, 12)],

    ("부천", "A"): [((1, 9), (1, 15), True, False, False, 1, 85),
                   ((11, 11), (8, 14), False, False, False, 86, 7)],
    ("부천", "B"): [((1, 10), (1, 6), True, False, False, 101, 39)],
    ("부천", "C"): [((1, 8), (1, 7), True, False, False, 201, 31)],

    ("이매", "A"): [((1, 8), (1, 9), True, False, False, 1, 37)],
    ("이매", "B"): [((1, 5), (1, 8), True, False, False, 101, 30),
                   ((7, 7), (2, 7), False, False, False, 131, 6)],
    # 오른쪽 블록(246~261)만 가로 우선이다
    ("이매", "C"): [((1, 9), (1, 10), True, False, False, 201, 45),
                   ((1, 6), (12, 15), False, False, False, 246, 16)],

    ("광명", "A"): [((1, 9), (1, 10), True, False, False, 1, 46),
                   ((10, 10), (2, 10), False, False, False, 47, 9)],
    ("광명", "B"): [((1, 8), (1, 12), True, False, False, 101, 54)],
    # ★ 가장 많이 쪼갠 구역 — 가로줄이 전부 오른쪽→왼쪽이고,
    #   마지막 264~287 블록만 세로 우선 + 가로·세로 역방향이다
    ("광명", "C"): [((1, 3), (13, 18), True, False, False, 201, 12),
                   ((5, 6), (12, 18), False, True, False, 213, 14),
                   ((8, 9), (12, 18), False, True, False, 227, 14),
                   ((11, 12), (12, 18), False, True, False, 241, 13),
                   ((14, 14), (9, 18), False, True, False, 254, 10),
                   ((7, 14), (1, 7), True, True, True, 264, 24)],

    ("목동", "A"): [((1, 6), (1, 12), True, False, False, 1, 42),
                   ((1, 8), (13, 14), False, False, False, 43, 11),
                   ((10, 10), (4, 14), False, True, False, 54, 11),
                   ((11, 12), (4, 14), False, False, False, 65, 11)],
    ("목동", "B"): [((1, 11), (1, 2), False, False, False, 101, 15),
                   ((1, 10), (4, 14), True, False, False, 116, 74)],
    ("목동", "C"): [((1, 12), (1, 3), False, False, False, 201, 17),
                   ((1, 8), (4, 11), True, False, False, 218, 48),
                   ((12, 12), (5, 14), False, False, False, 266, 10),
                   ((13, 14), (14, 14), False, False, False, 276, 2)],
    ("목동", "D"): [((1, 11), (1, 7), True, False, False, 301, 46),
                   ((13, 13), (3, 7), False, True, False, 347, 5)],

    # 송파 3구역 모두 「위쪽은 정방향, 아래쪽 블록은 오른쪽 아래에서 거꾸로」다
    ("송파", "A"): [((1, 4), (1, 12), True, False, False, 1, 24),
                   ((5, 10), (1, 12), True, True, True, 25, 40)],
    ("송파", "B"): [((1, 5), (1, 13), True, False, False, 101, 36),
                   ((6, 10), (1, 14), True, True, True, 137, 36)],
    ("송파", "C"): [((1, 4), (1, 12), True, False, False, 201, 25),
                   ((5, 10), (1, 12), True, True, True, 226, 38)],

    # 대전 — 아래에서 위로 번호가 올라간다
    ("대전", "A"): [((9, 11), (1, 14), True, False, True, 1, 27),
                   ((1, 7), (1, 10), False, False, True, 28, 48)],
    ("대전", "B"): [((1, 9), (1, 11), False, False, True, 101, 50)],
    ("대전", "C"): [((1, 16), (1, 9), False, True, False, 201, 78)],

    # ★ 대구 A·C 는 가로 역방향(1번이 오른쪽 끝), B·D 는 정방향이다
    ("대구", "A"): [((1, 12), (1, 11), False, True, False, 1, 68)],
    ("대구", "B"): [((1, 12), (1, 11), False, False, False, 101, 61)],
    ("대구", "C"): [((1, 12), (1, 11), False, True, False, 201, 68)],
    ("대구", "D"): [((1, 12), (1, 11), False, False, False, 301, 61)],
}

# ── 번호 앵커 ───────────────────────────────────────────────────────────────
#
# ★ 좌석 수만으로는 번호 방향을 검증할 수 없다. 가로 역방향을 정방향으로 잘못 넣어도
#   좌석 수는 똑같이 맞는다 — 번호만 거꾸로 붙는다. 그러면 배정·출결이 통째로
#   어긋나는데 합계 검산은 통과한다.
#
#   그래서 배치표 PNG 에서 **눈으로 읽은 번호의 위치**를 적어 두고, 아래
#   `simulate()` 가 서버와 같은 순서로 번호를 매겨 대조한다.
#   `번호: (행, 열)` 이고 행·열은 PNG 격자 기준 1부터다.
ANCHORS = {
    ("분당", "A"): {1: (2, 1), 71: (7, 17)},
    ("분당", "B"): {101: (1, 1), 143: (2, 11), 168: (9, 16)},
    ("분당", "C"): {201: (1, 1), 227: (7, 6)},
    ("분당", "D"): {301: (1, 1), 315: (4, 6)},

    ("일산", "A"): {1: (1, 1), 49: (1, 15), 56: (8, 15)},
    ("일산", "B"): {101: (1, 1), 133: (2, 10), 135: (4, 10)},
    ("일산", "C"): {201: (1, 1), 216: (1, 6), 247: (5, 14)},

    ("동탄", "A"): {1: (3, 1), 8: (10, 3), 14: (10, 9), 15: (3, 3), 50: (8, 9)},
    ("동탄", "B"): {101: (1, 1), 149: (10, 9), 155: (10, 3)},
    ("동탄", "C"): {201: (4, 1), 240: (1, 9), 250: (11, 9), 251: (12, 3), 256: (12, 8)},

    ("동탄별관", "A"): {1: (1, 1), 34: (7, 7)},
    ("동탄별관", "B"): {101: (1, 1), 141: (7, 9)},
    ("동탄별관", "C"): {201: (6, 12), 210: (6, 9), 225: (2, 1)},

    ("김포", "A"): {1: (1, 1), 10: (10, 1), 11: (12, 1), 15: (12, 5),
                   16: (1, 3), 51: (7, 9)},
    ("김포", "B"): {101: (1, 1), 135: (7, 7)},
    ("김포", "C"): {201: (3, 1), 205: (4, 2), 208: (1, 1), 218: (1, 11),
                   219: (8, 12), 230: (8, 1)},

    ("부천", "A"): {1: (1, 1), 85: (9, 15), 86: (11, 8), 92: (11, 14)},
    ("부천", "B"): {101: (1, 1), 139: (10, 6)},
    ("부천", "C"): {201: (1, 1), 231: (8, 7)},

    ("이매", "A"): {1: (1, 1), 37: (6, 9)},
    ("이매", "B"): {101: (1, 1), 130: (5, 8), 131: (7, 2), 136: (7, 7)},
    ("이매", "C"): {201: (1, 1), 245: (4, 10), 246: (1, 12), 261: (6, 15)},

    ("광명", "A"): {1: (3, 1), 46: (9, 10), 47: (10, 2), 55: (10, 10)},
    ("광명", "B"): {101: (1, 1), 154: (6, 12)},
    ("광명", "C"): {201: (1, 13), 212: (3, 18), 213: (5, 18), 219: (5, 12),
                   226: (6, 12), 227: (8, 18), 240: (9, 12), 241: (11, 18),
                   246: (11, 13), 247: (12, 18), 253: (12, 12), 254: (14, 18),
                   263: (14, 9), 264: (14, 7), 271: (7, 7), 287: (7, 1)},

    ("목동", "A"): {1: (2, 1), 42: (6, 11), 43: (1, 13), 53: (8, 14),
                   54: (10, 14), 64: (10, 4), 65: (11, 4), 75: (12, 14)},
    ("목동", "B"): {101: (1, 2), 115: (11, 2), 116: (1, 4), 189: (10, 14)},
    ("목동", "C"): {201: (1, 1), 217: (12, 3), 218: (1, 4), 265: (8, 11),
                   266: (12, 5), 275: (12, 14), 276: (13, 14), 277: (14, 14)},
    ("목동", "D"): {301: (2, 1), 346: (11, 7), 347: (13, 7), 351: (13, 3)},

    ("송파", "A"): {1: (2, 1), 24: (3, 12), 25: (10, 12), 64: (6, 1)},
    ("송파", "B"): {101: (2, 1), 136: (4, 13), 137: (10, 14), 172: (6, 1)},
    ("송파", "C"): {201: (2, 1), 225: (3, 12), 226: (10, 12), 263: (6, 1)},

    ("대전", "A"): {1: (11, 2), 27: (9, 14), 28: (7, 2), 75: (1, 10)},
    ("대전", "B"): {101: (9, 2), 150: (1, 11)},
    ("대전", "C"): {201: (1, 9), 278: (16, 2)},

    ("대구", "A"): {1: (1, 10), 68: (12, 1)},
    ("대구", "B"): {101: (1, 2), 161: (12, 11)},
    ("대구", "C"): {201: (1, 10), 268: (12, 1)},
    ("대구", "D"): {301: (1, 2), 361: (12, 11)},
}

# 구역별 좌석 수 — 0-1 집계표에서 역산한 검산값. 어긋나면 생성이 멈춘다.
EXPECTED = {
    ("분당", "A"): 71, ("분당", "B"): 68, ("분당", "C"): 27, ("분당", "D"): 15,
    ("일산", "A"): 56, ("일산", "B"): 35, ("일산", "C"): 47,
    ("동탄", "A"): 50, ("동탄", "B"): 55, ("동탄", "C"): 56,
    ("동탄별관", "A"): 34, ("동탄별관", "B"): 41, ("동탄별관", "C"): 25,
    ("김포", "A"): 51, ("김포", "B"): 35, ("김포", "C"): 30,
    ("부천", "A"): 92, ("부천", "B"): 39, ("부천", "C"): 31,
    ("이매", "A"): 37, ("이매", "B"): 36, ("이매", "C"): 61,
    ("광명", "A"): 55, ("광명", "B"): 54, ("광명", "C"): 87,
    ("목동", "A"): 75, ("목동", "B"): 89, ("목동", "C"): 77, ("목동", "D"): 51,
    ("송파", "A"): 64, ("송파", "B"): 72, ("송파", "C"): 63,
    ("대전", "A"): 75, ("대전", "B"): 50, ("대전", "C"): 78,
    ("대구", "A"): 68, ("대구", "B"): 61, ("대구", "C"): 68, ("대구", "D"): 61,
}

# 지점별 합계 — 0-1 집계표 원문
BRANCH_TOTAL = {
    "분당": 181, "일산": 138, "동탄": 161, "동탄별관": 100, "김포": 116,
    "부천": 162, "이매": 134, "광명": 196, "목동": 292, "송파": 199,
    "대전": 203, "대구": 258,
}

PNG_NAME = {"동탄별관": "동탄(별관)"}


# ── PNG → 격자 ──────────────────────────────────────────────────────────────

def _near(p, c, tol=14):
    return (abs(p[0] - c[0]) <= tol and abs(p[1] - c[1]) <= tol
            and abs(p[2] - c[2]) <= tol)


def _runs(flags, min_len=1):
    out, start = [], None
    for i, f in enumerate(flags):
        if f and start is None:
            start = i
        elif not f and start is not None:
            if i - start >= min_len:
                out.append((start, i - 1))
            start = None
    if start is not None and len(flags) - start >= min_len:
        out.append((start, len(flags) - 1))
    return out


def read_chart(path):
    """배치표 한 장 → 2차원 문자맵. 'O'=좌석 · '.'=통로 · 'x'=배치불가."""
    im = Image.open(path).convert("RGB")
    w, h = im.size
    px = im.load()

    col_border = [0] * w
    row_nonwhite = [0] * h
    for y in range(h):
        for x in range(w):
            p = px[x, y]
            if _near(p, BORDER, 6):
                col_border[x] += 1
            if p != (255, 255, 255):
                row_nonwhite[y] += 1

    # 열 = 셀 테두리 세로선 쌍(왼쪽 선 · 오른쪽 선)
    cmax = max(col_border)
    vruns = _runs([c > cmax * 0.35 for c in col_border])
    cols = [(vruns[i][0], vruns[i + 1][1]) for i in range(0, len(vruns) - 1, 2)]

    # 행 = 비흰색 픽셀이 이어지는 밴드. 제목 한 줄은 셀보다 낮아 걸러진다
    thr = max(4, int(0.02 * w))
    bands = [r for r in _runs([c > thr for c in row_nonwhite]) if r[1] - r[0] >= 10]
    heights = sorted(r[1] - r[0] for r in bands)
    median = heights[len(heights) // 2]
    rows = [r for r in bands if (r[1] - r[0]) >= median * 0.6]

    grid = []
    for (y0, y1) in rows:
        line = []
        for (x0, x1) in cols:
            cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
            votes = {"CYAN": 0, "GRAY": 0, "WHITE": 0}
            for dx in (-12, -6, 0, 6, 12):
                for dy in (-14, -7, 0, 7, 14):
                    x, y = cx + dx, cy + dy
                    if not (0 <= x < w and 0 <= y < h):
                        continue
                    p = px[x, y]
                    if _near(p, CYAN):
                        votes["CYAN"] += 1
                    elif _near(p, GRAY):
                        votes["GRAY"] += 1
                    elif p == (255, 255, 255):
                        votes["WHITE"] += 1
            kind = max(votes, key=votes.get)
            line.append("." if kind == "CYAN" else ("x" if kind == "GRAY" else "O"))
        grid.append(line)
    return grid


def build_grid(chart, spec):
    """격자 한 개 → API 요청값. 좌석 수가 예상과 다르면 예외."""
    (r0, r1), (c0, c1), column_major, rev_x, rev_y, start, expect = spec
    rows = r1 - r0 + 1
    columns = c1 - c0 + 1

    skips, seats = [], 0
    for r in range(rows):
        for c in range(columns):
            if chart[r0 - 1 + r][c0 - 1 + c] == "O":
                seats += 1
            else:
                skips.append({"row": r + 1, "column": c + 1})
    if seats != expect:
        raise ValueError("격자 좌석 수가 다릅니다: 예상 %d · 실제 %d (행 %s 열 %s)"
                         % (expect, seats, (r0, r1), (c0, c1)))
    if rows * columns > 500:
        raise ValueError("격자가 500칸을 넘습니다(서버 상한): %d" % (rows * columns))

    grid = {
        "rows": rows,
        "columns": columns,
        "startNumber": start,
        "numberPadding": 0,     # ★ 0 이어야 순수 숫자다. 별관 offset 변환이 숫자일 때만 걸린다
        "startX": c0,
        "startY": r0,
        "columnMajor": column_major,
        "reverseX": rev_x,
        "reverseY": rev_y,
        "seats": seats,
    }
    if skips:
        grid["skips"] = skips
    return grid


def simulate(grids):
    """서버 `SeatMasterAdminService.plan()` 과 같은 순서로 번호를 매긴다.

    ★ 좌표가 아니라 **훑는 순서**를 뒤집는 것이 역방향이다(서버 주석 그대로).
    돌려주는 값은 `번호 → (PNG 격자 행, 열)` 이다.
    """
    placed = {}
    for g in grids:
        skips = {(s["row"], s["column"]) for s in g.get("skips", [])}
        rows = list(range(1, g["rows"] + 1))
        columns = list(range(1, g["columns"] + 1))
        if g["reverseY"]:
            rows.reverse()
        if g["reverseX"]:
            columns.reverse()
        outer, inner = (columns, rows) if g["columnMajor"] else (rows, columns)

        number = g["startNumber"]
        for o in outer:
            for i in inner:
                row, column = (i, o) if g["columnMajor"] else (o, i)
                if (row, column) in skips:
                    continue
                # xPos = startX + column - 1 · yPos = startY + row - 1
                placed[number] = (g["startY"] + row - 1, g["startX"] + column - 1)
                number += 1
    return placed


def check_anchors(branch, area_cd, grids):
    placed = simulate(grids)
    for number, want in ANCHORS.get((branch, area_cd), {}).items():
        got = placed.get(number)
        if got != want:
            sys.exit("%s %s — %d번이 배치표와 다른 칸에 붙습니다: 배치표 %s · 생성 %s "
                     "(번호 방향을 확인하세요)"
                     % (branch, area_cd, number, want, got))


def style_counts(ranges):
    counts = {}
    for lo, hi, style in ranges:
        counts[style] = counts.get(style, 0) + (hi - lo + 1)
    return counts


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--png-dir", default=DEFAULT_PNG_DIR)
    ap.add_argument("--out", default=os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "seat-layout.json"))
    args = ap.parse_args()

    areas, totals = [], {CLASSROOM: 0, FOCUS: 0, SINGLE: 0}
    branch_sum = {}

    for (branch, area_cd), specs in GRIDS.items():
        png = os.path.join(args.png_dir,
                           "%s%s.PNG" % (PNG_NAME.get(branch, branch), area_cd))
        if not os.path.exists(png):
            sys.exit("배치표를 찾을 수 없습니다: %s" % png)
        chart = read_chart(png)

        grids = [build_grid(chart, s) for s in specs]
        check_anchors(branch, area_cd, grids)
        seats = sum(g["seats"] for g in grids)
        if seats != EXPECTED[(branch, area_cd)]:
            sys.exit("%s %s 좌석 수가 집계표와 다릅니다: 예상 %d · 실제 %d"
                     % (branch, area_cd, EXPECTED[(branch, area_cd)], seats))

        ranges = STYLES[(branch, area_cd)]
        counts = style_counts(ranges)
        if sum(counts.values()) != seats:
            sys.exit("%s %s 좌석형태 범위 합계가 좌석 수와 다릅니다: %d vs %d"
                     % (branch, area_cd, sum(counts.values()), seats))
        for style, n in counts.items():
            totals[style] += n
        branch_sum[branch] = branch_sum.get(branch, 0) + seats

        annex = branch == "동탄별관"
        areas.append({
            "acadCd": ACADEMIES["동탄" if annex else branch],
            "academyName": "동탄" if annex else branch,
            "building": "ANNEX" if annex else "MAIN",
            "areaCd": area_cd,
            "areaNm": "%s구역" % area_cd,
            "sortOrder": ord(area_cd) - ord("A") + 1,
            "expectedSeats": seats,
            "styleCounts": counts,
            "styleRanges": [{"fromNumber": lo, "toNumber": hi, "seatStyle": st}
                            for lo, hi, st in ranges],
            "grids": grids,
        })

    for branch, total in BRANCH_TOTAL.items():
        if branch_sum.get(branch) != total:
            sys.exit("%s 지점 합계가 집계표와 다릅니다: 예상 %d · 실제 %d"
                     % (branch, total, branch_sum.get(branch)))

    grand = sum(totals.values())
    if grand != 2140:
        sys.exit("전 지점 합계가 2,140 이 아닙니다: %d" % grand)

    payload = {
        "generatedOn": date.today().isoformat(),
        "source": "md/좌석배치/개발자_전달_좌석배치확정_1023.md "
                  "· 1021 좌석배치표 확인요청 답변_1022.xlsx 시트2 · 배치표 PNG 39장",
        "totals": {"seats": grand, **totals},
        "branchTotals": branch_sum,
        "areas": areas,
    }
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
        f.write("\n")

    print("구역 %d · 좌석 %d (교실형 %d · 집중형 %d · 1인실 %d)"
          % (len(areas), grand, totals[CLASSROOM], totals[FOCUS], totals[SINGLE]))
    print("→ %s" % args.out)


if __name__ == "__main__":
    main()
