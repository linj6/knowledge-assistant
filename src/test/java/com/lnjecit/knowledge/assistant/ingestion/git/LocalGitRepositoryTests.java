package com.lnjecit.knowledge.assistant.ingestion.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lnjecit.knowledge.assistant.auth.service.AuthPrincipal;
import com.lnjecit.knowledge.assistant.common.error.BusinessException;
import com.lnjecit.knowledge.assistant.infrastructure.web.ApiExceptionHandler;
import com.lnjecit.knowledge.assistant.ingestion.git.GitBrowseSnapshotStore.View;
import com.lnjecit.knowledge.assistant.ingestion.git.GitRepositoryProperties.Repository;
import com.lnjecit.knowledge.assistant.ingestion.git.LocalGitRepositoryService.Entry;
import com.lnjecit.knowledge.assistant.ingestion.upload.UploadProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class LocalGitRepositoryTests {
    @TempDir
    Path root;

    private final GitRepositoryProperties properties = new GitRepositoryProperties();
    private final UploadProperties upload = new UploadProperties();
    private final GitCommandRunner runner = new GitCommandRunner(properties);
    private final GitBrowseSnapshotStore store = new GitBrowseSnapshotStore(properties);
    private final LocalGitRepositoryService service = new LocalGitRepositoryService(properties, runner, store, upload);

    @BeforeEach
    void configure() {
        properties.setAllowedUserIds(Set.of(42L, 43L));
        properties.setRepositories(Map.of("test", new Repository("测试仓库", root.toString())));
    }

    @Test
    void repositoryEndpointRequiresFeatureAndAuthorizedAccount() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new GitRepositoryController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
        mvc.perform(get("/v1/git-repositories").principal(principal(42L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40410));
        properties.setEnabled(true);
        mvc.perform(get("/v1/git-repositories").principal(principal(44L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40310));
        mvc.perform(get("/v1/git-repositories").principal(principal(42L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].repositoryKey").value("test"))
                .andExpect(jsonPath("$.data[0].path").doesNotExist());
    }

    @Test
    void exposesSnapshotEntriesAndPreviewInResultEnvelope() throws Exception {
        initialize();
        write("notes/one.md", "abc");
        git("add", ".");
        git("commit", "-qm", "notes");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new GitRepositoryController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
        String response = mvc.perform(
                        post("/v1/git-repositories/test/snapshots").principal(principal(42L)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.commitSha").value(git("rev-parse", "HEAD")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = JsonMapper.builder()
                .build()
                .readTree(response)
                .path("data")
                .path("snapshotId")
                .asString();
        mvc.perform(get("/v1/git-snapshots/{id}/entries", id)
                        .param("path", "notes")
                        .principal(principal(42L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].path").value("notes/one.md"));
        mvc.perform(post("/v1/git-snapshots/{id}/manifest:preview", id)
                        .principal(principal(42L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paths\":[\"notes\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].sizeBytes").value(3));
        mvc.perform(get("/v1/git-snapshots/{id}/entries", id).principal(principal(43L)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value(41010));
    }

    @Test
    void snapshotReadsPinnedTreeAndBlobAcrossWorkingTreeAndHeadChanges() throws Exception {
        String path = "中文目录/空 格 [#].MD";
        initialize();
        write(path, "第一版");
        write("中文目录/下级/说明.md", "nested");
        write("中文目录/ignore.txt", "ignored");
        git("add", ".");
        git("commit", "-qm", "first");
        String first = git("rev-parse", "HEAD");
        // 直接写入索引中的特殊模式，在 Windows 上也无需创建真实符号链接或子模块工作区。
        String oid = git("rev-parse", "HEAD:" + path);
        git("update-index", "--add", "--cacheinfo", "120000," + oid + ",link.md");
        git("update-index", "--add", "--cacheinfo", "160000," + first + ",module.md");
        git("commit", "-qm", "special entries");
        View snapshot = service.createSnapshot(42L, "test");
        assertThat(snapshot.branch()).isEqualTo("main");
        assertThat(snapshot.commitSha()).isEqualTo(git("rev-parse", "HEAD"));

        write(path, "未提交");
        assertThat(new String(service.readBlob(42L, snapshot.snapshotId(), path), StandardCharsets.UTF_8))
                .isEqualTo("第一版");
        git("add", "--", path);
        git("commit", "-qm", "changed");
        git("checkout", "--detach", "HEAD");
        View detached = service.createSnapshot(42L, "test");
        assertThat(detached.branch()).isEqualTo("HEAD");
        assertThat(detached.commitSha()).isNotEqualTo(snapshot.commitSha());

        assertThat(service.entries(42L, snapshot.snapshotId(), ""))
                .extracting(Entry::path)
                .containsExactly("中文目录");
        assertThat(service.entries(42L, snapshot.snapshotId(), "中文目录"))
                .extracting(Entry::path)
                .containsExactlyInAnyOrder(path, "中文目录/下级");
        assertThat(service.preview(42L, snapshot.snapshotId(), List.of("", "中文目录", path, path)))
                .extracting(Entry::path)
                .containsExactlyInAnyOrder(path, "中文目录/下级/说明.md");
        assertThat(new String(service.readBlob(42L, snapshot.snapshotId(), path), StandardCharsets.UTF_8))
                .isEqualTo("第一版");
        for (String invalid : List.of(
                "../a.md",
                "中文目录/../a.md",
                "/a.md",
                "C:/a.md",
                "a\\z.md",
                "中文目录//a.md",
                "link.md",
                "module.md",
                "中文目录/ignore.txt",
                "中文目录/新.md")) {
            assertError(
                    GitErrorCode.INVALID_SELECTION,
                    () -> service.preview(42L, snapshot.snapshotId(), List.of(invalid)));
        }
        assertError(GitErrorCode.SNAPSHOT_EXPIRED, () -> service.entries(43L, snapshot.snapshotId(), ""));
    }

    @Test
    void enforcesFileCountSizeAndCommandOutputLimits() throws Exception {
        initialize();
        for (int i = 0; i < 31; i++) write("all/" + i + ".md", "123");
        git("add", ".");
        git("commit", "-qm", "files");
        View snapshot = service.createSnapshot(42L, "test");
        List<String> thirty = new ArrayList<>();
        for (int i = 0; i < 30; i++) thirty.add("all/" + i + ".md");
        upload.setMaxFileBytes(3);
        assertThat(service.preview(42L, snapshot.snapshotId(), thirty)).hasSize(30);
        assertError(GitErrorCode.FILE_COUNT_LIMIT, () -> service.preview(42L, snapshot.snapshotId(), List.of("all")));
        upload.setMaxFileBytes(2);
        assertError(
                GitErrorCode.FILE_SIZE_LIMIT, () -> service.preview(42L, snapshot.snapshotId(), List.of("all/0.md")));
        assertError(GitErrorCode.FILE_SIZE_LIMIT, () -> service.readBlob(42L, snapshot.snapshotId(), "all/0.md"));
        properties.setMaxOutputBytes(256);
        assertError(GitErrorCode.OUTPUT_LIMIT, () -> service.entries(42L, snapshot.snapshotId(), "all"));
    }

    @Test
    void handlesRepositoryAndCommandFailures() throws Exception {
        properties.setEnabled(true);
        assertError(GitErrorCode.REPOSITORY_UNREADABLE, () -> service.createSnapshot(42L, "test"));
        initialize();
        properties.setExecutable(root.resolve("missing-git").toString());
        assertError(GitErrorCode.GIT_UNAVAILABLE, () -> service.createSnapshot(42L, "test"));
        properties.setExecutable("git");
        properties.setCommandTimeout(Duration.ofNanos(1));
        assertError(GitErrorCode.COMMAND_TIMEOUT, () -> service.createSnapshot(42L, "test"));
    }

    @Test
    void limitsSnapshotCapacityAndExpiresAtDeadline() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        properties.setSnapshotCapacity(1);
        properties.setSnapshotTtl(Duration.ofSeconds(1));
        Clock clock = org.mockito.Mockito.mock(Clock.class);
        org.mockito.Mockito.when(clock.instant()).thenReturn(now);
        GitBrowseSnapshotStore bounded = new GitBrowseSnapshotStore(properties, clock);
        View snapshot = bounded.create(42L, "test", root, "main", "sha");
        assertError(GitErrorCode.SNAPSHOT_CAPACITY, () -> bounded.create(42L, "test", root, "main", "sha"));
        org.mockito.Mockito.when(clock.instant()).thenReturn(now.plusSeconds(1));
        assertError(GitErrorCode.SNAPSHOT_EXPIRED, () -> bounded.require(42L, snapshot.snapshotId()));
        assertThat(bounded.create(42L, "test", root, "main", "sha").snapshotId())
                .isNotEqualTo(snapshot.snapshotId());
    }

    private void initialize() throws Exception {
        git("init", "-q", "-b", "main");
        git("config", "user.name", "Git Tests");
        git("config", "user.email", "git-tests@example.invalid");
        properties.setEnabled(true);
    }

    private void write(String path, String body) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }

    private String git(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).stripTrailing();
            assertThat(process.exitValue()).as(output).isZero();
            return output;
        } finally {
            process.destroyForcibly();
        }
    }

    private static UsernamePasswordAuthenticationToken principal(long id) {
        return new UsernamePasswordAuthenticationToken(new AuthPrincipal(id, "test"), null, List.of());
    }

    private static void assertError(GitErrorCode code, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode())
                        .isEqualTo(code));
    }
}
