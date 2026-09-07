package com.wechat.wechatsummary.cache;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/**
 * Eviction execution is idempotent and safe: targeted evicts, clear-all fallback, missing
 * regions and rejected cache names.
 */
@ExtendWith(MockitoExtension.class)
class CacheEvictorTest {

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache imageCache;

    @Mock
    private Cache listCache;

    private CacheEvictor evictor() {
        return new CacheEvictor(cacheManager);
    }

    @Test
    void evictsTargetedEntry() {
        when(cacheManager.getCache(CacheNames.IMAGE_SUMMARY)).thenReturn(imageCache);

        evictor().execute(CacheEvictionMessage.evict(CacheNames.IMAGE_SUMMARY, "hash-1"));

        verify(imageCache).evict("hash-1");
    }

    @Test
    void clearsWholeRegionForClearAll() {
        when(cacheManager.getCache(CacheNames.IMAGE_SUMMARY_LIST)).thenReturn(listCache);

        evictor().execute(CacheEvictionMessage.clear(CacheNames.IMAGE_SUMMARY_LIST));

        verify(listCache).clear();
    }

    @Test
    void missingCacheRegionIsSuccessNotCrash() {
        when(cacheManager.getCache(CacheNames.AUDIO_SUMMARY)).thenReturn(null);

        assertDoesNotThrow(() ->
                evictor().execute(CacheEvictionMessage.evict(CacheNames.AUDIO_SUMMARY, "h")));
    }

    @Test
    void rejectsUnknownCacheName() {
        assertThrows(IllegalArgumentException.class,
                () -> evictor().execute(CacheEvictionMessage.evict("chat_analysis", "k")));
        assertThrows(IllegalArgumentException.class,
                () -> evictor().execute(CacheEvictionMessage.evict("users", "k")));
    }

    @Test
    void allowedSetCoversAllKnownCachesAndRejectsOthers() {
        for (String cache : new String[]{
                "image_summary", "image_summary_list",
                "emoji_summary", "emoji_summary_list",
                "audio_summary", "audio_summary_list",
                "video_summary", "video_summary_list"}) {
            org.junit.jupiter.api.Assertions.assertTrue(CacheNames.ALLOWED.contains(cache), cache);
        }
        org.junit.jupiter.api.Assertions.assertFalse(CacheNames.ALLOWED.contains("chat_analysis"));
        org.junit.jupiter.api.Assertions.assertFalse(CacheNames.ALLOWED.contains("users"));
        org.junit.jupiter.api.Assertions.assertFalse(
                CacheNames.ALLOWED.contains("image_summary; DROP TABLE x"));
    }
}
