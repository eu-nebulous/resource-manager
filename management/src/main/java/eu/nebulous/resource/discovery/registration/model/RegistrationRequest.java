package eu.nebulous.resource.discovery.registration.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.List;

@Data
@SuperBuilder
@NoArgsConstructor
public class RegistrationRequest {
    private String id;
    private Device device;
    private String requester;
    private Instant requestDate;
    private long lastUpdateTimestamp;
    private RegistrationRequestStatus status;
    private List<RegistrationRequestHistoryEntry> history;
}
