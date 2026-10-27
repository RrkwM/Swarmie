package dev.swarmie.adapter.event;

import dev.swarmie.contract.EventBatch;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Server-tick event publisher. Listeners are called synchronously on the
 * server thread and must not block; listener exceptions are isolated.
 */
public final class AdapterEventBus {
    private final Map<Consumer<EventBatch>, Subscription> subscriptions = new IdentityHashMap<>();

    public synchronized Subscription subscribe(Consumer<EventBatch> listener) {
        Objects.requireNonNull(listener, "listener");
        Subscription existing = this.subscriptions.get(listener);
        if (existing != null) {
            return existing;
        }
        Subscription subscription = new Subscription(this, listener);
        this.subscriptions.put(listener, subscription);
        return subscription;
    }

    public synchronized void unsubscribe(Subscription subscription) {
        if (subscription == null) {
            return;
        }
        this.subscriptions.remove(subscription.listener);
        subscription.active = false;
    }

    public void publish(EventBatch batch) {
        if (batch == null || batch.events().isEmpty()) {
            return;
        }
        Subscription[] targets;
        synchronized (this) {
            targets = this.subscriptions.values().toArray(Subscription[]::new);
        }
        for (Subscription subscription : targets) {
            if (!subscription.active) {
                continue;
            }
            try {
                subscription.listener.accept(batch);
            }
            catch (RuntimeException exception) {
                System.err.println("Adapter event listener failed: " + exception);
            }
        }
    }

    public static final class Subscription {
        private final AdapterEventBus bus;
        private final Consumer<EventBatch> listener;
        private volatile boolean active = true;

        private Subscription(AdapterEventBus bus, Consumer<EventBatch> listener) {
            this.bus = bus;
            this.listener = listener;
        }

        public void close() {
            this.bus.unsubscribe(this);
        }
    }
}
