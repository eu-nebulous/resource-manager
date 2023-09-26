package eu.nebulous.resource.discovery.registration;

import java.util.concurrent.Future;

public interface IRegistrationRequestProcessor {
	Future<String> processRequests();
}
