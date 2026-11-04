package cn.originalrpg.api.model;

/** Destructive or ownership-changing community decisions requiring two current governors. */
public enum CommunityLifecycleAction {
    SUCCESSION("community.succession"),
    DISSOLUTION("community.dissolution");

    private final String approvalKey;

    CommunityLifecycleAction(String approvalKey) {
        this.approvalKey = approvalKey;
    }

    /** Key used in the existing community approval ledger, scoped to a proposal UUID. */
    public String approvalKey() {
        return approvalKey;
    }
}
