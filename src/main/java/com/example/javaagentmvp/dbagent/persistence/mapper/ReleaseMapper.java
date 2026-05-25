package com.example.javaagentmvp.dbagent.persistence.mapper;

import com.example.javaagentmvp.dbagent.persistence.model.DeploymentRow;
import com.example.javaagentmvp.dbagent.persistence.model.ReleaseRow;
import com.example.javaagentmvp.dbagent.persistence.model.ReleaseScriptRow;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReleaseMapper {

    void insertRelease(
            @Param("id") String id,
            @Param("title") String title,
            @Param("designDocPath") String designDocPath,
            @Param("status") String status,
            @Param("createdAt") Instant createdAt,
            @Param("updatedAt") Instant updatedAt);

    void updateReleaseStatus(
            @Param("id") String id,
            @Param("status") String status,
            @Param("updatedAt") Instant updatedAt);

    Optional<ReleaseRow> selectReleaseById(@Param("id") String id);

    List<ReleaseRow> listReleases();

    void insertScript(ReleaseScriptRow script);

    void updateScriptContent(
            @Param("id") long id,
            @Param("sqlContent") String sqlContent,
            @Param("status") String status,
            @Param("reviewComment") String reviewComment,
            @Param("updatedAt") Instant updatedAt);

    void updateScriptStatus(
            @Param("id") long id,
            @Param("status") String status,
            @Param("reviewComment") String reviewComment,
            @Param("updatedAt") Instant updatedAt);

    Optional<ReleaseScriptRow> selectScriptById(@Param("id") long id);

    List<ReleaseScriptRow> listScriptsByReleaseId(@Param("releaseId") String releaseId);

    void insertDeployment(DeploymentRow deployment);

    void updateDeployment(
            @Param("id") long id,
            @Param("status") String status,
            @Param("log") String log,
            @Param("finishedAt") Instant finishedAt);

    List<DeploymentRow> listDeploymentsByReleaseId(@Param("releaseId") String releaseId);

    Optional<DeploymentRow> selectDeploymentById(@Param("id") long id);
}
