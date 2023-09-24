package eu.nebulous.resource.discovery.registration.model;

public class RegistrationRequestException extends RuntimeException {
    public RegistrationRequestException(String message) { super(message); }
    public RegistrationRequestException(Throwable t) { super(t); }
    public RegistrationRequestException(String message, Throwable t) { super(message, t); }
}
