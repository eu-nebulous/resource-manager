package eu.nebulous.resource.discovery.monitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.nebulous.resource.discovery.REQUEST_TYPE;
import eu.nebulous.resource.discovery.ResourceDiscoveryProperties;
import eu.nebulous.resource.discovery.monitor.model.ArchivedDevice;
import eu.nebulous.resource.discovery.monitor.model.Device;
import eu.nebulous.resource.discovery.monitor.model.DeviceException;
import eu.nebulous.resource.discovery.monitor.model.DeviceStatus;
import eu.nebulous.resource.discovery.monitor.repository.ArchivedDeviceRepository;
import eu.nebulous.resource.discovery.monitor.repository.DeviceRepository;
import jakarta.jms.JMSException;
import jakarta.jms.MessageNotWriteableException;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.ActiveMQConnection;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.command.ActiveMQMessage;
import org.apache.activemq.command.ActiveMQTextMessage;
import org.apache.activemq.command.ActiveMQTopic;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.util.Pair;
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
	private final ObjectMapper objectMapper;

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

	// ------------------------------------------------------------------------

	public void reinstallRequest(String id) {
		log.trace("reinstallRequest: BEGIN: device-id {}", id);
		Optional<Device> result = getById(id);
		if (result.isEmpty())
			throw new DeviceException(
					"Device with the Id does not exists in repository: " + id);
		Device device = result.get();

		try {
			// Prepare request
			log.debug("reinstallRequest: Requesting device re-onboarding with Id: {}", device.getId());
			Map<String, String> onboardingRequest = prepareRequestPayload(REQUEST_TYPE.REINSTALL, device);
			String jsonMessage = objectMapper.writer().writeValueAsString(onboardingRequest);

			// Connect to Message broker
			Pair<ActiveMQConnection, MessageProducer> connAndProducer = connectToBroker();

			// Send request
			connAndProducer.getSecond().send(createMessage(jsonMessage));
			device.setStatus(DeviceStatus.ONBOARDING);

			// Close connection to Message broker
			connAndProducer.getFirst().close();

			log.debug("reinstallRequest: Save updated device: id={}, device={}", device.getId(), device);
			update(device);
			log.debug("reinstallRequest: Onboarding request sent for device with Id: {}", device.getId());
		} catch (Exception e) {
			log.warn("reinstallRequest: EXCEPTION while sending onboarding request for device with Id: {}\n", device.getId(), e);
			device.setStatus(DeviceStatus.ONBOARD_ERROR);
			device.getMessages().add("EXCEPTION "+e.getMessage());
			update(device);
		}

		log.trace("reinstallRequest: END");
	}

	public void uninstallRequest(String id) {
		log.trace("uninstallRequest: BEGIN: device-id {}", id);
		Optional<Device> result = getById(id);
		if (result.isEmpty())
			throw new DeviceException(
					"Device with the Id does not exists in repository: " + id);
		Device device = result.get();

		try {
			// Prepare request
			log.debug("uninstallRequest: Requesting device off-onboarding with Id: {}", device.getId());
			Map<String, String> offboardingRequest = prepareRequestPayload(REQUEST_TYPE.UNINSTALL, device);
			String jsonMessage = objectMapper.writer().writeValueAsString(offboardingRequest);

			// Connect to Message broker
			Pair<ActiveMQConnection, MessageProducer> connAndProducer = connectToBroker();

			// Send request
			connAndProducer.getSecond().send(createMessage(jsonMessage));
			device.setStatus(DeviceStatus.OFFBOARDING);

			// Close connection to Message broker
			connAndProducer.getFirst().close();

			log.debug("uninstallRequest: Save updated device: id={}, device={}", device.getId(), device);
			update(device);
			log.debug("uninstallRequest: Off-boarding request sent for device with Id: {}", device.getId());
		} catch (Exception e) {
			log.warn("uninstallRequest: EXCEPTION while sending off-boarding request for device with Id: {}\n", device.getId(), e);
			device.setStatus(DeviceStatus.OFFBOARD_ERROR);
			device.getMessages().add("EXCEPTION "+e.getMessage());
			update(device);
		}

		log.trace("uninstallRequest: END");
	}

	public void requestInfoUpdate() {
		try {
			// Prepare request
			log.debug("requestInfoUpdate: Requesting device info and metrics update");
			Map<String, String> updateRequest = prepareRequestPayload(REQUEST_TYPE.INFO, null);
			String jsonMessage = objectMapper.writer().writeValueAsString(updateRequest);

			// Connect to Message broker
			Pair<ActiveMQConnection, MessageProducer> connAndProducer = connectToBroker();

			// Send request
			connAndProducer.getSecond().send(createMessage(jsonMessage));

			// Close connection to Message broker
			connAndProducer.getFirst().close();

			log.debug("requestInfoUpdate: Update request sent");
		} catch (Exception e) {
			log.warn("requestInfoUpdate: EXCEPTION while sending update request:\n", e);
		}
		log.trace("requestInfoUpdate: END");
	}

	// ------------------------------------------------------------------------

	private static Map<String, String> prepareRequestPayload(@NonNull REQUEST_TYPE requestType, Device device) {
		try {
			Map<String, String> payload;
			if (device==null) {
				payload = new LinkedHashMap<>(Map.of(
						"requestType", requestType.name()
				));
			} else {
				payload = new LinkedHashMap<>(Map.of(
						"requestId", device.getRequestId(),
						"requestType", requestType.name(),
						"deviceId", device.getId(),
						"deviceOs", device.getOs(),
						"deviceName", device.getName(),
						"deviceIpAddress", device.getIpAddress(),
						"deviceUsername", device.getUsername(),
						"devicePassword", new String(device.getPassword()),
						"devicePublicKey", new String(device.getPublicKey())
				));
			}
			payload.put("timestamp", Long.toString(Instant.now().toEpochMilli()));
			payload.put("priority", Double.toString(1.0));
			payload.put("retry", Integer.toString(1));
			return payload;
		} catch (Exception e) {
			log.error("prepareRequestPayload: EXCEPTION: request-type={}, device={}\nException: ",
					requestType, device, e);
			throw e;
		}
	}

	protected Pair<ActiveMQConnection, MessageProducer> connectToBroker() throws JMSException {
		ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory(
				properties.getBrokerUsername(), properties.getBrokerPassword(),
				properties.getBrokerURL());
		ActiveMQConnection conn = (ActiveMQConnection) connectionFactory.createConnection();
		Session session = conn.createSession();
		MessageProducer producer = session.createProducer(
				new ActiveMQTopic(properties.getDataCollectionRequestTopic()));
		return Pair.of(conn, producer);
	}

	protected ActiveMQMessage createMessage(String message) throws MessageNotWriteableException {
		ActiveMQTextMessage textMessage = new ActiveMQTextMessage();
		textMessage.setText(message);
		return textMessage;
	}
}
