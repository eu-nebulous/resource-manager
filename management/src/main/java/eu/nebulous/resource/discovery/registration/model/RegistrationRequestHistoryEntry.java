package eu.nebulous.resource.discovery.registration.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

@Data
@SuperBuilder
@NoArgsConstructor
public class RegistrationRequestHistoryEntry {
    private String requestId;
    private RegistrationRequestStatus status;
    private String extraInfo;
    private Instant entryDate;
}
