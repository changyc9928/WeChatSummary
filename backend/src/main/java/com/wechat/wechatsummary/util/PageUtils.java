package com.wechat.wechatsummary.util;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * Shared pagination helpers for in-memory lists.
 */
public final class PageUtils {

    private PageUtils() {
    }

    /**
     * Wraps an already-processed in-memory list into a {@link Page} slice honoring the given
     * {@link Pageable}.
     */
    public static <T> Page<T> paginate(List<T> items, Pageable pageable) {
        int total = items.size();
        int start = (int) pageable.getOffset();

        if (start >= total) {
            return new PageImpl<>(List.of(), pageable, total);
        }

        int end = Math.min(start + pageable.getPageSize(), total);
        return new PageImpl<>(items.subList(start, end), pageable, total);
    }
}
