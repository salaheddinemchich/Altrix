package com.example.altrix.pubsub;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

/**
 * Binds the {@link PubsubService} interface to {@link PubsubServiceImpl} for CDI.
 * Kept as a producer (rather than annotating the impl directly) so the impl
 * stays a pure POJO matching the canonical Kafka client design.
 */
@ApplicationScoped
public class PubsubServiceProducer {

    @Inject
    private RetryHandler retryHandler;

    @Inject
    private Provider<IGoogleErrorConverter> googleErrorConverterProvider;

    @Inject
    private KafkaProducer<String, String> kafkaProducer;

    @Inject
    private KafkaConsumer<String, String> kafkaConsumer;

    @Produces
    @ApplicationScoped
    public PubsubService pubsubService() {
        return new PubsubServiceImpl(retryHandler, googleErrorConverterProvider, kafkaProducer, kafkaConsumer);
    }
}