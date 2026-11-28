package com.lnjecit.knowledge.assistant.ingestion.git;

import com.lnjecit.knowledge.assistant.auth.service.AuthPrincipal;
import com.lnjecit.knowledge.assistant.common.api.Result;
import com.lnjecit.knowledge.assistant.ingestion.git.GitBrowseSnapshotStore.View;
import com.lnjecit.knowledge.assistant.ingestion.git.LocalGitRepositoryService.Entry;
import com.lnjecit.knowledge.assistant.ingestion.git.LocalGitRepositoryService.RepositoryView;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class GitRepositoryController {
    private final LocalGitRepositoryService service;

    public GitRepositoryController(LocalGitRepositoryService service) {
        this.service = service;
    }

    public record PreviewRequest(List<String> paths) {}

    @GetMapping("/git-repositories")
    public Result<List<RepositoryView>> list(Authentication authentication) {
        return Result.success(service.list(AuthPrincipal.requireUserId(authentication)));
    }

    @PostMapping("/git-repositories/{repositoryKey}/snapshots")
    @ResponseStatus(HttpStatus.CREATED)
    public Result<View> snapshot(@PathVariable String repositoryKey, Authentication authentication) {
        return Result.success(service.createSnapshot(AuthPrincipal.requireUserId(authentication), repositoryKey));
    }

    @GetMapping("/git-snapshots/{snapshotId}/entries")
    public Result<List<Entry>> entries(
            @PathVariable String snapshotId,
            @RequestParam(defaultValue = "") String path,
            Authentication authentication) {
        return Result.success(service.entries(AuthPrincipal.requireUserId(authentication), snapshotId, path));
    }

    @PostMapping("/git-snapshots/{snapshotId}/manifest:preview")
    public Result<List<Entry>> preview(
            @PathVariable String snapshotId, @RequestBody PreviewRequest request, Authentication authentication) {
        return Result.success(
                service.preview(AuthPrincipal.requireUserId(authentication), snapshotId, request.paths()));
    }
}
