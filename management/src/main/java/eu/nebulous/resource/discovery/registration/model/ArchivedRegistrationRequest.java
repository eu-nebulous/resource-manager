package eu.nebulous.resource.discovery.registration.model;

import lombok.NonNull;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "archived_registration_request")
public class ArchivedRegistrationRequest extends RegistrationRequest {
    public static ArchivedRegistrationRequest fromRegistrationRequest(@NonNull RegistrationRequest registrationRequest) {
        ArchivedRegistrationRequest archivedRequest = new ArchivedRegistrationRequest();
        archivedRequest.setId(registrationRequest.getId());
        archivedRequest.setDevice(registrationRequest.getDevice());
        archivedRequest.setRequester(registrationRequest.getRequester());
        archivedRequest.setRequestDate(registrationRequest.getRequestDate());
        archivedRequest.setLastUpdateDate(registrationRequest.getLastUpdateDate());
        archivedRequest.setArchiveDate(registrationRequest.getArchiveDate());
        archivedRequest.setStatus(registrationRequest.getStatus());
        archivedRequest.setHistory(registrationRequest.getHistory());
        return archivedRequest;
    }

    public RegistrationRequest toRegistrationRequest() {
        return RegistrationRequest.builder()
                .id(this.getId())
                .device(this.getDevice())
                .requester(this.getRequester())
                .requestDate(this.getRequestDate())
                .lastUpdateDate(this.getLastUpdateDate())
                .archiveDate(this.getArchiveDate())
                .status(this.getStatus())
                .history(this.getHistory())
                .build();
    }
}
