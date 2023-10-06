package eu.nebulous.resource.discovery.registration.repository;

import eu.nebulous.resource.discovery.registration.model.ArchivedRegistrationRequest;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ArchivedRegistrationRequestRepository extends MongoRepository<ArchivedRegistrationRequest, String> {
	List<ArchivedRegistrationRequest> findByRequester(String requester);
}
