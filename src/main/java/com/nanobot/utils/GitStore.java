package com.nanobot.utils;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Git 版本控制存储——用 JGit 管理 workspace 下的 memory 文件。
 * 完整对标 Python GitStore（utils/gitstore.py:45-390）。
 *
 * <p>功能：初始化仓库、自动提交、日志、diff、line_age（blame）、revert。
 * 依赖 JGit（对标 Python dulwich）。</p>
 *
 * <p>设计要点：
 * <ul>
 *   <li>仅追踪 workspace 下的 memory 文件（MEMORY.md、history.jsonl、SOUL.md、USER.md）</li>
 *   <li>通过 .gitignore 排除所有非追踪文件，防止意外提交敏感数据</li>
 *   <li>auto_commit() 在 Dream 处理和 /memory 命令提交时调用，生成变更快照</li>
 *   <li>revert() 从父 commit 的 tree 中读取文件内容实现回滚，无需 checkout</li>
 *   <li>初始化时检测是否已在外部 git repo 中，避免嵌套初始化</li>
 * </ul></p>
 */
public class GitStore {

    private static final Logger log = LoggerFactory.getLogger(GitStore.class);
    /** 提交时间格式化器 */
    private static final DateTimeFormatter COMMIT_TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 工作区根目录 */
    private final Path workspace;
    /** 被 git 追踪的文件列表（相对于 workspace 的路径） */
    private final List<String> trackedFiles;

    public GitStore(Path workspace, List<String> trackedFiles) {
        this.workspace = workspace;
        this.trackedFiles = List.copyOf(trackedFiles);
    }

    // -- init --
    // 对应 Python GitStore.init()

    /** 是否已初始化 git 仓库。
     *  对应 Python GitStore.is_initialized()。 */
    public boolean isInitialized() {
        return Files.isDirectory(workspace.resolve(".git"));
    }

    /** 初始化 git 仓库（若尚未初始化）。
     *  对应 Python GitStore.init()。
     *
     * @return true 为新创建，false 为已存在 */
    public boolean init() {
        if (isInitialized()) return false;
        if (isInsideGitRepo()) {
            log.warn("Workspace {} is already inside a git repo; skipping nested repo init", workspace);
            return false;
        }
        try {
            try (var git = Git.init().setDirectory(workspace.toFile()).call()) {
                // 写入 .gitignore
                var gitignore = workspace.resolve(".gitignore");
                var dreamEntries = buildGitignore();
                if (Files.exists(gitignore)) {
                    var existing = Files.readString(gitignore);
                    var existingSet = new LinkedHashSet<>(List.of(existing.split("\n")));
                    var newLines = new ArrayList<String>();
                    for (var line : dreamEntries.split("\n")) {
                        if (!existingSet.contains(line)) newLines.add(line);
                    }
                    if (!newLines.isEmpty()) {
                        var merged = existing.stripTrailing() + "\n" + String.join("\n", newLines) + "\n";
                        Files.writeString(gitignore, merged);
                    }
                } else {
                    Files.writeString(gitignore, dreamEntries);
                }

                // 确保跟踪文件存在（touch）
                for (var rel : trackedFiles) {
                    var f = workspace.resolve(rel);
                    Files.createDirectories(f.getParent());
                    if (!Files.exists(f)) Files.createFile(f);
                }

                // 初始提交
                git.add().addFilepattern(".gitignore").call();
                for (var f : trackedFiles) git.add().addFilepattern(f).call();
                git.commit()
                        .setMessage("init: nanobot memory store")
                        .setAuthor("nanobot", "nanobot@dream")
                        .setCommitter("nanobot", "nanobot@dream")
                        .call();
            }
            log.info("Git store initialized at {}", workspace);
            return true;
        } catch (Exception e) {
            log.error("Git store init failed for {}", workspace, e);
            return false;
        }
    }

    // -- auto_commit --
    // 对应 Python GitStore.auto_commit()

    /** 自动提交：stage 跟踪文件并 commit（若有变更）。
     *  对应 Python GitStore.auto_commit()。
     *
     * @return 短 SHA（8 位），无变更则 null */
    public String autoCommit(String message) {
        if (!isInitialized()) return null;
        try (var git = Git.open(workspace.toFile())) {
            // 检查是否有变更
            var status = git.status().call();
            if (status.getUncommittedChanges().isEmpty() && !status.hasUncommittedChanges()) {
                return null;
            }

            for (var f : trackedFiles) git.add().addFilepattern(f).call();
            var revCommit = git.commit()
                    .setMessage(message)
                    .setAuthor("nanobot", "nanobot@dream")
                    .setCommitter("nanobot", "nanobot@dream")
                    .call();
            if (revCommit == null) return null;
            var sha = revCommit.abbreviate(8).name();
            log.debug("Git auto-commit: {} ({})", sha, message);
            return sha;
        } catch (Exception e) {
            log.error("Git auto-commit failed: {}", message, e);
            return null;
        }
    }

