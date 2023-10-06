package eu.nebulous.resource.discovery.registration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.nebulous.resource.discovery.ResourceDiscoveryProperties;
import eu.nebulous.resource.discovery.monitor.model.Device;
import eu.nebulous.resource.discovery.monitor.service.DeviceManagementService;
import eu.nebulous.resource.discovery.registration.model.RegistrationRequest;
import eu.nebulous.resource.discovery.registration.model.RegistrationRequestStatus;
import eu.nebulous.resource.discovery.registration.service.RegistrationRequestService;
import jakarta.jms.*;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.ActiveMQConnection;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.command.ActiveMQMessage;
import org.apache.activemq.command.ActiveMQTextMessage;
import org.apache.activemq.command.ActiveMQTopic;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@EnableAsync
@EnableScheduling
@RequiredArgsConstructor
public class RegistrationRequestProcessor implements IRegistrationRequestProcessor, InitializingBean, MessageListener {
	private static final String REQUEST_TYPE_DATA_COLLECTION = "DIAGNOSTICS";	// EMS task type for collecting node info
	private static final String REQUEST_TYPE_ONBOARDING = "VM";					// EMS task type for installing EMS client

	private final static List<RegistrationRequestStatus> STATUSES_TO_ARCHIVE = List.of(
			RegistrationRequestStatus.PRE_AUTHORIZATION_REJECT,
			RegistrationRequestStatus.PRE_AUTHORIZATION_ERROR,
			RegistrationRequestStatus.DATA_COLLECTION_ERROR,
			RegistrationRequestStatus.AUTHORIZATION_REJECT,
			RegistrationRequestStatus.AUTHORIZATION_ERROR,
			RegistrationRequestStatus.ONBOARDING_ERROR,
			RegistrationRequestStatus.SUCCESS
	);

	private final ResourceDiscoveryProperties processorProperties;
	private final RegistrationRequestService registrationRequestService;
	private final DeviceManagementService deviceManagementService;
	private final TaskScheduler taskScheduler;
	private final ObjectMapper objectMapper;
	private final AtomicBoolean isRunning = new AtomicBoolean(false);

	@Override
	public void afterPropertiesSet() throws Exception {
		// Initialize request processing results listener
		taskScheduler.schedule(this::initializeResultsListener, Instant.now().plusSeconds(processorProperties.getSubscriptionStartupDelay()));

		// Initialize periodic request processing
		if (processorProperties.isEnablePeriodicProcessing()) {
			Instant firstRun;
			taskScheduler.scheduleAtFixedRate(this::processRequests,
					firstRun = Instant.now().plusSeconds(processorProperties.getProcessingStartupDelay()),
					Duration.ofSeconds(processorProperties.getProcessingPeriod()));
			log.info("RegistrationRequestProcessor: Started periodic registration request processing: period={}s, first-run-at={}",
					processorProperties.getProcessingPeriod(), firstRun.atZone(ZoneId.systemDefault()));
		} else {
			log.info("RegistrationRequestProcessor: Periodic registration request processing is disabled. You can still invoke it through GUI");
		}
	}

	@Async
	public Future<String> processRequests() {
		try {
			// Check and set if already running
			if (!isRunning.compareAndSet(false, true)) {
				log.warn("processRequests: Already running");
				return CompletableFuture.completedFuture("ALREADY RUNNING");
			}
			log.debug("processRequests: Processing registration requests");

			// Connect to Message broker
			ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory(
					processorProperties.getBrokerUsername(), processorProperties.getBrokerPassword(),
					processorProperties.getBrokerURL());
			ActiveMQConnection conn = (ActiveMQConnection) connectionFactory.createConnection();
			Session session = conn.createSession();
			MessageProducer producer = session.createProducer(
					new ActiveMQTopic(processorProperties.getDataCollectionRequestTopic()));

			// Process requests
			try {
				processNewRequests(producer);
				processOnboardingRequests(producer);
				if (processorProperties.isAutomaticArchivingEnabled())
					archiveRequests();
			} catch (Throwable t) {
				log.error("processRequests: ERROR processing requests: ", t);
			}

			// Close connection to Message broker
			conn.close();

			log.debug("processRequests: Processing completed");

			return CompletableFuture.completedFuture("DONE");
		} catch (Throwable e) {
			log.error("processRequests: ERROR connecting to Message broker: ", e);
			return CompletableFuture.completedFuture("ERROR: "+e.getMessage());
		} finally {
			// Clear running flag
			isRunning.set(false);
		}
	}

