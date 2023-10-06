package eu.nebulous.resource.discovery.registration.model;

import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "archived_registration_request")
public class ArchivedRegistrationRequest extends RegistrationRequest {
}
