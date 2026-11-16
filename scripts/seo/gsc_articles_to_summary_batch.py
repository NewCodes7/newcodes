#!/usr/bin/env python3
"""Search Console 실적 CSV → AI 요약 배치 생성용 브라우저 콘솔 스니펫.

배경: 아티클 상세는 AI 요약(Article.summary)이 있을 때만 색인된다(ArticleIndexPolicy).
과거에 검색 노출이 있던 글부터 요약을 만들어 색인 대상으로 되살리기 위한 도구다.

사용법
  1. Search Console → 실적 → 기간 16개월 → 필터 "페이지 URL에 /articles/ 포함"
     → 오른쪽 위 "내보내기" → CSV 다운로드 → 압축을 풀어 페이지 CSV(Pages.csv / 페이지.csv)를 고른다.
  2. python3 scripts/seo/gsc_articles_to_summary_batch.py 페이지.csv --top 300 > summary-batch.js
  3. newcodes.net에 관리자로 로그인한 탭에서 개발자 도구 콘솔을 열고 summary-batch.js 내용을 붙여 넣는다.

동작
  - URL에서 /articles/{id}를 뽑아 노출수 내림차순으로 상위 N개 ID를 고른다(같은 ID는 합산).
  - 스니펫은 POST /admin/articles/generate-summary-batch 를 2개씩 순서대로 호출한다.
    이 API는 동기식(글당 OpenAI 호출 + 1초 대기)이고 nginx 기본 타임아웃이 60초라 한 번에 많이 보내면 504가 난다.
    504가 나도 서버는 처리를 계속하므로 스니펫은 실패로 기록만 하고 다음 묶음으로 넘어간다.
  - 중간에 멈췄다면 콘솔에 찍힌 마지막 인덱스를 START에 넣어 다시 붙여 넣으면 이어서 처리한다.
  - 본문(content)이 없는 글은 서버가 건너뛴다(failedCount로 집계).

외부 의존성 없음(표준 라이브러리만).
"""
import argparse
import csv
import json
import re
import sys
from collections import defaultdict

ARTICLE_ID = re.compile(r"/articles/(\d+)")


def find_column(header, *needles):
    for i, name in enumerate(header):
        lowered = name.strip().lower()
        if any(n in lowered for n in needles):
            return i
    return None


def parse_number(value):
    cleaned = re.sub(r"[^\d.]", "", value or "")
    return float(cleaned) if cleaned else 0.0


def load_ranked_ids(path):
    with open(path, encoding="utf-8-sig", newline="") as f:
        rows = list(csv.reader(f))
    if not rows:
        sys.exit("빈 CSV입니다")
    header = rows[0]
    url_col = find_column(header, "page", "페이지", "url")
    impressions_col = find_column(header, "impression", "노출")
    clicks_col = find_column(header, "click", "클릭")
    if url_col is None or impressions_col is None:
        sys.exit(f"페이지/노출 열을 찾지 못했습니다. 헤더: {header}")

    impressions = defaultdict(float)
    clicks = defaultdict(float)
    for row in rows[1:]:
        if len(row) <= max(url_col, impressions_col):
            continue
        match = ARTICLE_ID.search(row[url_col])
        if not match:
            continue
        article_id = int(match.group(1))
        impressions[article_id] += parse_number(row[impressions_col])
        if clicks_col is not None and len(row) > clicks_col:
            clicks[article_id] += parse_number(row[clicks_col])

    return sorted(impressions, key=lambda i: (impressions[i], clicks[i]), reverse=True), impressions


SNIPPET = """// NewCodes AI 요약 배치 — Search Console 노출 상위 {count}개 글 (생성: gsc_articles_to_summary_batch.py)
// newcodes.net 관리자 로그인 탭의 콘솔에 붙여 넣으세요. 중단 후 재개하려면 START를 마지막 인덱스로 바꾸세요.
(async () => {{
  const IDS = {ids};
  const CHUNK = {chunk};
  const START = 0;
  let ok = 0, fail = 0;
  for (let i = START; i < IDS.length; i += CHUNK) {{
    const articleIds = IDS.slice(i, i + CHUNK);
    try {{
      const res = await fetch('/admin/articles/generate-summary-batch', {{
        method: 'POST',
        credentials: 'include',
        headers: {{ 'Content-Type': 'application/json' }},
        body: JSON.stringify({{ articleIds }}),
      }});
      const body = res.ok ? await res.json() : null;
      ok += body?.successCount ?? 0;
      fail += body ? (body.failedCount ?? 0) : articleIds.length;
      console.log(`[${{i}}/${{IDS.length}}] HTTP ${{res.status}}`, body?.message ?? '(504면 서버는 계속 처리 중일 수 있음)');
    }} catch (e) {{
      fail += articleIds.length;
      console.warn(`[${{i}}/${{IDS.length}}] 요청 실패`, e);
    }}
  }}
  console.log(`완료: 성공 ${{ok}}, 실패/미확인 ${{fail}} — 사이트맵은 다음 요청부터 요약 생긴 글을 포함합니다`);
}})();
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("csv_path", help="Search Console 실적 내보내기의 페이지 CSV")
    parser.add_argument("--top", type=int, default=300, help="노출 상위 몇 개 글까지 (기본 300)")
    parser.add_argument("--chunk", type=int, default=2, help="요청당 글 수 (기본 2, nginx 60초 타임아웃 기준)")
    parser.add_argument("--ids-only", action="store_true", help="스니펫 대신 ID 목록(JSON)만 출력")
    args = parser.parse_args()

    ranked, impressions = load_ranked_ids(args.csv_path)
    selected = ranked[: args.top]
    print(f"/articles/ URL {len(ranked)}개 중 상위 {len(selected)}개 선택 "
          f"(노출 합계 {int(sum(impressions[i] for i in selected))})", file=sys.stderr)

    if args.ids_only:
        print(json.dumps(selected))
    else:
        print(SNIPPET.format(count=len(selected), ids=json.dumps(selected), chunk=max(1, args.chunk)))


if __name__ == "__main__":
    main()
