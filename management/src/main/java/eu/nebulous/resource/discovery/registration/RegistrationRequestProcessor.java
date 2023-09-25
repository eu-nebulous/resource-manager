package eu.nebulous.resource.discovery.registration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.nebulous.resource.discovery.registration.model.RegistrationRequest;
import eu.nebulous.resource.discovery.registration.model.RegistrationRequestStatus;
import jakarta.jms.JMSException;
import jakarta.jms.MessageNotWriteableException;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.ActiveMQConnection;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.command.ActiveMQMessage;
import org.apache.activemq.command.ActiveMQTextMessage;
import org.apache.activemq.command.ActiveMQTopic;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
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
public class RegistrationRequestProcessor implements InitializingBean {
	private final RegistrationRequestService registrationRequestService;
	private final AtomicBoolean isRunning = new AtomicBoolean(false);
	private final TaskScheduler taskScheduler;

	//XXX: TODO: Make properties....
	private final boolean enablePeriodicRunning = true;
	private final long startupDelay = 10;
	private final long runningPeriod = 60;

	private final String emsDataCollectionRequestTopic = "abcde";
	private final String brokerUsername = "aaa";
	private final String brokerPassword = "111";
	private final String brokerURL = "tcp://localhost:61616?daemon=true&trace=false&useInactivityMonitor=false&connectionTimeout=0&keepAlive=true";

	@Override
	public void afterPropertiesSet() throws Exception {
		if (enablePeriodicRunning) {
			Instant firstRun;
			taskScheduler.scheduleAtFixedRate(this::processRequests,
					firstRun = Instant.now().plusSeconds(startupDelay), Duration.ofSeconds(runningPeriod));
			log.info("RegistrationRequestProcessor: Started periodic registration request processing: period={}s, first-run-at={}",
					runningPeriod, firstRun.atZone(ZoneId.systemDefault()));
		} else {
			log.info("RegistrationRequestProcessor: Periodic registration request processing is disabled. You can still invoke it through GUI");
		}
	}

	@Async
	@SneakyThrows
	public Future<String> processRequests() {
		// Check and set if already running
		if (! isRunning.compareAndSet(false, true)) {
			log.warn("processRequests: Already running");
			return CompletableFuture.completedFuture("ALREADY RUNNING");
		}
		log.debug("processRequests: Processing registration requests");

		// Connect to EMS AMQ broker
		ActiveMQConnectionFactory connectionFactory1 = new ActiveMQConnectionFactory(brokerUsername, brokerPassword, brokerURL);
		ActiveMQConnection conn = (ActiveMQConnection) connectionFactory1.createConnection();
		Session session = conn.createSession();
		MessageProducer producer = session.createProducer(new ActiveMQTopic(emsDataCollectionRequestTopic));

		// Process requests
		ObjectMapper objectMapper = new ObjectMapper();
		processNewRequests(producer, objectMapper);

		// Clone connection to EMS AMQ broker
		conn.close();

		// Clear running flag
		log.debug("processRequests: Processing completed");
		isRunning.set(false);

		return CompletableFuture.completedFuture("DONE");
	}

	private void processNewRequests(MessageProducer producer, ObjectMapper objectMapper) throws JsonProcessingException, JMSException {
		log.trace("processNewRequests: BEGIN: {}", producer);
		List<RegistrationRequest> newRequests = registrationRequestService.getAll().stream()
				.filter(r -> r.getStatus() == RegistrationRequestStatus.NEW_REQUEST).toList();

		log.debug("processNewRequests: Found {} new requests: {}",
				newRequests.size(), newRequests.stream().map(RegistrationRequest::getId).toList());

		for (RegistrationRequest registrationRequest : newRequests) {
			registrationRequest.setStatus(RegistrationRequestStatus.DATA_COLLECTION);
			Map<String, String> emsNotification = Map.of(
					"registrationRequestId", registrationRequest.getId(),
					"deviceIpAddress", registrationRequest.getDevice().getIpAddress(),
					"deviceUsername", registrationRequest.getDevice().getUsername(),
					"devicePassword", new String(registrationRequest.getDevice().getPassword()),
					"devicePublicKey", new String(registrationRequest.getDevice().getPublicKey()),
					"timestamp", Long.toString(Instant.now().toEpochMilli()),
					"priority", Double.toString(1.0),
					"retry", Integer.toString(1)
			);

			log.debug("processNewRequests: Requesting EMS to collect device data for request with Id: {}", registrationRequest.getId());
			String jsonMessage = objectMapper.writer().writeValueAsString(emsNotification);
			producer.send(createMessage(jsonMessage));
			log.debug("processNewRequests: Request to EMS sent for request with Id: {}", registrationRequest.getId());
		}

		log.trace("processNewRequests: END");
	}

	protected ActiveMQMessage createMessage(String message) throws MessageNotWriteableException {
		ActiveMQTextMessage textMessage = new ActiveMQTextMessage();
		textMessage.setText(message);
		return textMessage;
	}
}
