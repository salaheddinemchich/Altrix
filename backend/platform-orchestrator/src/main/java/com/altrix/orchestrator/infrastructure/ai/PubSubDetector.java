package com.altrix.orchestrator.infrastructure.ai;

/**
 * Static heuristic for "does this file contain Google Cloud Pub/Sub code?".
 *
 * <p>Used as a safety net by both the {@link ContextPruner} (to find files the
 * AI planner under-listed) and by the migrator itself (to skip files the pruner
 * pulled in but that turn out to be unrelated).  Permissive on purpose — a
 * false positive only costs one extra AI call, but a false negative silently
 * skips a real migration target.
 *
 * <p>Covers both the modern Spring Cloud GCP API and the legacy REST v1 API
 * plus the most common user-wrapper class names and build/bootstrap markers.
 */
public final class PubSubDetector {

    private PubSubDetector() {
    }

    /**
     * True if the file path is a Java/build/config file we know how to rewrite.
     */
    public static boolean isMigratableFile(String path) {
        if (path == null) return false;
        String p = path.toLowerCase();
        return p.endsWith(".java")
                || p.endsWith(".xml")           // pom.xml, ivy.xml, EJB / Jakarta EE descriptors
                || p.endsWith(".yml")
                || p.endsWith(".yaml")
                || p.endsWith(".properties")
                || p.endsWith(".gradle")
                || p.endsWith(".gradle.kts");
    }

    /**
     * True if the file content shows any Pub/Sub fingerprint.
     */
    public static boolean hasPubSubCode(String content) {
        if (content == null || content.isEmpty()) return false;
        return
                // (A) Modern Spring Cloud GCP
                content.contains("google.cloud.pubsub")
                        || content.contains("PubSubTemplate")
                        || content.contains("@PubSubListener")
                        || content.contains("@SubscriberHandler")
                        || content.contains("MessagePublisher")

                        // (B) Legacy GCP Pub/Sub REST v1 — com.google.api.services.pubsub.*
                        || content.contains("com.google.api.services.pubsub")
                        || content.contains("google.api.services.pubsub")
                        || content.contains("ReceivedMessage")
                        || content.contains("PubsubMessage")

                        // (C) Common user-wrapper class names + their typical operations
                        || content.contains("PubsubService")
                        || content.contains("PubSubService")
                        || content.contains("PubsubClient")
                        || content.contains("PubSubClient")
                        || content.contains("PubsubConfig")
                        || content.contains("PubSubConfig")
                        || content.contains("getOrCreateTopic")
                        || content.contains("getOrCreateSubscription")

                        // (D) Build / dependency markers — pom.xml, build.gradle, ivy
                        || content.contains("google-api-services-pubsub")
                        || content.contains("google-cloud-pubsub")
                        || content.contains("spring-cloud-gcp-pubsub")
                        || content.contains("spring-cloud-gcp-starter-pubsub")

                        // (E) Bootstrap config markers — application.yml / .properties
                        || content.contains("spring.cloud.gcp.pubsub")
                        || content.contains("gcp.pubsub")
                        || content.contains("GOOGLE_APPLICATION_CREDENTIALS")
                        || content.contains("PUBSUB_EMULATOR_HOST")

                        // (F) Catch-all for the bare "Pubsub" / "PubSub" identifier — covers
                        //     user-defined helper classes our specific name list misses
                        //     (PubsubFactory, PubsubProperties, PubsubAdmin, ...).  False
                        //     positives only cost an extra AI call.
                        || content.contains("Pubsub")
                        || content.contains("PubSub")
                        || content.contains("pubsub");
    }
}
