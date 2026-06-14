package com.altrix.orchestrator.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * External-documentation corpus configuration.
 *
 * <p>Replaces the hard-coded {@code PAGES} list that lived inside
 * {@code DocumentationIngestionService}.  Every page ingested at startup
 * comes from here; every page also has its URL validated against the
 * {@link #allowedDomains() allow-list} so an out-of-policy URL slipped
 * into config never reaches the network.
 *
 * <p>The corpus is curated specifically for <b>Google Cloud Pub/Sub
 * → Apache Kafka</b> migrations.  Reverse-direction guides and
 * unrelated topics belong elsewhere.
 *
 * <pre>{@code
 * documentation:
 *   allowed-domains:
 *     - host: kafka.apache.org
 *     - host: cloud.google.com
 *       path-prefixes: ["/pubsub/", "/docs/authentication/"]
 *     - host: docs.spring.io
 *     - host: quarkus.io
 *     - host: openliberty.io
 *     - host: download.eclipse.org
 *     - host: jakarta.ee
 *     - host: googlecloudplatform.github.io
 *       path-prefixes: ["/spring-cloud-gcp/"]
 *   pages:
 *     - logical-path: kafka/producers
 *       url: https://kafka.apache.org/documentation/#producerapi
 *     - logical-path: gcp-pubsub/overview
 *       url: https://cloud.google.com/pubsub/docs/overview
 * }</pre>
 */
@ConfigurationProperties(prefix = "documentation")
public record DocumentationCorpusConfig(
        List<AllowedDomain> allowedDomains,
        List<Page> pages
) {

    public DocumentationCorpusConfig {
        allowedDomains = allowedDomains != null ? List.copyOf(allowedDomains) : List.of();
        pages          = pages          != null ? List.copyOf(pages)          : List.of();
    }

    /**
     * A single host the ingester is permitted to fetch from.
     *
     * @param host          required, lowercase host name (no scheme, no port).
     *                      Matched case-insensitively against the URL's host.
     * @param pathPrefixes  optional whitelist of path prefixes.  When empty
     *                      the entire host is allowed; otherwise the URL's
     *                      path must start with at least one prefix.
     *                      Always include the leading "/".
     */
    public record AllowedDomain(
            String host,
            @DefaultValue({}) List<String> pathPrefixes
    ) {
        public AllowedDomain {
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("AllowedDomain.host must not be blank");
            }
            host = host.toLowerCase().trim();
            pathPrefixes = pathPrefixes != null ? List.copyOf(pathPrefixes) : List.of();
        }
    }

    /**
     * One documentation page entry.
     *
     * @param logicalPath stable identifier the embedding store uses to
     *                    dedupe — same logicalPath = same row.  Examples:
     *                    {@code "kafka/producers"}, {@code "gcp-pubsub/ordering"}.
     * @param url         canonical HTTPS URL the page is fetched from.
     *                    Validated against {@link #allowedDomains()}.
     */
    public record Page(String logicalPath, String url) {
        public Page {
            if (logicalPath == null || logicalPath.isBlank()) {
                throw new IllegalArgumentException("Page.logicalPath must not be blank");
            }
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("Page.url must not be blank");
            }
        }
    }
}
