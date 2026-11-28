package com.lnjecit.knowledge.assistant.ingestion.git;

import com.lnjecit.knowledge.assistant.common.error.BusinessException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class GitBrowseSnapshotStore {
    private final Map<String, Snapshot> snapshots = new HashMap<>();
    private final GitRepositoryProperties properties;
    private final Clock clock;

    @Autowired
    public GitBrowseSnapshotStore(GitRepositoryProperties properties) {
        this(properties, Clock.systemUTC());
    }

    GitBrowseSnapshotStore(GitRepositoryProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public record View(String snapshotId, String repositoryKey, String branch, String commitSha, Instant expiresAt) {}

    record Snapshot(long userId, Path root, View view) {}

    synchronized View create(long userId, String repositoryKey, Path root, String branch, String sha) {
        Instant now = clock.instant();
        snapshots.values().removeIf(snapshot -> !snapshot.view().expiresAt().isAfter(now));
        if (snapshots.size() >= properties.getSnapshotCapacity()) {
            throw new BusinessException(GitErrorCode.SNAPSHOT_CAPACITY);
        }
        View view = new View(
                UUID.randomUUID().toString(), repositoryKey, branch, sha, now.plus(properties.getSnapshotTtl()));
        snapshots.put(view.snapshotId(), new Snapshot(userId, root, view));
        return view;
    }

    synchronized Snapshot require(long userId, String id) {
        Snapshot snapshot = snapshots.get(id);
        if (snapshot == null || snapshot.userId() != userId) {
            throw new BusinessException(GitErrorCode.SNAPSHOT_EXPIRED);
        }
        if (!snapshot.view().expiresAt().isAfter(clock.instant())) {
            snapshots.remove(id);
            throw new BusinessException(GitErrorCode.SNAPSHOT_EXPIRED);
        }
        return snapshot;
    }
}
