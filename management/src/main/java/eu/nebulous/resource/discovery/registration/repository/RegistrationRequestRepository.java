package eu.nebulous.resource.discovery.registration.repository;

import eu.nebulous.resource.discovery.registration.model.RegistrationRequest;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface RegistrationRequestRepository extends MongoRepository<RegistrationRequest, String> {
	RegistrationRequest findByRequester(String requester);
}
