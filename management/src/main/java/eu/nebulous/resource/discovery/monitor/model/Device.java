package eu.nebulous.resource.discovery.monitor.model;

import eu.nebulous.resource.discovery.registration.model.RegistrationRequest;
import lombok.AccessLevel;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Setter;
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
    private String id;
    private String os;
    private String name;
    private String owner;
    private String ipAddress;
    private String username;
    private char[] password;
    private char[] publicKey;
    private Map<String, String> deviceInfo;

    private RegistrationRequest request;
    private String requestId;
    private Instant creationDate;
    private Instant lastUpdateDate;
    private Instant archiveDate;
    private DeviceStatus status;

    private String nodeReference;
    @Setter(AccessLevel.NONE)
    private List<String> messages = new ArrayList<>();
}
