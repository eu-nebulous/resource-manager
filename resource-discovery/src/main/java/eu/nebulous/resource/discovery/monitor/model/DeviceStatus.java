package eu.nebulous.resource.discovery.monitor.model;

public enum DeviceStatus {
    NEW_DEVICE, ON_HOLD,
    ONBOARDING, ONBOARDED, ONBOARD_ERROR,
    HEALTHY, SUSPECT, FAILED,
    BUSY, IDLE,
    OFFBOARDING, OFFBOARDED, OFFBOARD_ERROR, COMPROMISED
}
