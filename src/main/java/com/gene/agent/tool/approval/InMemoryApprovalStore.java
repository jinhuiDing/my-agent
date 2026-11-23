package com.gene.agent.tool.approval;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class InMemoryApprovalStore implements ApprovalStore {

    private final Map<String, ApprovalRequest> storage =
            new ConcurrentHashMap<>();

    @Override
    public void save(ApprovalRequest request) {
        ApprovalRequest previous = storage.putIfAbsent(
                request.approvalId(), request
        );

        if (previous != null) {
            throw new IllegalStateException("审批单已存在: " + request.approvalId());
        }
    }

    @Override
    public ApprovalRequest findById(String approvalId) {
        return storage.get(approvalId);
    }

    @Override
    public List<ApprovalRequest> findPending() {
        return storage.values().stream()
                .filter(request -> request.status() == ApprovalStatus.PENDING)
                .sorted(Comparator.comparing(ApprovalRequest::createdAt))
                .toList();
    }

    @Override
    public ApprovalRequest update(ApprovalRequest request) {

        storage.compute(request.approvalId(), (id, current) -> {
            if (current == null) {
                return null;
            }

            if (current.status() != ApprovalStatus.PENDING) {
                return current;
            }

             return request;
        });

        return request;
    }
}