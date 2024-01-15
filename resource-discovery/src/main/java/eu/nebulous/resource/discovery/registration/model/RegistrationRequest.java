package eu.nebulous.resource.discovery.registration.model;

import lombok.*;
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
    @Builder.Default
    private List<RegistrationRequestHistoryEntry> history = new ArrayList<>();
    private String nodeReference;
    @Setter(AccessLevel.NONE)
    @Builder.Default
    private List<String> messages = new ArrayList<>();

    // Required in order BeanUtils.copyProperties() to also copy this
    public void setHistory(List<RegistrationRequestHistoryEntry> history) {
        this.history = new ArrayList<>(history);
    }

    // Required in order BeanUtils.copyProperties() to also copy this
    public void setMessages(List<String> messages) {
        this.messages = new ArrayList<>(messages);
    }
}