	private void processNewRequests(MessageProducer producer) throws JsonProcessingException, JMSException {
		log.trace("processNewRequests: BEGIN: {}", producer);
		List<RegistrationRequest> newRequests = registrationRequestService.getAll().stream()
				.filter(r -> r.getStatus() == RegistrationRequestStatus.NEW_REQUEST).toList();

		log.debug("processNewRequests: Found {} new requests: {}",
				newRequests.size(), newRequests.stream().map(RegistrationRequest::getId).toList());

		for (RegistrationRequest registrationRequest : newRequests) {
			try {
				log.debug("processNewRequests: Requesting collection of device data for request with Id: {}", registrationRequest.getId());
				Map<String, String> dataCollectionRequest = prepareRequestPayload(REQUEST_TYPE_DATA_COLLECTION, registrationRequest);
				String jsonMessage = objectMapper.writer().writeValueAsString(dataCollectionRequest);
				producer.send(createMessage(jsonMessage));
				registrationRequest.setStatus(RegistrationRequestStatus.DATA_COLLECTION_REQUESTED);
				registrationRequestService.update(registrationRequest);
				log.debug("processNewRequests: Data collection request sent for request with Id: {}", registrationRequest.getId());
			} catch (Exception e) {
				log.warn("processNewRequests: EXCEPTION while sending data collection request for request with Id: {}\n", registrationRequest.getId(), e);
				registrationRequest.setStatus(RegistrationRequestStatus.DATA_COLLECTION_ERROR);
				registrationRequestService.update(registrationRequest);
			}
		}

		log.trace("processNewRequests: END");
	}

	private void processOnboardingRequests(MessageProducer producer) throws JsonProcessingException, JMSException {
		log.trace("processOnboardingRequests: BEGIN: {}", producer);
		List<RegistrationRequest> onboardingRequests = registrationRequestService.getAll().stream()
				.filter(r -> r.getStatus() == RegistrationRequestStatus.PENDING_ONBOARDING).toList();

		log.debug("processOnboardingRequests: Found {} onboarding requests: {}",
				onboardingRequests.size(), onboardingRequests.stream().map(RegistrationRequest::getId).toList());

		for (RegistrationRequest registrationRequest : onboardingRequests) {
			try {
				log.debug("processOnboardingRequests: Checking device before requesting onboarding for request with Id: {}", registrationRequest.getId());
				deviceManagementService.checkDevice(
						objectMapper.convertValue(registrationRequest.getDevice(), Device.class),
						true);

				log.debug("processOnboardingRequests: Requesting device onboarding for request with Id: {}", registrationRequest.getId());
				Map<String, String> dataCollectionRequest = prepareRequestPayload(REQUEST_TYPE_ONBOARDING, registrationRequest);
				String jsonMessage = objectMapper.writer().writeValueAsString(dataCollectionRequest);
				producer.send(createMessage(jsonMessage));
				registrationRequest.setStatus(RegistrationRequestStatus.ONBOARDING_REQUESTED);
				registrationRequestService.update(registrationRequest);
				log.debug("processOnboardingRequests: Onboarding request sent for request with Id: {}", registrationRequest.getId());
			} catch (Exception e) {
				log.warn("processOnboardingRequests: EXCEPTION while sending onboarding request for request with Id: {}\n", registrationRequest.getId(), e);
				registrationRequest.setStatus(RegistrationRequestStatus.ONBOARDING_ERROR);
				registrationRequestService.update(registrationRequest);
			}
		}

		log.trace("processOnboardingRequests: END");
	}

	private static Map<String, String> prepareRequestPayload(@NonNull String requestType, RegistrationRequest registrationRequest) {
		try {
			Map<String, String> payload = new LinkedHashMap<>(Map.of(
					"requestId", registrationRequest.getId(),
					"requestType", requestType,
					"deviceId", registrationRequest.getDevice().getId(),
					"deviceOs", registrationRequest.getDevice().getOs(),
					"deviceName", registrationRequest.getDevice().getName(),
					"deviceIpAddress", registrationRequest.getDevice().getIpAddress(),
					"deviceUsername", registrationRequest.getDevice().getUsername(),
					"devicePassword", new String(registrationRequest.getDevice().getPassword()),
					"devicePublicKey", new String(registrationRequest.getDevice().getPublicKey())
			));
			payload.put("timestamp", Long.toString(Instant.now().toEpochMilli()));
			payload.put("priority", Double.toString(1.0));
			payload.put("retry", Integer.toString(1));
			return payload;
		} catch (Exception e) {
			log.error("prepareRequestPayload: EXCEPTION: request-type={}, registration-request={}\nException: ",
					requestType, registrationRequest, e);
			throw e;
		}
	}

