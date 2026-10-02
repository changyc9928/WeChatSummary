package com.wechat.wechatsummary.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "task")
@Component
@Data
public class TaskConfig {

    private Duration redisTtl = Duration.ofDays(1);

    /**
     * How long a batch may go without any sign of progress before the watchdog treats it as
     * unrecoverable and compiles the document from whatever finished. Must comfortably exceed the
     * worst-case single media item (AI backoff plus retry ladder) so a slow item is never mistaken
     * for a lost one.
     */
    private Duration stallGracePeriod = Duration.ofMinutes(20);

    /** How often the watchdog sweeps for stalled batches. */
    private Duration watchdogInterval = Duration.ofMinutes(2);
}
