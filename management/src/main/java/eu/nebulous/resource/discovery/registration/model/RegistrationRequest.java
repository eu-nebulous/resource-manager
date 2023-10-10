package eu.nebulous.resource.discovery.registration.model;

import lombok.AccessLevel;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@SuperBuilder
@NoArgsConstructor
@Document(collection = "registration_request")
public class RegistrationRequest {
    @Id
    private String id;
    private Device device;
    private String requester;
    private Instant requestDate;
    private Instant lastUpdateDate;
    private Instant archiveDate;
    private RegistrationRequestStatus status;
    private List<RegistrationRequestHistoryEntry> history = new ArrayList<>();
    private String nodeReference;
    @Setter(AccessLevel.NONE)
    private List<String> messages = new ArrayList<>();
}
