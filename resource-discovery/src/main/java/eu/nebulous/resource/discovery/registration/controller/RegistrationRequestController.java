package eu.nebulous.resource.discovery.registration.controller;

import eu.nebulous.resource.discovery.ResourceDiscoveryProperties;
import eu.nebulous.resource.discovery.broker_communication.BrokerSubscriber;
import eu.nebulous.resource.discovery.broker_communication.BrokerSubscriptionDetails;
import eu.nebulous.resource.discovery.broker_communication.SynchronousBrokerPublisher;
import eu.nebulous.resource.discovery.registration.IRegistrationRequestProcessor;
import eu.nebulous.resource.discovery.registration.model.ArchivedRegistrationRequest;
import eu.nebulous.resource.discovery.registration.model.RegistrationRequest;
import eu.nebulous.resource.discovery.registration.model.RegistrationRequestException;
import eu.nebulous.resource.discovery.registration.model.RegistrationRequestStatus;
import eu.nebulous.resource.discovery.registration.service.RegistrationRequestService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.BiFunction;

import static eu.nebulous.resource.discovery.broker_communication.BrokerPublisher.getExistingOrNewBrokerPublisher;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/discovery")
public class RegistrationRequestController implements InitializingBean {
	private final static String REQUIRES_ADMIN_ROLE = "hasAuthority('ROLE_ADMIN')";
	private final static String GET_USER_TOPIC = "eu.nebulouscloud.ui.user.get";
	private static ResourceDiscoveryProperties processorPropertiesStatic;
	private final ResourceDiscoveryProperties processorProperties;
	private final RegistrationRequestService registrationRequestService;
	private final IRegistrationRequestProcessor registrationRequestProcessor;
	
	private static final Map<String,String> nonce_messages = Collections.synchronizedMap(new HashMap<>());
	private static final Set<String> nonce_message_published = Collections.synchronizedSet(new HashSet<>());
	private static boolean has_initialized_nonce_connector = false;
	private static BrokerSubscriber nonce_subscriber;
	private static SynchronousBrokerPublisher nonce_publisher;
	private static final int TIMEOUT_DURATION_SECONDS = 5;


	@Override
	public void afterPropertiesSet() throws Exception {
		processorPropertiesStatic = processorProperties;

		log.debug("Initializing connector");
		if (!has_initialized_nonce_connector){
			nonce_publisher = new SynchronousBrokerPublisher(GET_USER_TOPIC,processorPropertiesStatic.getNebulous_broker_ip_address(), processorPropertiesStatic.getNebulous_broker_port(), processorPropertiesStatic.getNebulous_broker_username(), processorPropertiesStatic.getNebulous_broker_password(), "");

			//Testing change 1
			/*
			nonce_subscriber = new BrokerSubscriber(GET_USER_TOPIC+".>",processorPropertiesStatic.getNebulousBrokerIpAddress(), processorPropertiesStatic.getNebulousBrokerPort(), processorPropertiesStatic.getNebulousBrokerUsername(),processorPropertiesStatic.getNebulousBrokerPassword(), "","");
			//Testing change 1
			/*
			nonce_subscriber = new BrokerSubscriber(GET_USER_TOPIC+".>",processorPropertiesStatic.getNebulous_broker_ip_address(), processorPropertiesStatic.getNebulous_broker_port(), processorPropertiesStatic.getNebulous_broker_username(),processorPropertiesStatic.getNebulous_broker_password(), "","");

			//nonce_synced_publisher = new SyncedBrokerPublisher();
			
			log.debug("Defining function");
			BiFunction<BrokerSubscriptionDetails,String,String> function = (broker_subscription_details, message_body) -> {
				
				String topic_suffix = broker_subscription_details.getTopic().replace("topic://"+GET_USER_TOPIC,"");
				if (topic_suffix!=null && !topic_suffix.isEmpty()){
					//String nonce_from_topic = StringUtils.substringAfterLast(topic_suffix,".");
					JSONParser parser = new JSONParser();
					JSONObject message_json;
                    try {
                         message_json = (JSONObject) parser.parse(message_body);
                    } catch (ParseException e) {
                        throw new RuntimeException(e);
                    }
                    String nonce = (String) message_json.get("token");
					if (StringUtils.isBlank(nonce)) nonce = (String) message_json.get("uuid");
					log.warn("Received message"+message_body+" at "+broker_subscription_details.getTopic());
					nonce_messages.put(nonce,message_body);
					nonce_message_published.add(nonce);
				}
				return message_body;
			};

			log.debug("Starting subscription thread");
			Thread subscriber_thread = new Thread (()->	nonce_subscriber.subscribe(function,""));//Could have a particular application name instead of empty here if needed
			subscriber_thread.start();
			has_initialized_nonce_connector = true;
			
			 */
		}
		
	}
	