    // -- log --
    // 对应 Python GitStore.log()

    /** 返回简化提交日志，最多 maxEntries 条。
     *  对应 Python GitStore.log()。 */
    public List<CommitInfo> log(int maxEntries) {
        if (!isInitialized()) return List.of();
        var entries = new ArrayList<CommitInfo>();
        try (var git = Git.open(workspace.toFile());
             var revWalk = new RevWalk(git.getRepository())) {
            var head = git.getRepository().findRef("HEAD");
            if (head == null) return List.of();
            revWalk.markStart(revWalk.parseCommit(head.getObjectId()));
            for (var commit : revWalk) {
                if (entries.size() >= maxEntries) break;
                var ts = ZonedDateTime.ofInstant(
                        Instant.ofEpochSecond(commit.getCommitTime()),
                        ZoneId.systemDefault());
                entries.add(new CommitInfo(
                        commit.abbreviate(8).name(),
                        commit.getFullMessage().strip(),
                        ts.format(COMMIT_TS_FMT)));
            }
        } catch (Exception e) {
            log.error("Git log failed", e);
        }
        return entries;
    }

    // -- line_ages --
    // 对应 Python GitStore.line_ages()

    /** 计算每行代码的最后修改天数（git blame）。
     *  对应 Python GitStore.line_ages()。 */
    public List<LineAge> lineAges(String filePath) {
        if (!isInitialized()) return List.of();
        var target = workspace.resolve(filePath);
        if (!Files.exists(target) || target.toFile().length() == 0) return List.of();
        try (var git = Git.open(workspace.toFile())) {
            var blameResult = git.blame().setFilePath(filePath).call();
            if (blameResult == null) return List.of();
            var now = ZonedDateTime.now(ZoneId.of("UTC")).toLocalDate();
            var ages = new ArrayList<LineAge>();
            // JGit: BlameResult 直接提供 getResultContents() 和逐行 blame 信息
            var rawContent = blameResult.getResultContents();
            if (rawContent == null) return List.of();
            int lineCount = rawContent.size();
            for (int i = 0; i < lineCount; i++) {
                var sourceCommit = blameResult.getSourceCommit(i);
                if (sourceCommit != null) {
                    var commitTime = Instant.ofEpochSecond(sourceCommit.getCommitTime());
                    var commitDate = commitTime.atZone(ZoneId.of("UTC")).toLocalDate();
                    ages.add(new LineAge((int) ChronoUnit.DAYS.between(commitDate, now)));
                } else {
                    ages.add(new LineAge(0));
                }
            }
            return ages;
        } catch (Exception e) {
            log.error("Git line_ages annotate failed for {}", filePath, e);
            return List.of();
        }
    }

    // -- diff_commits --
    // 对应 Python GitStore.diff_commits()

    /** 比较两个 commit 的 diff。
     *  对应 Python GitStore.diff_commits()。 */
    public String diffCommits(String sha1, String sha2) {
        if (!isInitialized()) return "";
        try (var git = Git.open(workspace.toFile())) {
            var full1 = resolveSha(git, sha1);
            var full2 = resolveSha(git, sha2);
            if (full1 == null || full2 == null) return "";
            var out = new java.io.ByteArrayOutputStream();
            git.diff()
                    .setOldTree(prepareTreeParser(git.getRepository(), full1))
                    .setNewTree(prepareTreeParser(git.getRepository(), full2))
                    .setOutputStream(out)
                    .call();
            return out.toString();
        } catch (Exception e) {
            log.error("Git diff_commits failed", e);
            return "";
        }
    }

    // -- find_commit --
    // 对应 Python GitStore.find_commit()

    /** 按短 SHA 前缀查找提交。
     *  对应 Python GitStore.find_commit()。 */
    public CommitInfo findCommit(String shortSha, int maxEntries) {
        for (var c : log(maxEntries)) {
            if (c.sha().startsWith(shortSha)) return c;
        }
        return null;
    }

    // -- show_commit_diff --
    // 对应 Python GitStore.show_commit_diff()