	protected ActiveMQMessage createMessage(String message) throws MessageNotWriteableException {
		ActiveMQTextMessage textMessage = new ActiveMQTextMessage();
		textMessage.setText(message);
		return textMessage;
	}

	private void archiveRequests() {
		Instant archiveThreshold = Instant.now().minus(processorProperties.getArchivingThreshold(), ChronoUnit.MINUTES);
		log.trace("archiveRequests: BEGIN: archive-threshold: {}", archiveThreshold);
		List<RegistrationRequest> requestsForArchiving = registrationRequestService.getAll().stream()
				.filter(r -> STATUSES_TO_ARCHIVE.contains(r.getStatus()))
				.filter(r -> r.getLastUpdateDate().isBefore(archiveThreshold))
				.toList();

		log.debug("archiveRequests: Found {} requests for archiving: {}",
				requestsForArchiving.size(), requestsForArchiving.stream().map(RegistrationRequest::getId).toList());

		for (RegistrationRequest registrationRequest : requestsForArchiving) {
			log.debug("archiveRequests: Archiving request with Id: {}", registrationRequest.getId());
			registrationRequestService.archiveRequestBySystem(registrationRequest.getId());
			log.info("archiveRequests: Archived request with Id: {}", registrationRequest.getId());
		}

		log.trace("archiveRequests: END");
	}

	// ------------------------------------------------------------------------

	protected void initializeResultsListener() {
		try {
			ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory(
					processorProperties.getBrokerUsername(), processorProperties.getBrokerPassword(), processorProperties.getBrokerURL());
			ActiveMQConnection conn = (ActiveMQConnection) connectionFactory.createConnection();
			Session session = conn.createSession();
			MessageConsumer consumer = session.createConsumer(
					new ActiveMQTopic(processorProperties.getDataCollectionResponseTopic()));
			consumer.setMessageListener(this);
			conn.start();
		} catch (Exception e) {
			log.error("RegistrationRequestProcessor: ERROR while subscribing to Message broker for Device info announcements: ", e);
			taskScheduler.schedule(this::initializeResultsListener, Instant.now().plusSeconds(processorProperties.getSubscriptionRetry()));
		}
	}

	@Override
	public void onMessage(Message message) {
		try {
			log.debug("RegistrationRequestProcessor: Received a JMS message: {}", message);
			if (message instanceof ActiveMQTextMessage textMessage) {
				String payload = textMessage.getText();
				log.trace("RegistrationRequestProcessor: Message payload: {}", payload);
				TypeReference<Map<String,Object>> typeRef = new TypeReference<>() { };
				Object obj = objectMapper.readerFor(typeRef).readValue(payload);
				if (obj instanceof Map response) {
					processResponse(response);
				} else {
					log.debug("RegistrationRequestProcessor: Message payload is not recognized. Expected Map: type={}, object={}", obj.getClass().getName(), obj);
				}
			} else {
				log.debug("RegistrationRequestProcessor: Message type is not supported: {}", message);
			}
		} catch (Exception e) {
			log.warn("RegistrationRequestProcessor: ERROR while processing message: {}\nException: ", message, e);
		}
	}

