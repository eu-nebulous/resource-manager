package eu.nebulous.resource.discovery.broker_communication;

import eu.nebulouscloud.exn.core.Context;
import eu.nebulouscloud.exn.handlers.ConnectorHandler;

import java.util.concurrent.atomic.AtomicBoolean;

public class CustomConnectorHandler extends ConnectorHandler {
    private Context context;
    private final AtomicBoolean ready = new AtomicBoolean(false);

    @Override
    public void onReady(Context context) {
        this.context = context;
        synchronized (ready) {
            this.ready.set(true);
            this.ready.notify();
        }
    }
    public void remove_consumer_with_key(String key){
        context.unregisterConsumer(key);
    }

    public Context getContext() {
        return context;
    }

    public void setContext(Context context) {
        this.context = context;
    }

    public AtomicBoolean getReady() {
        return ready;
    }
}
