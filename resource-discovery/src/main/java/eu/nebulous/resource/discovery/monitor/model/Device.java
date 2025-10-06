package eu.nebulous.resource.discovery.monitor.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import eu.nebulous.resource.discovery.common.DeviceLocation;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@SuperBuilder
@NoArgsConstructor
@Document(collection = "device")
public class Device {
    private String id; //This is the internal id of the device in the Resource Discovery component 
    private String sal_id; //This identifier is used by SAL, and is used for deregistration
    private String ref; //This identifier is used by the Cloud Fog Service Broker, to ascertain which devices are available for which applications
    private Integer gpus;
    private Double price;
    private String os;
    private String name;
    private String owner;
    private String provider;
    private String ipAddress;
    private int port;
    private DeviceLocation location;
    private String username;
    @ToString.Exclude
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private char[] password;
    @ToString.Exclude
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private char[] publicKey;
    private Map<String, String> deviceInfo;

    //private RegistrationRequest request;
    private String requestId;
    private Instant creationDate;
    private Instant lastUpdateDate;
    private Instant archiveDate;
    private DeviceStatus status;

    private String nodeReference;
    @Setter(AccessLevel.NONE)
    @Builder.Default
    private List<String> messages = new ArrayList<>();

    private DeviceStatusUpdate statusUpdate;
    private DeviceMetrics metrics;

    private Instant suspectTimestamp;
    private int retries;

    private boolean registeredToSAL;

    public void incrementRetries() {
        retries++;
    }
}
