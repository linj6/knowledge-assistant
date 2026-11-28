package com.lnjecit.knowledge.assistant.ingestion.git;

import com.lnjecit.knowledge.assistant.common.error.BusinessException;
import com.lnjecit.knowledge.assistant.common.error.CommonErrorCode;
import com.lnjecit.knowledge.assistant.ingestion.git.GitBrowseSnapshotStore.Snapshot;
import com.lnjecit.knowledge.assistant.ingestion.git.GitBrowseSnapshotStore.View;
import com.lnjecit.knowledge.assistant.ingestion.git.GitCommandRunner.Command;
import com.lnjecit.knowledge.assistant.ingestion.git.GitRepositoryProperties.Repository;
import com.lnjecit.knowledge.assistant.ingestion.upload.UploadProperties;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

@Service
public class LocalGitRepositoryService {
    private static final int MAX_FILES = 30;
    private final GitRepositoryProperties properties;
    private final GitCommandRunner git;
    private final GitBrowseSnapshotStore snapshots;
    private final UploadProperties upload;

    public LocalGitRepositoryService(
            GitRepositoryProperties properties,
            GitCommandRunner git,
            GitBrowseSnapshotStore snapshots,
            UploadProperties upload) {
        this.properties = properties;
        this.git = git;
        this.snapshots = snapshots;
        this.upload = upload;
    }

    public record RepositoryView(String repositoryKey, String name) {}

    public record Entry(String path, boolean directory, Long sizeBytes) {}

    private record TreeEntry(Entry view, String oid) {}

