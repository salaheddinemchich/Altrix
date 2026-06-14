package com.example.altrix.pubsub;

import org.apache.kafka.clients.producer.KafkaProducer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

/**
 * Binds the {@link PubsubService} interface to {@link PubsubServiceImpl} for CDI.
 * Kept as a producer (rather than annotating the impl directly) so the impl
 * stays a pure POJO matching the canonical Kafka producer design.
 */
@ApplicationScoped
public class PubsubServiceProducer {

    @Inject
    private RetryHandler retryHandler;

    @Inject
    private Provider<IGoogleErrorConverter> googleErrorConverterProvider;

    @Inject
    private KafkaProducer<String, String> kafkaProducer;

    @Produces
    @ApplicationScoped
    public PubsubService pubsubService() {
        return new PubsubServiceImpl(retryHandler, googleErrorConverterProvider, kafkaProducer);
    }
}