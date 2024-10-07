package eu.nebulous.resource.discovery.monitor.service;

import eu.nebulous.resource.discovery.ResourceDiscoveryProperties;
import eu.nebulous.resource.discovery.monitor.model.ArchivedDevice;
import eu.nebulous.resource.discovery.monitor.model.Device;
import eu.nebulous.resource.discovery.monitor.model.DeviceException;
import eu.nebulous.resource.discovery.monitor.model.DeviceStatus;
import eu.nebulous.resource.discovery.monitor.repository.ArchivedDeviceRepository;
import eu.nebulous.resource.discovery.monitor.repository.DeviceRepository;
import eu.nebulous.resource.discovery.registration.model.RegistrationRequestException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceManagementService {
	private final ResourceDiscoveryProperties properties;
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

	public void setCompromised(@NonNull String id){
		Optional<Device> device = getById(id);
        device.ifPresent(value -> value.setStatus(DeviceStatus.COMPROMISED));
	}
	
	public Optional<ArchivedDevice> isCompromised(@NonNull String id){
		return archivedDeviceRepository.findById(id).filter(device -> device.getStatus()!= null && device.getStatus() == DeviceStatus.COMPROMISED );
	}
	
	public boolean isIpAddressInUse(@NonNull String ipAddress) {
		return deviceRepository.findByIpAddress(ipAddress).isPresent();
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

	private boolean canAccess(@NonNull Device device, Authentication authentication) {
		return canAccess(device, authentication, false);
	}

	private boolean canAccess(@NonNull Device device, Authentication authentication, boolean sameUserOnly) {
		String owner = device.getOwner();
		if (owner == null && authentication.getName() == null) return true;
		return owner != null && (
				owner.equals(authentication.getName()) ||
						!sameUserOnly && authentication.getAuthorities().stream()
								.map(GrantedAuthority::getAuthority).toList().contains("ROLE_ADMIN")
		);
	}

	// ------------------------------------------------------------------------

	public List<ArchivedDevice> getArchivedAll() {
		return Collections.unmodifiableList(archivedDeviceRepository.findAll());
	}

	public List<ArchivedDevice> getArchivedByOwner(@NonNull String owner) {
		return archivedDeviceRepository.findByOwner(owner);
	}
	public List<ArchivedDevice> getArchivedByOwner(Authentication authentication) {
		return getArchivedAll().stream()
				.filter(dev -> canAccess(dev, authentication, true))
				.toList();
	}

	public Optional<ArchivedDevice> getArchivedById(@NonNull String id, Authentication authentication) {
		Optional<ArchivedDevice> result = getArchivedById(id);
		if (result.isEmpty())
			throw new DeviceException(
					"Device with the Id does not exists in repository: " + id);
		return canAccess(result.get(), authentication)
				? result : Optional.empty();
	}

	public Optional<ArchivedDevice> getArchivedById(@NonNull String id) {
		return archivedDeviceRepository.findById(id);
	}

	public List<ArchivedDevice> getArchivedByIpAddress(@NonNull String ipAddress, Authentication authentication) {
		return getArchivedByIpAddress(ipAddress).stream()
				.filter(dev -> canAccess(dev, authentication, true))
				.toList();
	}

	public List<ArchivedDevice> getArchivedByIpAddress(@NonNull String ipAddress) {
		return archivedDeviceRepository.findByIpAddress(ipAddress);
	}

	public void archiveDevice(String id) {
		archiveDeviceBySystem(id);
	}

	public void archiveDeviceBySystem(String id) {
		Optional<Device> result = getById(id);
		if (result.isEmpty())
			throw new DeviceException(
					"Device with the Id does not exists in repository: " + id);
		result.get().setArchiveDate(Instant.now());
		archivedDeviceRepository.save(deviceConversionService.toArchivedDevice(result.get()));
		deviceRepository.delete(result.get());
		//XXX:TODO: Send notification to SAL to deregister Device
	}

	public void unarchiveDevice(String id, Map<String,String> credentials) {
		Optional<ArchivedDevice> result = getArchivedById(id);
		if (result.isEmpty())
			throw new DeviceException(
					"Archived device with Id does not exists in repository: "+id);
		checkCredentials(result.get().getId(), credentials);

		result.get().setArchiveDate(null);
		Device restoredDevice = deviceConversionService.toDevice(result.get());
		restoredDevice.setUsername(credentials.get("username"));
		restoredDevice.setPassword(credentials.get("password").toCharArray());
		restoredDevice.setPublicKey(credentials.get("publicKey").toCharArray());
		deviceRepository.save(restoredDevice);
		archivedDeviceRepository.deleteById(result.get().getId());
		//XXX:TODO: Send notification to SAL to re-register Device
	}

	private void checkCredentials(String id, Map<String, String> credentials) {
		if (credentials==null || credentials.isEmpty())
			throw new RegistrationRequestException(
					"No credentials provided for un-archiving device with Id: "+id);
		if (StringUtils.isBlank(credentials.getOrDefault("username", "")))
			throw new RegistrationRequestException(
					"No username provided for un-archiving device with Id: "+id);
		if (StringUtils.isBlank(credentials.getOrDefault("password", "")) &&
				StringUtils.isBlank(credentials.getOrDefault("publicKey", "")))
			throw new RegistrationRequestException(
					"No password or SSH key provided for un-archiving device with Id: "+id);
	}
}
