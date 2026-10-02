package cn.originalrpg.api.model;

import java.util.Objects;

public record CommissionEvidenceGrantResult(int granted, CommissionEvidenceSnapshot snapshot) {
    public CommissionEvidenceGrantResult {
        Objects.requireNonNull(snapshot, "snapshot");
        if (granted < 0 || granted > snapshot.earned()) {
            throw new IllegalArgumentException("Invalid commission evidence grant");
        }
    }
}
