package eu.nebulous.resource.discovery.registration.model;

public enum RegistrationRequestStatus {
    NEW_REQUEST, PRE_AUTHORIZATION, PRE_AUTHORIZATION_REJECT,
    PENDING_DATA_COLLECTION, DATA_COLLECTION, DATA_COLLECTION_ERROR,
    PENDING_AUTHORIZATION, AUTHORIZATION, AUTHORIZATION_REJECT,
    PENDING_ONBOARDING, ONBOARDING, ONBOARDING_ERROR,
    SUCCESS
}
