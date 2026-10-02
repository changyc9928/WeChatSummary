package com.wechat.wechatsummary.service;

import com.wechat.wechatsummary.config.RabbitConfig;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Safety net that guarantees a preprocessing batch can never remain stuck forever.
 *
 * <p>The compiled markdown is gated on a Redis counter reaching zero, and that counter only drops
 * when a consumed media message reports back through
 * {@link TaskCoordinatorService#completeTask(String, Thread)}. Any message lost in flight (broker
 * restart between publish and ack, container eviction, an error path that drops the delivery)
 * therefore leaves the counter permanently above zero: the UI then reports the session as running
 * at 99% for as long as the user cares to wait, and the only escape is manually restarting the run
 * and hoping the same message is not lost again.
 *
 * <p>This watchdog closes that hole. On a fixed interval it inspects every batch that still has a
 * live counter and force-completes the ones that provably cannot progress: no registered worker
 * thread, no queued or unacknowledged message anywhere in the media topology, and a heartbeat that
 * has not moved within the grace period. Anything still genuinely in flight is left untouched, so a
 * slow run is never cut short.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PreprocessWatchdog {

    /** Queues whose depth indicates work is still in flight for some batch. */
    private static final List<String> WATCHED_QUEUES = List.of(
        RabbitConfig.IMAGE_QUEUE,
        RabbitConfig.AUDIO_QUEUE,
        RabbitConfig.VIDEO_QUEUE,
        RabbitConfig.EMOJI_QUEUE,
        RabbitConfig.IMAGE_RETRY_HOLD_QUEUE,
        RabbitConfig.AUDIO_RETRY_HOLD_QUEUE,
        RabbitConfig.VIDEO_RETRY_HOLD_QUEUE,
        RabbitConfig.EMOJI_RETRY_HOLD_QUEUE);

    private final TaskCoordinatorService coordinatorService;
    private final AmqpAdmin amqpAdmin;

    /**
     * Scans for stalled batches and closes them out.
     *
     * @return the number of batches force-completed during this sweep
     */
    @Scheduled(fixedDelayString = "${task.watchdog-interval:2m}")
    public int sweep() {
        Set<String> candidates;
        try {
            candidates = coordinatorService.findUnfinishedTaskIds();
        } catch (Exception e) {
            log.warn("Task watchdog could not enumerate unfinished tasks this sweep", e);
            return 0;
        }
        if (candidates.isEmpty()) {
            return 0;
        }

        long pending = pendingBrokerMessages();
        long gracePeriodMillis = coordinatorService.stallGracePeriod().toMillis();

        int rescued = 0;
        for (String uuid : candidates) {
            try {
                if (!coordinatorService.isStalled(uuid, pending, gracePeriodMillis)) {
                    continue;
                }
                int remaining = coordinatorService.remainingTasks(uuid);
                if (coordinatorService.forceCompleteStalledTask(uuid,
                    remaining + " sub-task(s) never reported completion")) {
                    rescued++;
                }
            } catch (Exception e) {
                log.warn("Task watchdog failed to evaluate batch UUID: [{}]", uuid, e);
            }
        }
        return rescued;
    }

    /**
     * Total messages still waiting in, or held by, any media queue. A non-zero value means some
     * batch is still legitimately in flight and no batch should be force-completed this sweep.
     */
    private long pendingBrokerMessages() {
        long total = 0;
        for (String queue : WATCHED_QUEUES) {
            try {
                QueueInformation info = amqpAdmin.getQueueInfo(queue);
                if (info != null) {
                    total += info.getMessageCount();
                }
            } catch (Exception e) {
                // A single unreachable queue must not disable the watchdog; skipping it only makes
                // the "no work in flight" signal less certain, and the heartbeat still guards us.
                log.debug("Task watchdog could not read queue depth for [{}]", queue, e);
            }
        }
        return total;
    }
}