    public List<RepositoryView> list(long userId) {
        authorize(userId);
        return properties.getRepositories().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry ->
                        new RepositoryView(entry.getKey(), entry.getValue().name()))
                .toList();
    }

    public View createSnapshot(long userId, String repositoryKey) {
        authorize(userId);
        Path root = repositoryRoot(repositoryKey);
        // 先后核对 HEAD，避免切换分支时把分支和提交绑定到不同版本。
        String ref = line(git.run(root, Command.BRANCH));
        String sha = line(git.run(root, Command.HEAD));
        if (!ref.equals(line(git.run(root, Command.BRANCH))) || !sha.matches("[0-9a-f]{40}|[0-9a-f]{64}")) {
            throw new BusinessException(GitErrorCode.REPOSITORY_UNREADABLE);
        }
        String branch = ref.equals("HEAD") ? ref : ref.replaceFirst("^refs/heads/", "");
        return snapshots.create(userId, repositoryKey, root, branch, sha);
    }

    public List<Entry> entries(long userId, String snapshotId, String path) {
        Snapshot snapshot = requireSnapshot(userId, snapshotId);
        String oid = snapshot.view().commitSha();
        if (!path.isEmpty()) {
            TreeEntry directory = resolve(snapshot, path, new LinkedHashMap<>());
            if (!directory.view().directory()) throw new BusinessException(GitErrorCode.INVALID_SELECTION);
            oid = directory.oid();
        }
        return tree(snapshot.root(), oid, false, path).stream()
                .map(TreeEntry::view)
                .toList();
    }

    public List<Entry> preview(long userId, String snapshotId, List<String> paths) {
        Snapshot snapshot = requireSnapshot(userId, snapshotId);
        if (paths == null || paths.isEmpty() || paths.size() > 256) {
            throw new BusinessException(GitErrorCode.INVALID_SELECTION);
        }
        // 所有选择都从固定提交解析，展开后按完整路径去重。
        Map<String, Entry> files = new TreeMap<>();
        Map<String, List<TreeEntry>> directories = new LinkedHashMap<>();
        for (String path : paths.stream().distinct().toList()) {
            if ("".equals(path)) {
                includeDirectory(snapshot, snapshot.view().commitSha(), "", files);
                continue;
            }
            TreeEntry selection = resolve(snapshot, path, directories);
            if (selection.view().directory()) {
                includeDirectory(snapshot, selection.oid(), path, files);
            } else {
                includeFile(selection.view(), files);
            }
        }
        return List.copyOf(files.values());
    }

    public byte[] readBlob(long userId, String snapshotId, String path) {
        Snapshot snapshot = requireSnapshot(userId, snapshotId);
        TreeEntry file = resolve(snapshot, path, new LinkedHashMap<>());
        if (file.view().directory()) throw new BusinessException(GitErrorCode.INVALID_SELECTION);
        checkSize(file.view());
        return git.run(snapshot.root(), Command.BLOB, file.oid());
    }

    private void includeDirectory(Snapshot snapshot, String oid, String path, Map<String, Entry> files) {
        for (TreeEntry entry : tree(snapshot.root(), oid, true, path)) {
            if (!entry.view().directory()) includeFile(entry.view(), files);
        }
    }

    private void includeFile(Entry entry, Map<String, Entry> files) {
        checkSize(entry);
        files.putIfAbsent(entry.path(), entry);
        if (files.size() > MAX_FILES) throw new BusinessException(GitErrorCode.FILE_COUNT_LIMIT);
    }

    private void checkSize(Entry entry) {
        if (entry.sizeBytes() > upload.getMaxFileBytes()) throw new BusinessException(GitErrorCode.FILE_SIZE_LIMIT);
    }

    private TreeEntry resolve(Snapshot snapshot, String path, Map<String, List<TreeEntry>> directories) {
        if (!validPath(path)) throw new BusinessException(GitErrorCode.INVALID_SELECTION);
        String oid = snapshot.view().commitSha();
        String prefix = "";
        TreeEntry match = null;
        // 逐层匹配 Git Tree 原始名称，只向 Git 传递服务端解析出的对象 ID。
        for (String component : path.split("/")) {
            if (match != null && !match.view().directory()) {
                throw new BusinessException(GitErrorCode.INVALID_SELECTION);
            }
            String expected = prefix.isEmpty() ? component : prefix + "/" + component;
            List<TreeEntry> children = directories.get(prefix);
            if (children == null) {
                children = tree(snapshot.root(), oid, false, prefix);
                // 多选时复用近期目录列表，同时限制单次请求保留的枚举结果。
                if (directories.size() >= 4)
                    directories.remove(directories.keySet().iterator().next());
                directories.put(prefix, children);
            }
            match = children.stream()
                    .filter(entry -> entry.view().path().equals(expected))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(GitErrorCode.INVALID_SELECTION));
            oid = match.oid();
            prefix = expected;
        }
        return match;
    }

    private List<TreeEntry> tree(Path root, String oid, boolean recursive, String prefix) {
        byte[] output = git.run(root, recursive ? Command.RECURSIVE_TREE : Command.TREE, oid);
        List<TreeEntry> entries = new ArrayList<>();
        int start = 0;
        for (int end = 0; end < output.length; end++) {
            if (output[end] != 0) continue;
            String record = decode(output, start, end - start);
            start = end + 1;
            if (record == null) continue;
            int tab = record.indexOf('\t');
            String[] metadata = record.substring(0, tab).split(" +");
            String name = record.substring(tab + 1);
            String path = prefix.isEmpty() ? name : prefix + "/" + name;
            if (!validPath(path)) continue;
            if (metadata[0].equals("040000")) {
                entries.add(new TreeEntry(new Entry(path, true, null), metadata[2]));
            } else if ((metadata[0].equals("100644") || metadata[0].equals("100755"))
                    && path.toLowerCase(Locale.ROOT).endsWith(".md")) {
                if (!metadata[3].matches("[0-9]+")) throw new BusinessException(GitErrorCode.REPOSITORY_UNREADABLE);
                entries.add(new TreeEntry(new Entry(path, false, Long.parseLong(metadata[3])), metadata[2]));
            }
        }
        return entries;
    }

    private static String decode(byte[] bytes, int offset, int length) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(ByteBuffer.wrap(bytes, offset, length))
                    .toString();
        } catch (CharacterCodingException ex) {
            // 无法用 API 的 UTF-8 路径无损表示时跳过该条目。
            return null;
        }
    }

    private static boolean validPath(String path) {
        if (path == null
                || path.isEmpty()
                || path.indexOf('\\') >= 0
                || path.indexOf('\0') >= 0
                || path.matches("(?s)^[A-Za-z]:.*")) return false;
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.equalsIgnoreCase(".git")) return false;
        }
        return true;
    }

    private Snapshot requireSnapshot(long userId, String id) {
        authorize(userId);
        Snapshot snapshot = snapshots.require(userId, id);
        if (!repositoryRoot(snapshot.view().repositoryKey()).equals(snapshot.root())) {
            throw new BusinessException(GitErrorCode.REPOSITORY_UNREADABLE);
        }
        return snapshot;
    }

    private void authorize(long userId) {
        if (!properties.isEnabled()) throw new BusinessException(GitErrorCode.DISABLED);
        if (!properties.getAllowedUserIds().contains(userId)) throw new BusinessException(GitErrorCode.FORBIDDEN);
    }

    private Path repositoryRoot(String key) {
        Repository repository = properties.getRepositories().get(key);
        if (repository == null) throw new BusinessException(CommonErrorCode.NOT_FOUND);
        try {
            Path configured = Path.of(repository.path());
            if (configured.isAbsolute()) {
                Path root = configured.toRealPath();
                Path gitDirectory = root.resolve(".git");
                // 普通工作区应有自己的 .git 目录，并与 Git 报告的仓库根目录一致。
                if (Files.isDirectory(gitDirectory, LinkOption.NOFOLLOW_LINKS)
                        && root.equals(
                                Path.of(line(git.run(root, Command.ROOT))).toRealPath())) {
                    return root;
                }
            }
        } catch (IOException | InvalidPathException ex) {
            throw new BusinessException(GitErrorCode.REPOSITORY_UNREADABLE);
        }
        throw new BusinessException(GitErrorCode.REPOSITORY_UNREADABLE);
    }

    private static String line(byte[] value) {
        return new String(value, StandardCharsets.UTF_8).replaceFirst("\\r?\\n$", "");
    }
}
