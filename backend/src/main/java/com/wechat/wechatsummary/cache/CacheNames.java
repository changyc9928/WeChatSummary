package com.wechat.wechatsummary.cache;

import java.util.Set;

/**
 * Spring cache region names used by the media-summary caches.
 */
public final class CacheNames {

    public static final String IMAGE_SUMMARY = "image_summary";
    public static final String IMAGE_SUMMARY_LIST = "image_summary_list";
    public static final String EMOJI_SUMMARY = "emoji_summary";
    public static final String EMOJI_SUMMARY_LIST = "emoji_summary_list";
    public static final String AUDIO_SUMMARY = "audio_summary";
    public static final String AUDIO_SUMMARY_LIST = "audio_summary_list";
    public static final String VIDEO_SUMMARY = "video_summary";
    public static final String VIDEO_SUMMARY_LIST = "video_summary_list";

    /** Regions an inbound eviction message is allowed to address. */
    public static final Set<String> ALLOWED = Set.of(
            IMAGE_SUMMARY,
            IMAGE_SUMMARY_LIST,
            EMOJI_SUMMARY,
            EMOJI_SUMMARY_LIST,
            AUDIO_SUMMARY,
            AUDIO_SUMMARY_LIST,
            VIDEO_SUMMARY,
            VIDEO_SUMMARY_LIST);

    private CacheNames() {
    }
}
