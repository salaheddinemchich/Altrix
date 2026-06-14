package com.altrix.orchestrator.infrastructure.rag;

import com.altrix.orchestrator.infrastructure.config.DocumentationCorpusConfig;
import com.altrix.orchestrator.infrastructure.config.DocumentationCorpusConfig.AllowedDomain;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DomainAllowListValidatorTest {

    private DomainAllowListValidator validator(List<AllowedDomain> domains) {
        return new DomainAllowListValidator(
                new DocumentationCorpusConfig(domains, List.of()));
    }

    @Test
    void allowsExactHostWhenNoPathPrefixConfigured() {
        var v = validator(List.of(new AllowedDomain("kafka.apache.org", List.of())));
        assertThat(v.isAllowed("https://kafka.apache.org/documentation/#producerapi")).isTrue();
        assertThat(v.isAllowed("https://kafka.apache.org/")).isTrue();
    }

    @Test
    void rejectsHostNotOnAllowList() {
        var v = validator(List.of(new AllowedDomain("kafka.apache.org", List.of())));
        assertThat(v.isAllowed("https://medium.com/some-kafka-article")).isFalse();
        assertThat(v.isAllowed("https://example.com")).isFalse();
    }

    @Test
    void enforcesPathPrefixWhenConfigured() {
        // cloud.google.com is broad; lock down to /pubsub/* and /docs/authentication/*.
        var v = validator(List.of(new AllowedDomain(
                "cloud.google.com", List.of("/pubsub/", "/docs/authentication/"))));
        assertThat(v.isAllowed("https://cloud.google.com/pubsub/docs/overview")).isTrue();
        assertThat(v.isAllowed("https://cloud.google.com/docs/authentication/client-libraries")).isTrue();
        // Off-prefix paths on the same host are blocked.
        assertThat(v.isAllowed("https://cloud.google.com/bigquery/docs")).isFalse();
        assertThat(v.isAllowed("https://cloud.google.com/")).isFalse();
    }

    @Test
    void hostMatchIsCaseInsensitive() {
        var v = validator(List.of(new AllowedDomain("kafka.apache.org", List.of())));
        assertThat(v.isAllowed("https://Kafka.Apache.Org/")).isTrue();
        assertThat(v.isAllowed("https://KAFKA.APACHE.ORG/")).isTrue();
    }

    @Test
    void rejectsMalformedAndBlankInput() {
        var v = validator(List.of(new AllowedDomain("kafka.apache.org", List.of())));
        assertThat(v.isAllowed(null)).isFalse();
        assertThat(v.isAllowed("")).isFalse();
        assertThat(v.isAllowed("   ")).isFalse();
        assertThat(v.isAllowed("not a url with spaces and ^")).isFalse();
    }

    @Test
    void rejectsUrlWithoutHost() {
        var v = validator(List.of(new AllowedDomain("kafka.apache.org", List.of())));
        // A relative path has no host.
        assertThat(v.isAllowed("/some/path")).isFalse();
        // file: URLs have no host.
        assertThat(v.isAllowed("file:///etc/passwd")).isFalse();
    }

    @Test
    void multipleEntriesForSameHostCombineAdditively() {
        // First entry only allows /pubsub/, second adds /docs/authentication/.
        // Either should match (we walk the list and short-circuit on first hit).
        var v = validator(List.of(
                new AllowedDomain("cloud.google.com", List.of("/pubsub/")),
                new AllowedDomain("cloud.google.com", List.of("/docs/authentication/"))
        ));
        assertThat(v.isAllowed("https://cloud.google.com/pubsub/docs/overview")).isTrue();
        assertThat(v.isAllowed("https://cloud.google.com/docs/authentication/client-libraries")).isTrue();
        assertThat(v.isAllowed("https://cloud.google.com/storage/docs")).isFalse();
    }

    @Test
    void emptyAllowListRejectsEverything() {
        var v = validator(List.of());
        assertThat(v.isAllowed("https://kafka.apache.org/")).isFalse();
        assertThat(v.isAllowed("https://cloud.google.com/pubsub/docs/overview")).isFalse();
    }
}
