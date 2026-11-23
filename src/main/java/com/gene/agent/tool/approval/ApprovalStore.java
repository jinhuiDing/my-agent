package com.gene.agent.tool.approval;

import java.util.List;

public interface ApprovalStore {

    void save(ApprovalRequest request);

    ApprovalRequest findById(String approvalId);

    List<ApprovalRequest> findPending();

    ApprovalRequest update(ApprovalRequest request);
}