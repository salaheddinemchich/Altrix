package com.altrix.orchestrator.domain.model.leak;

/**
 * Categories of Google Cloud Pub/Sub residue that survived a migration.
 *
 * <p>The validator runs AFTER the migrator + contract repair pass.  Anything
 * here is, by definition, a migration failure: the target is Apache Kafka,
 * so any Google Pub/Sub artifact remaining in the source tree is wrong.
 *
 * <p>Kinds are intentionally specific so the repair prompt can be
 * kind-aware — "replace this Pub/Sub method chain with Kafka equivalent"
 * needs different guidance than "drop this unresolved import".
 */
public enum PubSubLeakKind {

    /**
     * An {@code import} statement points at a Google Pub/Sub package
     * ({@code com.google.api.services.pubsub.*},
     * {@code com.google.cloud.pubsub.*}, {@code com.google.pubsub.*},
     * {@code org.springframework.cloud.gcp.pubsub.*}).
     *
     * <p>This is the most visible signal of a wholesale migration failure
     * (the AI call returned the original file unchanged, usually due to
     * rate-limit / output rejection).
     */
    GOOGLE_IMPORT,

    /**
     * A type token at code position uses a name that exclusively belongs to
     * the Google Pub/Sub API surface ({@code Pubsub}, {@code PubsubMessage},
     * {@code ReceivedMessage}, {@code PublishRequest}, {@code PullRequest},
     * {@code PullResponse}, {@code PublishResponse}, {@code AcknowledgeRequest},
     * {@code PubSubTemplate}) AND the project does not declare a type of
     * that name itself.
     */
    GOOGLE_TYPE_REFERENCE,

    /**
     * A method-call chain matches a Google Pub/Sub idiom — e.g.
     * {@code pubsub.projects().topics().publish(...).execute()} or
     * {@code pubsub.projects().subscriptions().acknowledge(...).execute()}
     * or {@code testIamPermissions(...)}.
     *
     * <p>Catches files whose imports were rewritten but whose method body
     * was left untouched (the {@code PubsubTopicTestIAMPermissionsTask}
     * pattern: a comment block referencing the original API).
     */
    GOOGLE_METHOD_CHAIN,

    /**
     * A POM / Gradle / Ivy declaration of a Pub/Sub dependency that should
     * have been stripped during the build-file migration
     * ({@code google-api-services-pubsub}, {@code google-cloud-pubsub},
     * {@code spring-cloud-gcp-pubsub}, {@code spring-cloud-gcp-starter-pubsub}).
     */
    GOOGLE_DEPENDENCY,

    /**
     * A bootstrap-config key that targets Pub/Sub
     * ({@code spring.cloud.gcp.pubsub.*}, {@code gcp.pubsub.*},
     * {@code GOOGLE_APPLICATION_CREDENTIALS}, {@code PUBSUB_EMULATOR_HOST}).
     */
    GOOGLE_CONFIG_KEY
}
