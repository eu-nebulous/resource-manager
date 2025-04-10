package eu.nebulous.resource.discovery.registration;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.nebulous.resource.discovery.ResourceDiscoveryProperties;
import eu.nebulous.resource.discovery.common.BrokerUtil;
import eu.nebulous.resource.discovery.common.REQUEST_TYPE;
import eu.nebulous.resource.discovery.monitor.model.Device;
import eu.nebulous.resource.discovery.monitor.service.DeviceManagementService;
import eu.nebulous.resource.discovery.registration.model.RegistrationRequest;
import eu.nebulous.resource.discovery.registration.model.RegistrationRequestStatus;
import eu.nebulous.resource.discovery.registration.service.RegistrationRequestService;
import eu.nebulous.resource.discovery.registration.service.SALRegistrationService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@EnableAsync
@EnableScheduling
@RequiredArgsConstructor
public class RegistrationRequestProcessor implements IRegistrationRequestProcessor, InitializingBean, BrokerUtil.Listener {
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
	private final Optional<SALRegistrationService> salRegistrationService;
	private final TaskScheduler taskScheduler;
	private final ObjectMapper objectMapper;
	private final BrokerUtil brokerUtil;
	private final AtomicBoolean isRunning = new AtomicBoolean(false);

	@Override
	public void afterPropertiesSet() {
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

			// Process requests
			try {
				processNewRequests();
				processOnboardingRequests();
				if (processorProperties.isAutomaticArchivingEnabled())
					archiveRequests();
			} catch (Throwable t) {
				log.error("processRequests: ERROR processing requests: ", t);
			}

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

	private void processNewRequests() {
		log.trace("processNewRequests: BEGIN");
		List<RegistrationRequest> newRequests = registrationRequestService.getAll().stream()
				.filter(r -> r.getStatus() == RegistrationRequestStatus.NEW_REQUEST).toList();

		log.debug("processNewRequests: Found {} new requests: {}",
				newRequests.size(), newRequests.stream().map(RegistrationRequest::getId).toList());

		for (RegistrationRequest registrationRequest : newRequests) {
			try {
				log.debug("processNewRequests: Requesting collection of device data for request with Id: {}", registrationRequest.getId());
				Map<String, String> dataCollectionRequest = prepareRequestPayload(REQUEST_TYPE.DIAGNOSTICS, registrationRequest);
				brokerUtil.sendMessage(processorProperties.getDataCollectionRequestTopic(), dataCollectionRequest);
				registrationRequest.setStatus(RegistrationRequestStatus.DATA_COLLECTION_REQUESTED);

				log.debug("processNewRequests: Save updated request: id={}, request={}", registrationRequest.getId(), registrationRequest);
				registrationRequestService.update(registrationRequest, null);
				log.debug("processNewRequests: Data collection request sent for request with Id: {}", registrationRequest.getId());
			} catch (Exception e) {
				log.warn("processNewRequests: EXCEPTION while sending data collection request for request with Id: {}\n", registrationRequest.getId(), e);
				registrationRequest.setStatus(RegistrationRequestStatus.DATA_COLLECTION_ERROR);
				registrationRequestService.update(registrationRequest, null);
			}
		}

		log.trace("processNewRequests: END");
	}

	private void processOnboardingRequests() {
		log.trace("processOnboardingRequests: BEGIN");
		List<RegistrationRequest> onboardingRequests = registrationRequestService.getAll().stream()
				.filter(r -> r.getStatus() == RegistrationRequestStatus.PENDING_ONBOARDING).toList();

		log.debug("processOnboardingRequests: Found {} onboarding requests: {}",
				onboardingRequests.size(), onboardingRequests.stream().map(RegistrationRequest::getId).toList());

		for (RegistrationRequest registrationRequest : onboardingRequests) {
			try {
				log.debug("processOnboardingRequests: Checking device data before requesting onboarding, for request with Id: {}", registrationRequest.getId());
				Device deviceForMonitoring = objectMapper.convertValue(registrationRequest.getDevice(), Device.class);
				deviceForMonitoring.setPassword(registrationRequest.getDevice().getPassword());		// ignored by 'objectMapper', so we've to copy them
				deviceForMonitoring.setPublicKey(registrationRequest.getDevice().getPublicKey());	// ignored by 'objectMapper', so we've to copy them
				deviceManagementService.checkDevice(deviceForMonitoring, true);

				log.debug("processOnboardingRequests: Requesting device onboarding for request with Id: {}", registrationRequest.getId());
				Map<String, String> deviceOnboardingRequest = prepareRequestPayload(REQUEST_TYPE.INSTALL, registrationRequest);
				brokerUtil.sendMessage(processorProperties.getDataCollectionRequestTopic(), deviceOnboardingRequest);
				registrationRequest.setStatus(RegistrationRequestStatus.ONBOARDING_REQUESTED);

				log.debug("processOnboardingRequests: Save updated request: id={}, request={}", registrationRequest.getId(), registrationRequest);
				registrationRequestService.update(registrationRequest, false, null);
				log.debug("processOnboardingRequests: Onboarding request sent for request with Id: {}", registrationRequest.getId());
			} catch (Exception e) {
				log.warn("processOnboardingRequests: EXCEPTION while sending onboarding request for request with Id: {}\n", registrationRequest.getId(), e);
				registrationRequest.setStatus(RegistrationRequestStatus.ONBOARDING_ERROR);
				registrationRequest.getMessages().add("EXCEPTION "+e.getMessage());
				registrationRequestService.update(registrationRequest, false, null);
			}
		}

		log.trace("processOnboardingRequests: END");
	}

	private static Map<String, String> prepareRequestPayload(@NonNull REQUEST_TYPE requestType, RegistrationRequest registrationRequest) {
		try {
			Map<String, String> payload = new LinkedHashMap<>();
			payload.put("requestId", registrationRequest.getId());
			payload.put("requestType", requestType.name());
			payload.put("deviceId", registrationRequest.getDevice().getId());
			payload.put("deviceRef", registrationRequest.getDevice().getRef());
			payload.put("deviceOs", registrationRequest.getDevice().getOs());
			payload.put("deviceName", registrationRequest.getDevice().getName());
			payload.put("deviceIpAddress", registrationRequest.getDevice().getIpAddress());
			payload.put("devicePort", Integer.toString( registrationRequest.getDevice().getPort() ));
			payload.put("deviceUsername", registrationRequest.getDevice().getUsername());
			payload.put("devicePassword", new String(registrationRequest.getDevice().getPassword()));
			payload.put("devicePublicKey", new String(registrationRequest.getDevice().getPublicKey()));
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
			brokerUtil.subscribe(processorProperties.getDataCollectionResponseTopic(), this);
		} catch (Exception e) {
			log.error("RegistrationRequestProcessor: ERROR while subscribing to Message broker for Device info announcements: ", e);
			taskScheduler.schedule(this::initializeResultsListener, Instant.now().plusSeconds(processorProperties.getSubscriptionRetryDelay()));
		}
	}

	public void onMessage(Map message) {
		processResponse(message);
	}

	private void processResponse(@NonNull Map<String, Object> response) {
		log.debug("RegistrationRequestProcessor: processResponse: BEGIN: {}", response);
		String requestType = response.getOrDefault("requestType", "").toString().trim();
		String requestId = response.getOrDefault("requestId", "").toString().trim();
		String reference = response.getOrDefault("reference", "").toString().trim();
		String responseStatus = response.getOrDefault("status", "").toString().trim();
		String deviceIpAddress = response.getOrDefault("deviceIpAddress", "").toString().trim();
		long timestamp = Long.parseLong(response.getOrDefault("timestamp", "-1").toString().trim());

		RegistrationRequest registrationRequest = registrationRequestService.getById(requestId).orElse(null);
		log.debug("RegistrationRequestProcessor: processResponse: request: {}", registrationRequest);
		if (registrationRequest!=null) {
			log.trace("RegistrationRequestProcessor: processResponse: request != null");
			RegistrationRequestStatus currStatus = registrationRequest.getStatus();
			RegistrationRequestStatus newStatus = switch (currStatus) {
				case PRE_AUTHORIZATION_REQUESTED -> RegistrationRequestStatus.PRE_AUTHORIZATION_ERROR;
				case DATA_COLLECTION_REQUESTED -> RegistrationRequestStatus.DATA_COLLECTION_ERROR;
				case AUTHORIZATION_REQUESTED -> RegistrationRequestStatus.AUTHORIZATION_ERROR;
				case ONBOARDING_REQUESTED -> RegistrationRequestStatus.ONBOARDING_ERROR;
				default -> currStatus;
			};
			log.debug("RegistrationRequestProcessor: processResponse: Temporary status change: {} --> {}", currStatus, newStatus);
			registrationRequest.setStatus(newStatus);
			log.trace("RegistrationRequestProcessor: processResponse: Temp. status: {}", newStatus);

			if (currStatus==RegistrationRequestStatus.SUCCESS) {
				log.warn("RegistrationRequestProcessor: ERROR: received response for a request with status SUCCESS. Will ignore response: request-id={}", requestId);
				// Discarding changes
				return;
			}

			String ipAddress = registrationRequest.getDevice().getIpAddress();
			boolean isError = false;
			if (StringUtils.isNotBlank(deviceIpAddress) && ! StringUtils.equals(ipAddress, deviceIpAddress)) {
				String mesg = String.format("Device IP address in RESPONSE does not match with that in the request: id=%s, ip-address-response=%s != ip-address-in-request=%s", requestId, deviceIpAddress, ipAddress);
				log.warn("processResponse: {}", mesg);
				registrationRequest.getMessages().add(mesg);
				isError = true;
			}
			log.trace("RegistrationRequestProcessor: processResponse: After checking device address: is-error={}", isError);
			if (timestamp < registrationRequest.getRequestDate().toEpochMilli()) {
				String mesg = String.format("Response timestamp is older than Request's date: id=%s, timestamp=%d < %s", requestId, timestamp, registrationRequest.getRequestDate());
				log.warn("processResponse: {}", mesg);
				registrationRequest.getMessages().add(mesg);
				isError = true;
			}
			log.trace("RegistrationRequestProcessor: processResponse: After checking timestamps: is-error={}", isError);
			if (! "SUCCESS".equals(responseStatus)) {
				String mesg = String.format("RESPONSE status is not SUCCESS: id=%s, timestamp=%d, status=%s", requestId, timestamp, responseStatus);
				log.warn("processResponse: {}", mesg);
				registrationRequest.getMessages().add(mesg);
				isError = true;
			}
			log.trace("RegistrationRequestProcessor: processResponse: After checking response status: is-error={}", isError);
			if (isError) {
				log.trace("RegistrationRequestProcessor: processResponse: Checks failed: {}", registrationRequest.getMessages());
				if (log.isDebugEnabled())
					log.debug("processResponse: Save request with errors: id={}, errors={}, request={}", requestId, registrationRequest.getMessages(), registrationRequest);
				log.warn("processResponse: Save request with errors: id={}, errors={}", requestId, registrationRequest.getMessages());
				registrationRequestService.update(registrationRequest, false, true, null);
				return;
			}
			log.trace("RegistrationRequestProcessor: processResponse: Checks passed");

			boolean doArchive = false;
			Object obj = response.get("nodeInfo");
			log.debug("RegistrationRequestProcessor: processResponse: nodeInfo: {} {}", obj==null?null:obj.getClass().getTypeName(), obj);

			// If device info are missing copy them from the registration request
			if (obj==null || obj instanceof Map && ((Map) obj).isEmpty()) {
				log.warn("RegistrationRequestProcessor: processResponse: nodeInfo: ** NO DEVICE INFO IN RESPONSE **");
				if (! registrationRequest.getDevice().getDeviceInfo().isEmpty()) {
					obj = registrationRequest.getDevice().getDeviceInfo();
					log.warn("RegistrationRequestProcessor: processResponse: nodeInfo: ** DEVICE INFO COPIED FROM REGISTRATION REQUEST **");
					log.warn("RegistrationRequestProcessor: processResponse: nodeInfo: {}", obj);
				} else {
					log.warn("RegistrationRequestProcessor: processResponse: nodeInfo: ** PROBLEM: REGISTRATION REQUEST DOES NOT CONTAIN DEVICE INFO EITHER **");
				}
			}
			log.trace("RegistrationRequestProcessor: processResponse: nodeInfo: {}", obj);

			if (obj instanceof Map devInfo) {
				log.trace("RegistrationRequestProcessor: processResponse: devInfo: {}", devInfo);
				// Update request info
				registrationRequest.setLastUpdateDate(Instant.ofEpochMilli(timestamp));

				// Process device info in response
				log.debug("processResponse: Device info in response: id={}, device-info{}", requestId, devInfo);
				boolean allowAllKeys = processorProperties.getAllowedDeviceInfoKeys().contains("*");
				final Map<String,String> processedDevInfo = new LinkedHashMap<>();
				devInfo.forEach((key, value) -> {
					log.warn("RegistrationRequestProcessor: processResponse:       Dev-info pair: {} = {}", key, value);
					if (key!=null && value!=null) {
						String k = key.toString().trim();
						String v = value.toString().trim();
						if (StringUtils.isNotBlank(k) && StringUtils.isNotBlank(v)) {
							if (allowAllKeys || processorProperties.getAllowedDeviceInfoKeys().contains(k.toUpperCase())) {
								processedDevInfo.put(k, v);
								log.warn("RegistrationRequestProcessor: processResponse: Dev-info pair ADDED: {} = {}", key, value);
							} else {
								log.debug("processResponse: Not allowed device info key for request: id={}, key={}", requestId, k);
							}
						}
					}
                });
				log.info("processResponse: New Device info for request: id={}, timestamp={}, device-info={}",
						requestId, timestamp, processedDevInfo);
				registrationRequest.getDevice().setDeviceInfo(processedDevInfo);
				log.trace("RegistrationRequestProcessor: processResponse: Device updated: {}", processedDevInfo);

				// Set node reference (meaningful only in case of Onboarding)
				if (StringUtils.isNotBlank(reference) && currStatus==RegistrationRequestStatus.ONBOARDING_REQUESTED) {
					log.trace("RegistrationRequestProcessor: processResponse: ONBOARDING_REQUESTED: reference={}", reference);
					registrationRequest.setNodeReference(reference.trim());
				}

				// Set new status
				if (currStatus==RegistrationRequestStatus.DATA_COLLECTION_REQUESTED) {
					log.trace("RegistrationRequestProcessor: processResponse: DATA_COLLECTION_REQUESTED: next-status={}", getNextStatus(currStatus));
					registrationRequest.setStatus(getNextStatus(currStatus));
				}
				if (currStatus==RegistrationRequestStatus.ONBOARDING_REQUESTED) {
					log.trace("RegistrationRequestProcessor: processResponse: ONBOARDING_REQUESTED (2): next-status=SUCCESS");
					registrationRequest.setStatus(RegistrationRequestStatus.SUCCESS);
					doArchive = processorProperties.isImmediatelyArchiveSuccessRequests();
				}

				log.debug("processResponse: Done processing response for request: id={}, timestamp={}", requestId, timestamp);
			} else {
				log.warn("processResponse: No device info found in message or it is of wrong type: id={}, obj={}", requestId, obj);
			}

			// If request status is SUCCESS then copy Device in monitoring subsystem
			if (registrationRequest.getStatus() == RegistrationRequestStatus.SUCCESS) {
				try {
					log.trace("RegistrationRequestProcessor: processResponse: SUCCESS - COPY DEVICE:  {}", registrationRequest);
					copyDeviceToMonitoring(registrationRequest);
				} catch (Exception e) {
					log.error("RegistrationRequestProcessor: processResponse: EXCEPTION while copying device to monitoring subsystem: request={}\n", registrationRequest, e);
					registrationRequest.setStatus(RegistrationRequestStatus.ONBOARDING_ERROR);
					registrationRequest.getMessages().add("Exception while copying device to monitoring subsystem: " + e.getMessage());
				}
			}

			// Store changes
			log.debug("RegistrationRequestProcessor: processResponse: Save updated request: id={}, request={}", requestId, registrationRequest);
			registrationRequestService.update(registrationRequest, false, true, null);

			// Archive success requests
			if (doArchive) {
				log.debug("RegistrationRequestProcessor: processResponse: Archiving registration request:  {}", registrationRequest);
				registrationRequestService.archiveRequestBySystem(registrationRequest.getId());
			}
		} else {
			log.error("RegistrationRequestProcessor: processResponse: Request not found: id={}, requestType={}", requestId, requestType);
		}
		log.trace("RegistrationRequestProcessor: processResponse: END:  {}", registrationRequest);
	}

	private RegistrationRequestStatus getNextStatus(RegistrationRequestStatus currStatus) {
		return switch (processorProperties.getAuthorizationType()) {
			case MANUAL -> RegistrationRequestStatus.PENDING_AUTHORIZATION;
			case NONE, ALWAYS_AUTHORIZE -> RegistrationRequestStatus.PENDING_ONBOARDING;
			case ALWAYS_REJECT -> RegistrationRequestStatus.AUTHORIZATION_REJECT;
		};
	}

	private void copyDeviceToMonitoring(RegistrationRequest registrationRequest) {
		log.warn("RegistrationRequestProcessor: copyDeviceToMonitoring: BEGIN: request: {}", registrationRequest);
		log.warn("RegistrationRequestProcessor: copyDeviceToMonitoring: request-DEVICE: {}", registrationRequest.getDevice());
		Device device = objectMapper.convertValue(registrationRequest.getDevice(), Device.class);
		// override values
		device.setId(null);
		device.setStatus(null);
		device.getMessages().clear();

		// copy credentials
		device.setPassword(registrationRequest.getDevice().getPassword());		// ignored by 'objectMapper', so we've to copy them
		device.setPublicKey(registrationRequest.getDevice().getPublicKey());	// ignored by 'objectMapper', so we've to copy them

		// fields specific to Monitoring Device
		//device.setRequest(registrationRequest);
		device.setRequestId(registrationRequest.getId());
		device.setNodeReference(registrationRequest.getNodeReference());
		deviceManagementService.save(device);
		log.warn("RegistrationRequestProcessor: copyDeviceToMonitoring: COPIED-DEVICE: {}", device);
		if (processorProperties.isSalRegistrationEnabled())
            salRegistrationService.ifPresent(salRegistrationService -> salRegistrationService.queueForRegistration(device));
	}
}
