package com.example.javaagentmvp.dbagent;

import com.example.javaagentmvp.dbagent.persistence.mapper.ReleaseMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
class DeployFailureRecorder {

    private final ReleaseMapper releaseMapper;

    DeployFailureRecorder(ReleaseMapper releaseMapper) {
        this.releaseMapper = releaseMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void record(long deploymentId, String releaseId, String log, Instant finished) {
        releaseMapper.updateDeployment(deploymentId, DeploymentStatus.FAILED.name(), log, finished);
        releaseMapper.updateReleaseStatus(releaseId, ReleaseStatus.FAILED.name(), finished);
    }
}
