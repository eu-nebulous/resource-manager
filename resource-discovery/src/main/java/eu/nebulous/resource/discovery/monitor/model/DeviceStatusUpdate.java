package eu.nebulous.resource.discovery.monitor.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeviceStatusUpdate {
    private String ipAddress;
    private String clientId;
    private String state;
    private Instant stateLastUpdate;
    private String reference;
    private List<String> errors;
}
