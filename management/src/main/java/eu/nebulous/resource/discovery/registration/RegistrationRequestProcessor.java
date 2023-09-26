package eu.nebulous.resource.discovery.registration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.nebulous.resource.discovery.ResourceDiscoveryProperties;
import eu.nebulous.resource.discovery.registration.model.RegistrationRequest;
import eu.nebulous.resource.discovery.registration.model.RegistrationRequestStatus;
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
	private final ResourceDiscoveryProperties processorProperties;
	private final RegistrationRequestService registrationRequestService;
	private final TaskScheduler taskScheduler;
	private final AtomicBoolean isRunning = new AtomicBoolean(false);
	private final ObjectMapper objectMapper = new ObjectMapper();

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
				processNewRequests(producer, objectMapper);
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

	private void processNewRequests(MessageProducer producer, ObjectMapper objectMapper) throws JsonProcessingException, JMSException {
		log.trace("processNewRequests: BEGIN: {}", producer);
		List<RegistrationRequest> newRequests = registrationRequestService.getAll().stream()
				.filter(r -> r.getStatus() == RegistrationRequestStatus.NEW_REQUEST).toList();

		log.debug("processNewRequests: Found {} new requests: {}",
				newRequests.size(), newRequests.stream().map(RegistrationRequest::getId).toList());

		for (RegistrationRequest registrationRequest : newRequests) {
			registrationRequest.setStatus(RegistrationRequestStatus.DATA_COLLECTION);
			Map<String, String> dataCollectionRequest = new LinkedHashMap<>(Map.of(
					"registrationRequestId", registrationRequest.getId(),
					"deviceId", registrationRequest.getDevice().getDeviceId(),
					"deviceOs", registrationRequest.getDevice().getDeviceOS(),
					"deviceName", registrationRequest.getDevice().getDeviceName(),
					"deviceIpAddress", registrationRequest.getDevice().getIpAddress(),
					"deviceUsername", registrationRequest.getDevice().getUsername(),
					"devicePassword", new String(registrationRequest.getDevice().getPassword()),
					"devicePublicKey", new String(registrationRequest.getDevice().getPublicKey())
			));
			dataCollectionRequest.put("timestamp", Long.toString(Instant.now().toEpochMilli()));
			dataCollectionRequest.put("priority", Double.toString(1.0));
			dataCollectionRequest.put("retry", Integer.toString(1));

			log.debug("processNewRequests: Requesting collection of device data for request with Id: {}", registrationRequest.getId());
			String jsonMessage = objectMapper.writer().writeValueAsString(dataCollectionRequest);
			producer.send(createMessage(jsonMessage));
			log.debug("processNewRequests: Data collection request sent for request with Id: {}", registrationRequest.getId());
		}

		log.trace("processNewRequests: END");
	}

	protected ActiveMQMessage createMessage(String message) throws MessageNotWriteableException {
		ActiveMQTextMessage textMessage = new ActiveMQTextMessage();
		textMessage.setText(message);
		return textMessage;
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
				Object obj = objectMapper.reader().readValue(payload);
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
		String requestId = response.getOrDefault("registrationRequestId", "").toString();
		String deviceIpAddress = response.getOrDefault("deviceIpAddress", "").toString();
		long timestamp = Long.parseLong(response.getOrDefault("timestamp", "-1").toString());

		RegistrationRequest registrationRequest = registrationRequestService.getById(requestId).orElse(null);
		if (registrationRequest!=null) {
			String ipAddress = registrationRequest.getDevice().getIpAddress();
			if (StringUtils.equals(ipAddress, deviceIpAddress)) {
				log.warn("processResponse: Device IP address do not match with that in request: id={}, ip-address={} != {}",
						requestId, ipAddress, deviceIpAddress);
				return;
			}
			if (timestamp < registrationRequest.getLastUpdateTimestamp()) {
				log.warn("processResponse: Response timestamp is older than requests last update timestamp: id={}, timestamp={} < {}",
						requestId, timestamp, registrationRequest.getLastUpdateTimestamp());
				return;
			}

			Object obj = response.get("deviceInfo");
			if (obj instanceof Map devInfo) {
				// Update request info
				registrationRequest.setLastUpdateTimestamp(timestamp);

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
				log.info("processResponse: New Device info for request: id={}, timestamp={}, device-info{}",
						requestId, timestamp, processedDevInfo);
				registrationRequest.getDevice().setDeviceInfo(processedDevInfo);

				log.debug("processResponse: Done processing response for request: id={}, timestamp={}", requestId, timestamp);
			} else {
				log.warn("processResponse: No device info found in message or it is of wrong type: id={}, obj={}", requestId, obj);
			}
		} else {
			log.warn("processResponse: Request not found: id={}", requestId);
		}
	}
}
