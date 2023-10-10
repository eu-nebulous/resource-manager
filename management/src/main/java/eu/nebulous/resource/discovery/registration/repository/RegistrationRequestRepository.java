package eu.nebulous.resource.discovery.registration.repository;

import eu.nebulous.resource.discovery.registration.model.RegistrationRequest;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface RegistrationRequestRepository extends MongoRepository<RegistrationRequest, String> {
	List<RegistrationRequest> findByRequester(String requester);
	List<RegistrationRequest> findByDeviceIpAddress(String ipAddress);
}
