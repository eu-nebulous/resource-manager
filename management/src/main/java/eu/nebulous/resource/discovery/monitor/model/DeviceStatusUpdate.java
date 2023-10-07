package eu.nebulous.resource.discovery.monitor.model;

import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
public class DeviceStatusUpdate {
    private String ipAddress;
    private String clientId;
    private String state;
    private Instant stateLastUpdate;
    private String reference;
    private List<String> errors;
}