	@GetMapping(value = "/whoami", produces = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> whoami(Authentication authentication, HttpServletRequest request) {
		List<String> roles = authentication != null
				? authentication.getAuthorities().stream()
						.map(Object::toString)
						.map(s -> StringUtils.removeStartIgnoreCase(s, "ROLE_"))
						.toList()
				: Collections.emptyList();
        HttpSession session = request.getSession(false);
        String appId = Optional.ofNullable(session)
                .map(s -> s.getAttribute("appId"))
                .map(Object::toString)
                .orElse("");
		return Map.of(
				"user", authentication!=null ? authentication.getName() : "",
				"roles", roles,
				"admin", roles.contains("ADMIN"),
                "appId", appId
		);
	}

	@PreAuthorize(REQUIRES_ADMIN_ROLE)
	@GetMapping(value = "/request/process", produces = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, String> processRequests() throws ExecutionException, InterruptedException {
		Future<String> future = registrationRequestProcessor.processRequests();
		return Map.of("result", future.isDone() ? future.get() : "STARTED");
	}

	@PreAuthorize(REQUIRES_ADMIN_ROLE)
	@GetMapping(value = "/request/all", produces = MediaType.APPLICATION_JSON_VALUE)
	public List<RegistrationRequest> listRequestsAdmin(Authentication authentication) {
		return registrationRequestService.getAll();
	}

	@GetMapping(value = "/request", produces = MediaType.APPLICATION_JSON_VALUE)
	public List<RegistrationRequest> listRequests(Authentication authentication) {
		return registrationRequestService.getAllAsUser(authentication);
	}

	@GetMapping(value = "/request/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
	public RegistrationRequest getRequest(@PathVariable String id, Authentication authentication) {
		return registrationRequestService.getByIdAsUser(id, authentication)
				.orElseThrow(() -> new RegistrationRequestException("Not found registration request with id: "+id));
	}

	//@GetMapping(value= "/nonce_username", produces = MediaType.APPLICATION_JSON_VALUE)
	public static String getNonceUsername(@RequestParam Map data) {
		
		//Get the nonce from the provided data, along with the appId
		//initialize the BrokerPublisher
		//Parse the response of the SyncedPublisher
		//Return an appropriate json object to the client
		log.debug("Initializing processing");
		String nonce = (String) data.get("nonce");
		String appId = (String) data.get("appId");
		
		
		JSONObject json_request = new JSONObject();
		json_request.put("token",nonce);
		json_request.put("appId",appId);
		
		String empty_response = null; //"{\"nonce\": \"" + nonce + "\", \"username\": \"" + "" + "\"}";
		
		log.debug("Sending nonce message to middleware");
		Map nonce_publisher_response = nonce_publisher.publish_for_response(json_request.toJSONString(),List.of(""));
		
		if (StringUtils.isBlank(nonce) || StringUtils.isBlank(appId)) {
			return empty_response;
		}
		
		int cumulative_sleep = 0;
		int sleep_duration_millis = 500;
		
		JSONParser parser = new JSONParser();
		
		//Testing Change 1
		/*
		while (!nonce_message_published.contains(nonce)){
			log.debug("While iteration, nonce messages {}",nonce_messages.keySet());
			log.debug("While iteration, nonce message published {}",nonce_message_published);
			if (cumulative_sleep>= TIMEOUT_DURATION_SECONDS *1000){
				//nonce_message_published.remove(nonce);
				//nonce_messages.remove(nonce);
				return empty_response;
			}
            try {
                Thread.sleep(sleep_duration_millis);
				cumulative_sleep = cumulative_sleep + sleep_duration_millis;
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        
		 */
		try {
			
			//nonce_message_published.remove(nonce);
			//nonce_messages.remove(nonce);
			
			//Testing change 1
			/*
			JSONObject response = (JSONObject) parser.parse(nonce_messages.get(nonce));
			 */
			JSONObject response = new JSONObject(nonce_publisher_response);
			String username = (String) response.get("username");

			// Return an appropriate JSON object to the client
			//return "{\"nonce\": \"" + nonce + "\", \"username\": \"" + username + "\"}";
			return username;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		
	}
	
	@PutMapping(value = "/request", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public RegistrationRequest createRequest(@RequestBody RegistrationRequest registrationRequest, Authentication authentication) {
		return registrationRequestService.saveAsUser(registrationRequest, authentication);
	}

	@PostMapping(value = "/request/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public RegistrationRequest updateRequest(@PathVariable String id,
											 @RequestBody RegistrationRequest registrationRequest,
											 Authentication authentication)
	{
		if (! StringUtils.equals(id, registrationRequest.getId()))
			throw new RegistrationRequestException(
					"Id does not match the id in registration request: "+id+" <> "+registrationRequest.getId());
		return registrationRequestService.updateAsUser(registrationRequest, authentication);
	}

	@DeleteMapping(value = "/request/{id}")
	public void deleteRequest(@PathVariable String id, Authentication authentication) {
		registrationRequestService.deleteByIdAsUser(id, authentication);
	}

	@PreAuthorize(REQUIRES_ADMIN_ROLE)
	@GetMapping(value = "/request/{id}/authorize", produces = MediaType.APPLICATION_JSON_VALUE)
	public RegistrationRequest authorizeRequest(@PathVariable String id, Authentication authentication) {
		return registrationRequestService.authorizeRequest(id, true, authentication);
	}

	@PreAuthorize(REQUIRES_ADMIN_ROLE)
	@GetMapping(value = "/request/{id}/reject", produces = MediaType.APPLICATION_JSON_VALUE)
	public RegistrationRequest rejectRequest(@PathVariable String id, Authentication authentication) {
		return registrationRequestService.authorizeRequest(id, false, authentication);
	}

	@PreAuthorize(REQUIRES_ADMIN_ROLE)
	@GetMapping(value = "/request/{id}/status/{newStatus}", produces = MediaType.APPLICATION_JSON_VALUE)
	public RegistrationRequest setRequestStatus(@PathVariable String id, @PathVariable String newStatus) {
		RegistrationRequestStatus _newStatus = RegistrationRequestStatus.valueOf(newStatus);
		RegistrationRequest request = registrationRequestService.getById(id)
				.orElseThrow(() -> new RegistrationRequestException("Not found registration request with id: " + id));
		request.setStatus(_newStatus);
		return registrationRequestService.update(request, false, null);
	}

	@PreAuthorize(REQUIRES_ADMIN_ROLE)
	@GetMapping(value = "/request/{id}/archive", produces = MediaType.APPLICATION_JSON_VALUE)
	public String archiveRequest(@PathVariable String id, Authentication authentication) {
		registrationRequestService.archiveRequest(id, authentication);
		return "ARCHIVED";
	}

	@PreAuthorize(REQUIRES_ADMIN_ROLE)
	@PostMapping(value = "/request/{id}/unarchive",
			consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public RegistrationRequest unarchiveRequest(@PathVariable String id,
												@RequestBody Map<String,String> credentials,
												Authentication authentication)
	{
		registrationRequestService.unarchiveRequest(id, credentials, authentication);
		return registrationRequestService.getById(id)
				.orElseThrow(() -> new RegistrationRequestException("Failed to unarchive registration request with id: " + id));
	}

	@GetMapping(value = "/request/archived", produces = MediaType.APPLICATION_JSON_VALUE)
	public List<ArchivedRegistrationRequest> listArchivedRequests(Authentication authentication) {
		return registrationRequestService.getArchivedAllAsUser(authentication);
	}

	@PreAuthorize(REQUIRES_ADMIN_ROLE)
	@GetMapping(value = "/request/archived/all", produces = MediaType.APPLICATION_JSON_VALUE)
	public List<ArchivedRegistrationRequest> listArchivedRequestsAdmin() {
		return registrationRequestService.getArchivedAll();
	}

	@GetMapping(value = "/request/archived/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
	public ArchivedRegistrationRequest getArchivedRequest(@PathVariable String id, Authentication authentication) {
		return registrationRequestService.getArchivedByIdAsUser(id, authentication)
				.orElseThrow(() -> new RegistrationRequestException("Not found archived registration request with id: "+id));
	}

	@ResponseStatus(value = HttpStatus.BAD_REQUEST)
	@ExceptionHandler(RegistrationRequestException.class)
	public Map<String,Object> handleRegistrationRequestException(RegistrationRequestException exception) {
		return Map.of(
				"status", HttpStatus.BAD_REQUEST.value(),
				"timestamp", System.currentTimeMillis(),
				"exception", exception.getClass().getName(),
				"message", exception.getMessage()
		);
	}

}