	private void processResponse(@NonNull Map<String, Object> response) {
		String requestId = response.getOrDefault("requestId", "").toString().trim();
		String reference = response.getOrDefault("reference", "").toString().trim();
		String status = response.getOrDefault("status", "").toString().trim();
		String deviceIpAddress = response.getOrDefault("deviceIpAddress", "").toString().trim();
		long timestamp = Long.parseLong(response.getOrDefault("timestamp", "-1").toString().trim());

		RegistrationRequest registrationRequest = registrationRequestService.getById(requestId).orElse(null);
		if (registrationRequest!=null) {
			RegistrationRequestStatus currStatus = registrationRequest.getStatus();
			RegistrationRequestStatus newStatus = switch (currStatus) {
				case PRE_AUTHORIZATION_REQUESTED -> RegistrationRequestStatus.PRE_AUTHORIZATION_ERROR;
				case DATA_COLLECTION_REQUESTED -> RegistrationRequestStatus.DATA_COLLECTION_ERROR;
				case AUTHORIZATION_REQUESTED -> RegistrationRequestStatus.AUTHORIZATION_ERROR;
				case ONBOARDING_REQUESTED -> RegistrationRequestStatus.ONBOARDING_ERROR;
				default -> currStatus;
			};
			registrationRequest.setStatus(newStatus);

			if (currStatus==RegistrationRequestStatus.SUCCESS) {
				log.error("ERROR: received response for a request with status SUCCESS. Will ignore response: request-id={}", requestId);
				// Discarding changes
				return;
			}

			String ipAddress = registrationRequest.getDevice().getIpAddress();
			boolean isError = false;
			if (StringUtils.equals(ipAddress, deviceIpAddress)) {
				String mesg = String.format("Device IP address do not match with that in request: id=%s, ip-address=%s != %s", requestId, ipAddress, deviceIpAddress);
				log.warn("processResponse: {}", mesg);
				registrationRequest.getMessages().add(mesg);
				isError = true;
			}
			if (timestamp < registrationRequest.getRequestDate().toEpochMilli()) {
				String mesg = String.format("Response timestamp is older than Request's date: id=%s, timestamp=%d < %s", requestId, timestamp, registrationRequest.getRequestDate());
				log.warn("processResponse: {}", mesg);
				registrationRequest.getMessages().add(mesg);
				isError = true;
			}
			if (! "SUCCESS".equals(status)) {
				String mesg = String.format("Request status is not SUCCESS: id=%s, timestamp=%d, status=%s", requestId, timestamp, status);
				log.warn("processResponse: {}", mesg);
				registrationRequest.getMessages().add(mesg);
				isError = true;
			}
			if (isError) {
				registrationRequestService.update(registrationRequest);
				return;
			}

			Object obj = response.get("nodeInfo");
			if (obj instanceof Map devInfo) {
				// Update request info
				registrationRequest.setLastUpdateDate(Instant.ofEpochMilli(timestamp));

				// Process device info in response
				log.debug("processResponse: Device info in response: id={}, device-info{}", requestId, devInfo);
				boolean allowAllKeys = processorProperties.getAllowedDeviceInfoKeys().contains("*");
				final Map<String,String> processedDevInfo = new LinkedHashMap<>();
				devInfo.forEach((key, value) -> {
					if (key!=null && value!=null) {
						String k = key.toString().trim();
						String v = value.toString().trim();
						if (StringUtils.isNotBlank(k) && StringUtils.isNotBlank(v)) {
							if (allowAllKeys || processorProperties.getAllowedDeviceInfoKeys().contains(k.toUpperCase())) {
								processedDevInfo.put(k, v);
							} else {
								log.debug("processResponse: Not allowed device info key for request: id={}, key={}", requestId, k);
							}
						}
					}
                });
				log.info("processResponse: New Device info for request: id={}, timestamp={}, device-info={}",
						requestId, timestamp, processedDevInfo);
				registrationRequest.getDevice().setDeviceInfo(processedDevInfo);

				// Set node reference (meaningful only in case of Onboarding)
				if (StringUtils.isNotBlank(reference) && currStatus==RegistrationRequestStatus.ONBOARDING_REQUESTED) {
					registrationRequest.setNodeReference(reference.trim());
				}

				// Set new status
				if (currStatus==RegistrationRequestStatus.DATA_COLLECTION_REQUESTED)
					registrationRequest.setStatus(RegistrationRequestStatus.PENDING_AUTHORIZATION);
				if (currStatus==RegistrationRequestStatus.ONBOARDING_REQUESTED)
					registrationRequest.setStatus(RegistrationRequestStatus.SUCCESS);

				log.debug("processResponse: Done processing response for request: id={}, timestamp={}", requestId, timestamp);
			} else {
				log.warn("processResponse: No device info found in message or it is of wrong type: id={}, obj={}", requestId, obj);
			}

			// If request status is SUCCESS then copy Device in monitoring subsystem
			if (registrationRequest.getStatus() == RegistrationRequestStatus.SUCCESS) {
				try {
					copyDeviceToMonitoring(registrationRequest);
				} catch (Exception e) {
					log.warn("processResponse: EXCEPTION: while copying device to monitoring subsystem: request={}\n", registrationRequest, e);
					registrationRequest.setStatus(RegistrationRequestStatus.ONBOARDING_ERROR);
					registrationRequest.getMessages().add("Exception while copying device to monitoring subsystem: " + e.getMessage());
				}
			}

			// Store changes
			registrationRequestService.update(registrationRequest, false);
		} else {
			log.warn("processResponse: Request not found: id={}", requestId);
		}
	}

	private void copyDeviceToMonitoring(RegistrationRequest registrationRequest) {
		Device device = objectMapper.convertValue(registrationRequest.getDevice(), Device.class);
		device.setId(null);
		device.setStatus(null);
		device.setRequest(registrationRequest);
		device.setRequestId(registrationRequest.getId());
		device.getMessages().clear();
		device.setNodeReference(registrationRequest.getNodeReference());
		deviceManagementService.save(device);
	}
}
