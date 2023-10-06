package eu.nebulous.resource.discovery.monitor.repository;

import eu.nebulous.resource.discovery.monitor.model.Device;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceRepository extends MongoRepository<Device, String> {
	List<Device> findByOwner(String owner);
	Optional<Device> findByIpAddress(String ipAddress);
}
