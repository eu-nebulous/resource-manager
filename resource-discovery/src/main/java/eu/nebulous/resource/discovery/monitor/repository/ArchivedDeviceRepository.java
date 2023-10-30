package eu.nebulous.resource.discovery.monitor.repository;

import eu.nebulous.resource.discovery.monitor.model.ArchivedDevice;
import eu.nebulous.resource.discovery.monitor.model.Device;
import eu.nebulous.resource.discovery.registration.model.RegistrationRequest;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ArchivedDeviceRepository extends MongoRepository<ArchivedDevice, String> {
	List<ArchivedDevice> findByOwner(String owner);
	List<ArchivedDevice> findByIpAddress(String ipAddress);
}
