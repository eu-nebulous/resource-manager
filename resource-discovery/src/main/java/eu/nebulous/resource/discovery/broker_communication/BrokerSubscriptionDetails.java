package eu.nebulous.resource.discovery.broker_communication;

import static eu.nebulous.resource.discovery.broker_communication.BrokerPublisher.EMPTY;

public class BrokerSubscriptionDetails {
    String broker_username = "admin";
    String broker_password = "admin";
    String broker_ip = "localhost";
    String application_name = "default_application";
    String topic = EMPTY;

    public BrokerSubscriptionDetails(String broker_ip, String broker_username, String broker_password,String application_name, String topic) {
        this.broker_ip = broker_ip;
        this.broker_username = broker_username;
        this.broker_password = broker_password;
        this.topic = topic;
        this.application_name = application_name;
    }

    public BrokerSubscriptionDetails(boolean fake_broker_subscription) {
        if (fake_broker_subscription) {
            this.broker_username = EMPTY;
            this.broker_password = EMPTY;
            this.broker_ip = EMPTY;
            this.topic = EMPTY;
            this.application_name = EMPTY;
        }
    }

    public String getBroker_username() {
        return broker_username;
    }

    public void setBroker_username(String broker_username) {
        this.broker_username = broker_username;
    }

    public String getBroker_password() {
        return broker_password;
    }

    public void setBroker_password(String broker_password) {
        this.broker_password = broker_password;
    }

    public String getBroker_ip() {
        return broker_ip;
    }

    public void setBroker_ip(String broker_ip) {
        this.broker_ip = broker_ip;
    }

    public String getApplication_name() {
        return application_name;
    }

    public void setApplication_name(String application_name) {
        this.application_name = application_name;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }
}