    /** 查找提交并返回其与父提交的 diff。
     *  对应 Python GitStore.show_commit_diff()。 */
    public Map.Entry<CommitInfo, String> showCommitDiff(String shortSha, int maxEntries) {
        var commits = log(maxEntries);
        for (int i = 0; i < commits.size(); i++) {
            var c = commits.get(i);
            if (c.sha().startsWith(shortSha)) {
                var diff = i + 1 < commits.size()
                        ? diffCommits(commits.get(i + 1).sha(), c.sha()) : "";
                return Map.entry(c, diff);
            }
        }
        return null;
    }

    // -- revert --
    // 对应 Python GitStore.revert()

    /** 撤销指定 commit 引入的变更，将跟踪文件恢复到父提交状态。
     *  对应 Python GitStore.revert()。
     *
     * @return 还原提交的 SHA，失败返回 null */
    public String revert(String commit) {
        if (!isInitialized()) return null;
        try (var git = Git.open(workspace.toFile())) {
            var fullSha = resolveSha(git, commit);
            if (fullSha == null) {
                log.warn("Git revert: SHA not found: {}", commit);
                return null;
            }
            var repo = git.getRepository();
            ObjectId commitId;
            try (var revWalk = new RevWalk(repo)) {
                var revCommit = revWalk.parseCommit(fullSha);
                if (revCommit.getParentCount() == 0) {
                    log.warn("Git revert: cannot revert root commit {}", commit);
                    return null;
                }
                // 获取父提交的 tree
                var parent = revWalk.parseCommit(revCommit.getParent(0));
                var treeWalk = new TreeWalk(repo);
                treeWalk.addTree(parent.getTree());
                treeWalk.setRecursive(true);

                var restored = new ArrayList<String>();
                while (treeWalk.next()) {
                    var filepath = treeWalk.getPathString();
                    if (trackedFiles.contains(filepath)) {
                        var loader = repo.open(treeWalk.getObjectId(0));
                        var content = new String(loader.getBytes());
                        var dest = workspace.resolve(filepath);
                        Files.writeString(dest, content);
                        restored.add(filepath);
                    }
                }
                if (restored.isEmpty()) return null;
            }

            var msg = "revert: undo " + commit;
            return autoCommit(msg);
        } catch (Exception e) {
            log.error("Git revert failed for {}", commit, e);
            return null;
        }
    }

    // -- internal helpers --
    // 对应 Python GitStore._resolve_sha()

    private ObjectId resolveSha(Git git, String shortSha) {
        try (var revWalk = new RevWalk(git.getRepository())) {
            var head = git.getRepository().findRef("HEAD");
            if (head == null) return null;
            revWalk.markStart(revWalk.parseCommit(head.getObjectId()));
            for (var commit : revWalk) {
                if (commit.abbreviate(8).name().startsWith(shortSha)) {
                    return commit.getId();
                }
            }
        } catch (IOException e) {
            // fall through
        }
        return null;
    }

    // 对应 Python GitStore._is_inside_git_repo()

    private boolean isInsideGitRepo() {
        var current = workspace.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve(".git")) || Files.isRegularFile(current.resolve(".git"))) {
                return true;
            }
            var parent = current.getParent();
            if (parent == null || parent.equals(current)) break;
            current = parent;
        }
        return false;
    }

    // 对应 Python GitStore._build_gitignore()

    private String buildGitignore() {
        var dirs = new LinkedHashSet<String>();
        for (var f : trackedFiles) {
            var parent = Path.of(f).getParent();
            if (parent != null) dirs.add(parent.toString());
        }
        var sb = new StringBuilder();
        sb.append("/*\n");
        for (var d : dirs) sb.append("!").append(d).append("/\n");
        for (var f : trackedFiles) sb.append("!").append(f).append("\n");
        sb.append("!.gitignore\n");
        return sb.toString();
    }

    // prepareTreeParser — 对应 Python _read_blob_from_tree
    private static org.eclipse.jgit.treewalk.CanonicalTreeParser prepareTreeParser(Repository repo, ObjectId commitId) throws IOException {
        try (var revWalk = new RevWalk(repo)) {
            var commit = revWalk.parseCommit(commitId);
            var treeId = commit.getTree().getId();
            var treeParser = new org.eclipse.jgit.treewalk.CanonicalTreeParser();
            try (var reader = repo.newObjectReader()) {
                treeParser.reset(reader, treeId);
            }
            return treeParser;
        }
    }
}
