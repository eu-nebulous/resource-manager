package eu.nebulous.resource.discovery.registration.repository;

import eu.nebulous.resource.discovery.registration.model.ArchivedRegistrationRequest;
import eu.nebulous.resource.discovery.registration.model.RegistrationRequest;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ArchivedRegistrationRequestRepository extends MongoRepository<ArchivedRegistrationRequest, String> {
	RegistrationRequest findByRequester(String requester);
}
