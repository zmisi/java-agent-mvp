package com.example.javaagentmvp.admissionworkflow.persistence.mapper;

import com.example.javaagentmvp.admissionworkflow.persistence.model.WorkflowCheckpointRecord;
import com.example.javaagentmvp.admissionworkflow.persistence.model.WorkflowRunRecord;
import com.example.javaagentmvp.admissionworkflow.persistence.model.WorkflowRunSummaryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface WorkflowRunMapper {

    int insert(WorkflowRunRecord run);

    int updateStatus(
            @Param("id") String id,
            @Param("status") String status,
            @Param("updatedAt") Instant updatedAt,
            @Param("finishedAt") Instant finishedAt,
            @Param("errorMessage") String errorMessage);

    int updateResult(
            @Param("id") String id,
            @Param("status") String status,
            @Param("resultJson") String resultJson,
            @Param("updatedAt") Instant updatedAt,
            @Param("finishedAt") Instant finishedAt);

    WorkflowRunSummaryRow selectSummaryById(@Param("id") String id);

    int countByIdAndUserId(@Param("id") String id, @Param("userId") long userId);
}
