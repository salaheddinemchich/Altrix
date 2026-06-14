package com.example.altrix.pubsub;

import com.google.common.collect.Lists;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Generic splitter — chops a collection of {@code T} into a list of Kafka producer requests 
 * honouring the per-request and per-item size constraints.
 */
public class PubSubRequestBuilderImpl {

    public <T, R> List<R> splitMessagesIntoMultipleRequests(
            Collection<T> items,
            PubSubRequestConstraints constraints,
            ToIntFunction<T> payloadSizer,
            Function<T, String> payloadAsString,
            Function<List<T>, R> requestFactory) {
        List<R> requests = Lists.newArrayList();
        List<T> current = new ArrayList<>();
        int currentBytes = 0;

        for (T item : items) {
            int size = payloadSizer.applyAsInt(item);
            if (size > constraints.getMaxItemBytes()) {
                throw new IllegalArgumentException(
                        "Kafka item exceeds max item size (" + size + " > " + constraints.getMaxItemBytes() + " bytes): " + payloadAsString.apply(item));
            }
            boolean wouldOverflow = current.size() + 1 > constraints.getMaxItemsPerRequest()
                    || currentBytes + size > constraints.getMaxRequestBytes();

            if (wouldOverflow && !current.isEmpty()) {
                requests.add(requestFactory.apply(current));
                current = new ArrayList<>();
                currentBytes = 0;
            }
            current.add(item);
            currentBytes += size;
        }
        if (!current.isEmpty()) {
            requests.add(requestFactory.apply(current));
        }
        return requests;
    }
}