package com.example.javaagentmvp.dbagent;

import com.example.javaagentmvp.dbagent.persistence.mapper.ReleaseMapper;
import com.example.javaagentmvp.dbagent.persistence.model.DeploymentRow;
import com.example.javaagentmvp.dbagent.persistence.model.ReleaseRow;
import com.example.javaagentmvp.dbagent.persistence.model.ReleaseScriptRow;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class ReleaseService {

    static final Pattern REL_ID_PATTERN = Pattern.compile("REL-\\d{8}-[a-z0-9]{6}");

    private final ReleaseMapper releaseMapper;
    private final DesignDocumentService designDocumentService;
    private final DraftSqlGenerator draftSqlGenerator;
    private final TestDbDeployService testDbDeployService;
    private final DbAgentTargetRegistry targetRegistry;
    private final DeployFailureRecorder deployFailureRecorder;
    private final Path releasesRoot;

    public ReleaseService(
            ReleaseMapper releaseMapper,
            DesignDocumentService designDocumentService,
            DraftSqlGenerator draftSqlGenerator,
            TestDbDeployService testDbDeployService,
            DbAgentTargetRegistry targetRegistry,
            DeployFailureRecorder deployFailureRecorder,
            ResolvedDbAgentPaths paths) {
        this.releaseMapper = releaseMapper;
        this.designDocumentService = designDocumentService;
        this.draftSqlGenerator = draftSqlGenerator;
        this.testDbDeployService = testDbDeployService;
        this.targetRegistry = targetRegistry;
        this.deployFailureRecorder = deployFailureRecorder;
        this.releasesRoot = paths.releasesRoot();
    }

    public List<ReleaseSummary> listReleases() {
        return releaseMapper.listReleases().stream()
                .map(this::toSummary)
                .toList();
    }

    public ReleaseDetail getRelease(String releaseId) {
        ReleaseRow release = requireRelease(releaseId);
        List<ReleaseScriptView> scripts = releaseMapper.listScriptsByReleaseId(releaseId).stream()
                .map(this::toScriptView)
                .toList();
        List<DeploymentView> deployments = releaseMapper.listDeploymentsByReleaseId(releaseId).stream()
                .map(this::toDeploymentView)
                .toList();
        return new ReleaseDetail(
                release.getId(),
                release.getTitle(),
                release.getDesignDocPath(),
                release.getStatus(),
                release.getCreatedAt(),
                release.getUpdatedAt(),
                scripts,
                deployments);
    }

    public String suggestReleaseId() {
        return newReleaseId();
    }

    @Transactional
    public ReleaseDetail createFromDesignDoc(String designDocPath, String title) {
        if (title == null || title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title is required");
        }
        var doc = designDocumentService.readDocument(designDocPath);
        String releaseTitle = title.strip();

        String releaseId = allocateReleaseId(releaseTitle);
        Instant now = Instant.now();

        String sql = draftSqlGenerator.generate(releaseTitle, doc.content());
        String fileName = "001_" + sanitizeFileStem(doc.relativePath()) + ".sql";

        releaseMapper.insertRelease(
                releaseId,
                releaseTitle,
                doc.relativePath(),
                ReleaseStatus.DRAFT.name(),
                now,
                now);

        ReleaseScriptRow script = new ReleaseScriptRow();
        script.setReleaseId(releaseId);
        script.setFileName(fileName);
        script.setSqlContent(sql);
        script.setStatus(ReleaseScriptStatus.DRAFT.name());
        script.setReviewComment(null);
        script.setSortOrder(1);
        script.setCreatedAt(now);
        script.setUpdatedAt(now);
        releaseMapper.insertScript(script);

        writeSqlFile(releaseId, fileName, sql);

        return getRelease(releaseId);
    }

    @Transactional
    public ReleaseScriptView updateScriptSql(String releaseId, long scriptId, String sqlContent) {
        requireRelease(releaseId);
        ReleaseScriptRow script = requireScript(scriptId, releaseId);
        ReleaseScriptStatus status = ReleaseScriptStatus.valueOf(script.getStatus());
        if (!status.editable()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "script is not editable in status " + status);
        }
        if (sqlContent == null || sqlContent.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sqlContent is required");
        }

        Instant now = Instant.now();
        releaseMapper.updateScriptContent(scriptId, sqlContent.strip(), ReleaseScriptStatus.DRAFT.name(), null, now);
        writeSqlFile(releaseId, script.getFileName(), sqlContent.strip());
        refreshReleaseStatus(releaseId, now);

        return toScriptView(requireScript(scriptId, releaseId));
    }

    @Transactional
    public ReleaseScriptView submitForReview(String releaseId, long scriptId) {
        requireRelease(releaseId);
        ReleaseScriptRow script = requireScript(scriptId, releaseId);
        ReleaseScriptStatus status = ReleaseScriptStatus.valueOf(script.getStatus());
        if (status != ReleaseScriptStatus.DRAFT && status != ReleaseScriptStatus.REJECTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "only DRAFT or REJECTED scripts can be submitted");
        }

        Instant now = Instant.now();
        releaseMapper.updateScriptStatus(scriptId, ReleaseScriptStatus.PENDING_REVIEW.name(), null, now);
        releaseMapper.updateReleaseStatus(releaseId, ReleaseStatus.IN_REVIEW.name(), now);

        return toScriptView(requireScript(scriptId, releaseId));
    }

    @Transactional
    public ReleaseScriptView reviewScript(String releaseId, long scriptId, boolean approve, String comment) {
        requireRelease(releaseId);
        ReleaseScriptRow script = requireScript(scriptId, releaseId);
        if (ReleaseScriptStatus.valueOf(script.getStatus()) != ReleaseScriptStatus.PENDING_REVIEW) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "script is not pending review");
        }

        Instant now = Instant.now();
        if (approve) {
            releaseMapper.updateScriptStatus(scriptId, ReleaseScriptStatus.APPROVED.name(), comment, now);
        }
        else {
            releaseMapper.updateScriptStatus(scriptId, ReleaseScriptStatus.REJECTED.name(), comment, now);
        }
        refreshReleaseStatus(releaseId, now);

        return toScriptView(requireScript(scriptId, releaseId));
    }

    @Transactional
    public DeploymentView deployToTest(String releaseId) {
        requireRelease(releaseId);
        List<ReleaseScriptRow> scripts = releaseMapper.listScriptsByReleaseId(releaseId);
        if (scripts.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "release has no scripts");
        }
        for (ReleaseScriptRow script : scripts) {
            if (ReleaseScriptStatus.valueOf(script.getStatus()) != ReleaseScriptStatus.APPROVED) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "all scripts must be APPROVED before deploy; found " + script.getStatus());
            }
        }

        Instant started = Instant.now();
        DeploymentRow deployment = new DeploymentRow();
        deployment.setReleaseId(releaseId);
        deployment.setEnvironment("test");
        deployment.setStatus(DeploymentStatus.RUNNING.name());
        deployment.setLog("");
        deployment.setStartedAt(started);
        deployment.setFinishedAt(null);
        releaseMapper.insertDeployment(deployment);

        StringBuilder log = new StringBuilder();
        log.append("Target: ")
                .append(targetRegistry.deployTargetKey())
                .append(" (schema ")
                .append(targetRegistry.deploySchema())
                .append(")\n\n");
        try {
            for (ReleaseScriptRow script : scripts) {
                log.append("==> ").append(script.getFileName()).append('\n');
                testDbDeployService.deploy(script.getSqlContent());
                log.append("OK\n\n");
                releaseMapper.updateScriptStatus(
                        script.getId(), ReleaseScriptStatus.DEPLOYED.name(), script.getReviewComment(), Instant.now());
            }
            Instant finished = Instant.now();
            releaseMapper.updateDeployment(
                    deployment.getId(), DeploymentStatus.SUCCESS.name(), log.toString(), finished);
            releaseMapper.updateReleaseStatus(releaseId, ReleaseStatus.DEPLOYED_TEST.name(), finished);
        }
        catch (RuntimeException ex) {
            Instant finished = Instant.now();
            log.append("ERROR: ").append(ex.getMessage()).append('\n');
            deployFailureRecorder.record(deployment.getId(), releaseId, log.toString(), finished);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "test deploy failed: " + ex.getMessage(), ex);
        }

        return toDeploymentView(requireDeployment(deployment.getId()));
    }

    private void refreshReleaseStatus(String releaseId, Instant now) {
        List<ReleaseScriptRow> scripts = releaseMapper.listScriptsByReleaseId(releaseId);
        ReleaseStatus aggregate = aggregateReleaseStatus(scripts);
        releaseMapper.updateReleaseStatus(releaseId, aggregate.name(), now);
    }

    private static ReleaseStatus aggregateReleaseStatus(List<ReleaseScriptRow> scripts) {
        boolean anyRejected = scripts.stream()
                .anyMatch(s -> ReleaseScriptStatus.REJECTED.name().equals(s.getStatus()));
        if (anyRejected) {
            return ReleaseStatus.DRAFT;
        }
        boolean anyPending = scripts.stream()
                .anyMatch(s -> ReleaseScriptStatus.PENDING_REVIEW.name().equals(s.getStatus()));
        if (anyPending) {
            return ReleaseStatus.IN_REVIEW;
        }
        boolean allApprovedOrDeployed = scripts.stream()
                .allMatch(s -> {
                    ReleaseScriptStatus st = ReleaseScriptStatus.valueOf(s.getStatus());
                    return st == ReleaseScriptStatus.APPROVED || st == ReleaseScriptStatus.DEPLOYED;
                });
        if (allApprovedOrDeployed && !scripts.isEmpty()) {
            boolean allDeployed = scripts.stream()
                    .allMatch(s -> ReleaseScriptStatus.DEPLOYED.name().equals(s.getStatus()));
            if (allDeployed) {
                return ReleaseStatus.DEPLOYED_TEST;
            }
            return ReleaseStatus.APPROVED;
        }
        return ReleaseStatus.DRAFT;
    }

    private void writeSqlFile(String releaseId, String fileName, String sql) {
        try {
            Path dir = releasesRoot.resolve(releaseId);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(fileName), sql);
        }
        catch (IOException ex) {
            throw new IllegalStateException("Failed to write SQL file for release " + releaseId, ex);
        }
    }

    private ReleaseRow requireRelease(String releaseId) {
        return releaseMapper.selectReleaseById(releaseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "release not found"));
    }

    private ReleaseScriptRow requireScript(long scriptId, String releaseId) {
        ReleaseScriptRow script = releaseMapper.selectScriptById(scriptId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "script not found"));
        if (!releaseId.equals(script.getReleaseId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "script not found");
        }
        return script;
    }

    private DeploymentRow requireDeployment(long deploymentId) {
        return releaseMapper.selectDeploymentById(deploymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "deployment not found"));
    }

    private String allocateReleaseId(String title) {
        String candidate = title.strip();
        if (REL_ID_PATTERN.matcher(candidate).matches()
                && releaseMapper.selectReleaseById(candidate).isEmpty()) {
            return candidate;
        }
        return newReleaseId();
    }

    private static String newReleaseId() {
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        return "REL-" + day + "-" + suffix;
    }

    private static String sanitizeFileStem(String relativePath) {
        String stem = relativePath;
        int slash = stem.lastIndexOf('/');
        if (slash >= 0) {
            stem = stem.substring(slash + 1);
        }
        if (stem.endsWith(".md")) {
            stem = stem.substring(0, stem.length() - 3);
        }
        return stem.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }

    private ReleaseSummary toSummary(ReleaseRow row) {
        return new ReleaseSummary(
                row.getId(),
                row.getTitle(),
                row.getDesignDocPath(),
                row.getStatus(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }

    private ReleaseScriptView toScriptView(ReleaseScriptRow row) {
        return new ReleaseScriptView(
                row.getId(),
                row.getFileName(),
                row.getSqlContent(),
                row.getStatus(),
                row.getReviewComment(),
                row.getSortOrder(),
                row.getUpdatedAt() != null ? row.getUpdatedAt().toString() : null);
    }

    private DeploymentView toDeploymentView(DeploymentRow row) {
        return new DeploymentView(
                row.getId(),
                row.getEnvironment(),
                row.getStatus(),
                row.getLog(),
                row.getStartedAt() != null ? row.getStartedAt().toString() : null,
                row.getFinishedAt() != null ? row.getFinishedAt().toString() : null);
    }

    public record ReleaseSummary(
            String id,
            String title,
            String designDocPath,
            String status,
            String createdAt,
            String updatedAt) {
    }

    public record ReleaseScriptView(
            long id,
            String fileName,
            String sqlContent,
            String status,
            String reviewComment,
            int sortOrder,
            String updatedAt) {
    }

    public record DeploymentView(
            long id,
            String environment,
            String status,
            String log,
            String startedAt,
            String finishedAt) {
    }

    public record ReleaseDetail(
            String id,
            String title,
            String designDocPath,
            String status,
            String createdAt,
            String updatedAt,
            List<ReleaseScriptView> scripts,
            List<DeploymentView> deployments) {
    }
}
