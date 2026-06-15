package com.example.javaagentmvp.admissionworkflow.persistence.mapper;

import com.example.javaagentmvp.admissionworkflow.persistence.model.WorkflowCheckpointRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface WorkflowCheckpointMapper {

    int insert(WorkflowCheckpointRecord checkpoint);

    List<WorkflowCheckpointRecord> listByRunId(@Param("runId") String runId);
}
