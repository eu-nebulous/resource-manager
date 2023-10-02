package eu.nebulous.resource.discovery.registration.repository;

import eu.nebulous.resource.discovery.registration.model.RegistrationRequest;
import eu.nebulous.resource.discovery.registration.model.RegistrationRequestException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.*;

/**
 *  Test RegistrationRequest repository storing requests in-memory
 *  In order to use, you need to uncomment lines 25-28 in class
 *  RegistrationRequestService, and comment out lines 22-23.
 *  Then re-build and run
 */
@Slf4j
public class InMemoryRegistrationRequestRepository<T extends RegistrationRequest> {
	private final LinkedList<T> requests = new LinkedList<>();

	// ------------------------------------------------------------------------

	public Optional<T> findById(@NonNull String id) {
		return requests.stream().filter(rr -> id.equals(rr.getId())).findAny();
	}

	public List<T> findAll() {
		return Collections.unmodifiableList(requests);
	}

	public @NonNull T save(@NonNull T registrationRequest) {
		if (findById(registrationRequest.getId()).isPresent()) {
			int index = requests.indexOf(registrationRequest);
			return requests.set(index, registrationRequest);
		} else {
			if (StringUtils.isBlank(registrationRequest.getId()))
				registrationRequest.setId(UUID.randomUUID().toString());
			requests.add(registrationRequest);
			return registrationRequest;
		}
	}

	public void deleteById(@NonNull String id) {
		Optional<T> result = findById(id);
		if (result.isEmpty())
			throw new RegistrationRequestException(
					"Registration request with the Id does not exists in repository: "+id);
		requests.remove(result.get());
	}

	public void delete(@NonNull T registrationRequest) {
		deleteById(registrationRequest.getId());
	}
}
