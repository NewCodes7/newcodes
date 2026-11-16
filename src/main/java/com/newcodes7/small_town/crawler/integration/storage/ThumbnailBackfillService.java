package com.newcodes7.small_town.crawler.integration.storage;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 원본 해상도로 저장된 기존 PNG 썸네일을 {@link ThumbnailOptimizer} 기준으로 다시 올리는 백필.
 *
 * <p>S3 다운로드·인코딩·업로드는 트랜잭션 밖에서 하고, URL 교체만 짧은 UPDATE로 한다
 * (HikariCP 풀이 5라 이미지 처리 동안 커넥션을 물고 있으면 검색·RAG가 밀린다).
 * 한 번에 limit개를 순서대로 처리하고, 다음 호출에 넘길 커서(nextBeforeId)를 돌려준다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThumbnailBackfillService {

    static final int MAX_LIMIT = 50;

    private final ArticleRepository articleRepository;
    private final S3ImageService s3ImageService;

    public record BatchResult(int scanned, int optimized, int skipped, int failed, Long nextBeforeId) {
    }

    public BatchResult optimizeBatch(long beforeId, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        List<Object[]> rows = articleRepository.findLegacyPngThumbnailsBefore(beforeId, boundedLimit);

        int optimized = 0;
        int skipped = 0;
        int failed = 0;
        Long lastId = null;
        for (Object[] row : rows) {
            Long id = ((Number) row[0]).longValue();
            String thumbnailUrl = (String) row[1];
            lastId = id;
            try {
                String newUrl = s3ImageService.reuploadOptimizedThumbnail(thumbnailUrl);
                if (newUrl == null) {
                    skipped++;
                    continue;
                }
                articleRepository.updateThumbnailImage(id, newUrl);
                optimized++;
            } catch (Exception e) {
                failed++;
                log.warn("썸네일 백필 실패: articleId={} url={} ({})", id, thumbnailUrl, e.getMessage());
            }
        }

        // 이번 배치가 limit보다 적게 찼으면 끝까지 온 것
        Long nextBeforeId = rows.size() < boundedLimit ? null : lastId;
        log.info("썸네일 백필: scanned={} optimized={} skipped={} failed={} next={}",
                rows.size(), optimized, skipped, failed, nextBeforeId);
        return new BatchResult(rows.size(), optimized, skipped, failed, nextBeforeId);
    }
}
