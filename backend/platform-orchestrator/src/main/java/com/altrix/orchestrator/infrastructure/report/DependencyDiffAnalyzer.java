package com.altrix.orchestrator.infrastructure.report;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Computes the Maven dependency delta between the original and migrated
 * {@code pom.xml} for the "Dependency Changes" report section.
 *
 * <p>Purely deterministic — same XXE-safe DOM parse as {@link
 * com.altrix.orchestrator.infrastructure.migration.PomSanitizer}, just
 * comparing artifactId sets instead of stripping unauthorised ones.
 */
@Component
public class DependencyDiffAnalyzer {

    public record Result(List<String> added, List<String> removed) {
        public static final Result EMPTY = new Result(List.of(), List.of());
    }

    /**
     * @param originalPom pom.xml content before migration, or {@code null}/blank if unavailable.
     * @param migratedPom pom.xml content after migration, or {@code null}/blank if unavailable.
     * @return added/removed artifactIds, or {@link Result#EMPTY} when either side can't be parsed.
     */
    public Result diff(String originalPom, String migratedPom) {
        if (originalPom == null || originalPom.isBlank() || migratedPom == null || migratedPom.isBlank()) {
            return Result.EMPTY;
        }
        Document origDoc = parseOrNull(originalPom);
        Document migDoc = parseOrNull(migratedPom);
        if (origDoc == null || migDoc == null) return Result.EMPTY;

        Set<String> originalArtifacts = collectArtifactIds(origDoc);
        Set<String> migratedArtifacts = collectArtifactIds(migDoc);

        List<String> added = migratedArtifacts.stream()
                .filter(a -> !originalArtifacts.contains(a))
                .sorted()
                .toList();
        List<String> removed = originalArtifacts.stream()
                .filter(a -> !migratedArtifacts.contains(a))
                .sorted()
                .toList();
        return new Result(added, removed);
    }

    private static Document parseOrNull(String content) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            dbf.setValidating(false);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setExpandEntityReferences(false);
            return dbf.newDocumentBuilder().parse(new InputSource(new StringReader(content)));
        } catch (Exception e) {
            return null;
        }
    }

    private static Set<String> collectArtifactIds(Document doc) {
        Set<String> ids = new LinkedHashSet<>();
        NodeList list = doc.getElementsByTagName("dependency");
        for (int i = 0; i < list.getLength(); i++) {
            Element dep = (Element) list.item(i);
            String aid = textOfChild(dep, "artifactId");
            if (aid != null && !aid.isBlank()) ids.add(aid);
        }
        return ids;
    }

    private static String textOfChild(Element parent, String tag) {
        NodeList children = parent.getElementsByTagName(tag);
        if (children.getLength() == 0) return null;
        String text = children.item(0).getTextContent();
        return text == null ? null : text.trim();
    }
}
