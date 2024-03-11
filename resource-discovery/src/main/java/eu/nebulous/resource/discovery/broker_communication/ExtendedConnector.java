package eu.nebulous.resource.discovery.broker_communication;

import eu.nebulouscloud.exn.Connector;
import eu.nebulouscloud.exn.core.Consumer;
import eu.nebulouscloud.exn.core.Context;
import eu.nebulouscloud.exn.core.Publisher;
import eu.nebulouscloud.exn.handlers.ConnectorHandler;
import eu.nebulouscloud.exn.settings.ExnConfig;
import org.apache.catalina.util.CustomObjectInputStream;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ExtendedConnector extends Connector {
    private CustomConnectorHandler handler;

    private Connector connector;
    public ExtendedConnector(String component, ConnectorHandler handler, List<Publisher> publishers, List<Consumer> consumers, boolean enableState, boolean enableHealth, ExnConfig configuration) {
        super(component, handler, publishers, consumers, enableState, enableHealth, configuration);
        this.handler =(CustomConnectorHandler) handler;
    }

    public ExtendedConnector(String component, ConnectorHandler handler, List<Publisher> publishers, List<Consumer> consumers, boolean enableState, ExnConfig configuration) {
        super(component, handler, publishers, consumers, enableState, configuration);
        this.handler = (CustomConnectorHandler) handler;
    }

    public ExtendedConnector(String component, ConnectorHandler handler, List<Publisher> publishers, List<Consumer> consumers, ExnConfig configuration) {
        super(component, handler, publishers, consumers, configuration);
        this.handler = (CustomConnectorHandler) handler;
    }

    public CustomConnectorHandler getHandler() {
        return (CustomConnectorHandler) handler;
    }

    public void setHandler(CustomConnectorHandler handler) {
        this.handler = handler;
    }

    public void remove_consumer_with_key(String key) {
        try {
            Context context = ((CustomConnectorHandler)handler).getContext();
            context.unregisterConsumer(key);
        }catch (ClassCastException c){
            Logger.getAnonymousLogger().log(Level.WARNING,"Could not unregister consumer, as the handler of the Connector it belongs to is not a CustomConnectorHandler");
        }
    }

    private void remove_publisher_with_key(String key) {
        try {
            Context context = ((CustomConnectorHandler)handler).getContext();
            context.unregisterPublisher(key);
        }catch (ClassCastException c){
            Logger.getAnonymousLogger().log(Level.WARNING,"Could not unregister consumer, as the handler of the Connector it belongs to is not a CustomConnectorHandler");
        }
    }

    public void add_consumer(Consumer newConsumer) {

        try {
            ((CustomConnectorHandler)handler).getContext().registerConsumer(newConsumer);
        }catch (ClassCastException c){
            Logger.getAnonymousLogger().log(Level.WARNING,"Could not register consumer, as the handler of the Connector it belongs to is not a CustomConnectorHandler");
        }
    }

    public void stop(ArrayList<Consumer> consumers, ArrayList <Publisher> publishers){
        if (consumers.size()>0) {
            stop_consumers(consumers);
        }
        if (publishers.size()>0) {
            stop_publishers(publishers);
        }
    }


    public void stop_consumers(ArrayList<Consumer> consumers){
        for (Consumer consumer : consumers){
            remove_consumer_with_key(consumer.key());
        }
    }
    public void stop_publishers(ArrayList<Publisher> publishers){
        for (Publisher publisher : publishers){
            remove_publisher_with_key(publisher.key());
        }
    }

    public Connector getConnector() {
        return connector;
    }

    public void setConnector(Connector connector) {
        this.connector = connector;
    }

}
