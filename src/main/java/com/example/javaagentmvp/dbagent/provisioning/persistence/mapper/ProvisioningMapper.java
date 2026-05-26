package com.example.javaagentmvp.dbagent.provisioning.persistence.mapper;

import com.example.javaagentmvp.dbagent.provisioning.persistence.model.ProvisioningRequestRow;
import com.example.javaagentmvp.dbagent.provisioning.persistence.model.ProvisioningStepRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ProvisioningMapper {

    void insertRequest(ProvisioningRequestRow row);

    void updateRequestStatus(ProvisioningRequestRow row);

    ProvisioningRequestRow selectRequestById(@Param("id") String id);

    List<ProvisioningRequestRow> listRequests();

    void insertStep(ProvisioningStepRow row);

    void updateStep(ProvisioningStepRow row);

    List<ProvisioningStepRow> listStepsByRequestId(@Param("requestId") String requestId);

    void resetStepsForRequest(@Param("requestId") String requestId);
}
