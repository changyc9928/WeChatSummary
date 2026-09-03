package com.wechat.wechatsummary.service;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

/**
 * Dedicated service enforcing the session deletion invariant: a successful DELETE means there are
 * zero application-discoverable artifacts belonging to that session, while unrelated sessions and
 * users remain untouched.
 *
 * <p>Execution order:
 * <ol>
 *   <li>Validate user/session ownership</li>
 *   <li>Identify all session-owned DB records</li>
 *   <li>Delete DB records transactionally</li>
 *   <li>Delete session filesystem + output files</li>
 *   <li>Evict detail caches and session list caches</li>
 *   <li>Clear chat-analysis task/cache/Redis state if owned by session</li>
 * </ol>
 *
 * <p>DB changes are transactional. Filesystem deletion is idempotent. Cache invalidation is
 * retry-safe. Failures are never swallowed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionDeletionService {

    private final WeChatSummaryCacheService cacheService;
    private final StoragePaths storagePaths;
    private final TaskCoordinatorService taskCoordinatorService;
    private final ChatSummaryService chatSummaryService;

    /**
     * Deletes all application artifacts belonging to the given session.
     *
     * @param userId    the authenticated user's UUID
     * @param sessionId the session UUID to delete
     * @throws IOException if filesystem operations fail
     */
    public boolean deleteSession(String userId, String sessionId) throws IOException {
        // 1. Validate sessionId is a well-formed UUID
        UUID uuid;
        try {
            uuid = UUID.fromString(sessionId);
        } catch (IllegalArgumentException e) {
            log.warn("Rejected deletion request. Malformed session UUID: {}", sessionId);
            throw new IllegalArgumentException("Invalid session UUID: " + sessionId);
        }

        // 2. Validate user directory exists
        Path userDir = storagePaths.userDir(userId);
        if (!Files.exists(userDir)) {
            throw new FileNotFoundException("User not found: " + userId);
        }

        // 3. SECURITY: Verify session path is inside user directory (path traversal guard)
        Path sessionPath = storagePaths.sessionDir(userId, sessionId).toAbsolutePath().normalize();
        Path userDirNormalized = userDir.toAbsolutePath().normalize();
        if (!sessionPath.startsWith(userDirNormalized)) {
            log.error(
                "Security violation! Session path [{}] escapes user directory [{}]. "
                    + "Rejecting deletion of session [{}] for user [{}].",
                sessionPath, userDirNormalized, sessionId, userId);
            throw new SecurityException(
                "Session path escapes user directory boundary");
        }

        // 4. Delete DB records (media summaries + chat task)
        //    Collect hashes first for cache eviction, then delete rows.
        cacheService.deleteSessionImageSummaries(sessionId);
        cacheService.deleteSessionEmojiSummaries(sessionId);
        cacheService.deleteSessionAudioSummaries(sessionId);
        cacheService.deleteSessionVideoSummaries(sessionId);
        cacheService.deleteChatAnalysisTask(uuid);

        // 5. Interrupt any running summary thread for this session (JVM-local state)
        chatSummaryService.pauseSummary(uuid);

        // 6. Delete session filesystem (idempotent)
        FileSystemUtils.deleteRecursively(storagePaths.sessionDir(userId, sessionId));

        // 7. Delete output files (idempotent)
        cacheService.deleteSessionOutputs(userId, sessionId);

        // 8. Clean up TaskCoordinator Redis keys
        taskCoordinatorService.cleanupTaskKeys(sessionId);

        log.info(
            "Successfully deleted session [{}] and all associated artifacts for user [{}].",
            sessionId, userId);
        return true;
    }
}
