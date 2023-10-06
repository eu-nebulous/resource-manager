package eu.nebulous.resource.discovery.monitor.service;

import eu.nebulous.resource.discovery.monitor.model.ArchivedDevice;
import eu.nebulous.resource.discovery.monitor.model.Device;
import eu.nebulous.resource.discovery.monitor.model.DeviceException;
import eu.nebulous.resource.discovery.monitor.model.DeviceStatus;
import eu.nebulous.resource.discovery.monitor.repository.ArchivedDeviceRepository;
import eu.nebulous.resource.discovery.monitor.repository.DeviceRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceManagementService {
	private final DeviceRepository deviceRepository;
	private final ArchivedDeviceRepository archivedDeviceRepository;
	private final DeviceConversionService deviceConversionService;

	// ------------------------------------------------------------------------

	public List<Device> getAll() {
		return Collections.unmodifiableList(deviceRepository.findAll());
	}

	public List<Device> getByOwner(@NonNull String owner) {
		return deviceRepository.findByOwner(owner);
	}

	public Optional<Device> getById(@NonNull String id) {
		return deviceRepository.findById(id);
	}

	public Optional<Device> getByIpAddress(@NonNull String ipAddress) {
		return deviceRepository.findByIpAddress(ipAddress);
	}

	public @NonNull Device save(@NonNull Device device) {
		DeviceStatus status = device.getStatus();
		checkDevice(device, true);

		if (status == null) {
			device.setStatus(DeviceStatus.NEW_DEVICE);
		}
		if (status != null && status != DeviceStatus.NEW_DEVICE) {
			throw new DeviceException("Cannot save a new device with status "+status);
		}
		if (StringUtils.isBlank(device.getId())) {
			device.setId( UUID.randomUUID().toString() );
		} else {
			throw new DeviceException(
					"New device already has an Id: " + device.getId());
		}
		if (getById(device.getId()).isPresent())
			throw new DeviceException(
					"A device with the same Id already exists in repository: "+device.getId());
		if (getByIpAddress(device.getIpAddress()).isPresent())
			throw new DeviceException(
					"A device with the same IP address already exists in repository: "+device.getIpAddress());
		device.setCreationDate(Instant.now());
		checkDevice(device, false);

		deviceRepository.save(device);
		return device;
	}

	public Device update(@NonNull Device device) {
		Optional<Device> result = getById(device.getId());
		if (result.isEmpty())
			throw new DeviceException(
					"Device with the Id does not exists in repository: "+device.getId());
		checkDevice(device, false);

		device.setLastUpdateDate(Instant.now());
		deviceRepository.save(device);

		return getById(device.getId()).orElseThrow(() ->
				new DeviceException("Device update failed for Device Id: "+device.getId()));
	}

	public void checkDevice(@NonNull Device device, boolean dryRun) {
		List<String> errors = new ArrayList<>();
		if (!dryRun && StringUtils.isBlank(device.getId())) errors.add("Null or blank Id");
		if (StringUtils.isBlank(device.getOs())) errors.add("Null or blank OS");
		if (StringUtils.isBlank(device.getOwner())) errors.add("Null or blank Owner");
		if (StringUtils.isBlank(device.getIpAddress())) errors.add("Null or blank IP address");
		if (!dryRun && StringUtils.isBlank(device.getNodeReference())) errors.add("Null or blank Node reference");
		if (!dryRun && device.getCreationDate()==null) errors.add("Null Creation date");
		if (!dryRun && device.getStatus()==null) errors.add("Null Status");
		if (StringUtils.isBlank(device.getUsername())) errors.add("Null or blank Username");
		if ((device.getPassword()==null || device.getPassword().length==0) &&
				(device.getPublicKey()==null || device.getPublicKey().length==0)) errors.add("Null or blank Password and Public Key");
		if (!errors.isEmpty()) {
			throw new DeviceException(
					String.format("Device spec has errors: %s\n%s",
							String.join(", ", errors), device));
		}
	}

	public void deleteById(@NonNull String id) {
		Optional<Device> result = getById(id);
		if (result.isEmpty())
			throw new DeviceException(
					"Device with the Id does not exists in repository: "+id);
		deviceRepository.delete(result.get());
		result.get().setLastUpdateDate(Instant.now());
	}

	public void delete(@NonNull Device device) {
		deviceRepository.deleteById(device.getId());
		device.setLastUpdateDate(Instant.now());
	}

	// ------------------------------------------------------------------------

	public List<ArchivedDevice> getArchivedAll() {
		return Collections.unmodifiableList(archivedDeviceRepository.findAll());
	}

	public List<ArchivedDevice> getArchivedByOwner(@NonNull String owner) {
		return archivedDeviceRepository.findByOwner(owner);
	}

	public Optional<ArchivedDevice> getArchivedById(@NonNull String id) {
		return archivedDeviceRepository.findById(id);
	}

	public List<ArchivedDevice> getArchivedByIpAddress(@NonNull String ipAddress) {
		return archivedDeviceRepository.findByIpAddress(ipAddress);
	}

	public void archiveDevice(String id) {
		archiveRequestBySystem(id);
	}

	public void archiveRequestBySystem(String id) {
		Optional<Device> result = getById(id);
		if (result.isEmpty())
			throw new DeviceException(
					"Device with the Id does not exists in repository: " + id);
		result.get().setArchiveDate(Instant.now());
		archivedDeviceRepository.save(deviceConversionService.toArchivedDevice(result.get()));
		deviceRepository.delete(result.get());
	}

	public void unarchiveDevice(String id) {
		Optional<ArchivedDevice> result = getArchivedById(id);
		if (result.isEmpty())
			throw new DeviceException(
					"Archived device with Id does not exists in repository: "+id);

		result.get().setArchiveDate(null);
		deviceRepository.save(deviceConversionService.toDevice(result.get()));
		archivedDeviceRepository.deleteById(result.get().getId());
	}
}