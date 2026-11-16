package com.newcodes7.small_town.admin.controller;

import com.newcodes7.small_town.crawler.integration.storage.ThumbnailBackfillService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 기존 썸네일 최적화 백필 트리거. {@code /admin/**}는 SecurityConfig에서 ADMIN 전용.
 *
 * <p>요청 하나가 동기로 limit개를 처리하므로(nginx 기본 타임아웃 60초) limit은 작게 두고,
 * 응답의 nextBeforeId를 다음 요청의 beforeId로 넘겨 반복한다. nextBeforeId가 null이면 끝.
 * <pre>
 * let before = Number.MAX_SAFE_INTEGER;
 * while (before) {
 *   const r = await (await fetch(`/admin/thumbnails/optimize?beforeId=${before}&amp;limit=20`, {method: 'POST'})).json();
 *   console.log(r); before = r.nextBeforeId;
 * }
 * </pre>
 */
@RestController
@RequestMapping("/admin/thumbnails")
@RequiredArgsConstructor
public class AdminThumbnailController {

    private final ThumbnailBackfillService thumbnailBackfillService;

    @PostMapping("/optimize")
    public ResponseEntity<ThumbnailBackfillService.BatchResult> optimize(
            @RequestParam(defaultValue = "9223372036854775807") long beforeId,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(thumbnailBackfillService.optimizeBatch(beforeId, limit));
    }
}
