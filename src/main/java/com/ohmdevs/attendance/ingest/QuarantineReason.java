package com.ohmdevs.attendance.ingest;

public enum QuarantineReason {
    /** The clock's code for this person has no employee mapping at the device's site. */
    UNKNOWN_EMPLOYEE,
    /** The punch is dated ahead of the server clock by more than the tolerance: the device clock is wrong. */
    FUTURE_TIMESTAMP
}
