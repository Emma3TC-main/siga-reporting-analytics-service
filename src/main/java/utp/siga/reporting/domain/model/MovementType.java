package utp.siga.reporting.domain.model;

public enum MovementType {
    ENTRY,
    EXIT,
    TRANSFER,
    ADJUST_POSITIVE,
    ADJUST_NEGATIVE;

    public boolean isAdjustment() {
        return this == ADJUST_POSITIVE || this == ADJUST_NEGATIVE;
    }
}
