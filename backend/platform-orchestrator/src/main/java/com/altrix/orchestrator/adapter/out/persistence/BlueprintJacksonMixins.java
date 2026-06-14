package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.orchestrator.domain.model.blueprint.BlueprintFile;
import com.altrix.orchestrator.domain.model.blueprint.ClassNode;
import com.altrix.orchestrator.domain.model.blueprint.MethodNode;
import com.altrix.orchestrator.domain.model.blueprint.ProjectBlueprint;
import com.altrix.orchestrator.domain.model.blueprint.SemanticGraph;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Jackson mix-ins that keep the {@code domain.model.blueprint} records
 * serialisation-framework-free.
 *
 * <p>The blueprint records expose computed convenience accessors
 * (e.g. {@link BlueprintFile#isPassThrough()},
 * {@link MethodNode#signature()}) that Jackson would otherwise serialise as
 * phantom JSON properties — which then fail to deserialise because records
 * have no canonical-constructor parameter for them.  Rather than annotate
 * the domain records with {@code @JsonIgnore} (which would leak Jackson into
 * the domain layer and break the hexagonal architecture rule), the
 * "ignore these accessors" instruction lives here, in the persistence
 * adapter, and is attached via {@link ObjectMapper#addMixIn}.
 *
 * <p>Mix-in methods are matched to the target by name + parameter types;
 * the abstract declarations below carry only the {@code @JsonIgnore} that
 * Jackson merges onto the matching record accessor.
 */
final class BlueprintJacksonMixins {

    private BlueprintJacksonMixins() {}

    /** Attaches every blueprint mix-in to {@code mapper}. */
    static void register(ObjectMapper mapper) {
        mapper.addMixIn(ProjectBlueprint.class, ProjectBlueprintMixin.class);
        mapper.addMixIn(BlueprintFile.class, BlueprintFileMixin.class);
        mapper.addMixIn(MethodNode.class, MethodNodeMixin.class);
        mapper.addMixIn(SemanticGraph.class, SemanticGraphMixin.class);
    }

    abstract static class ProjectBlueprintMixin {
        @JsonIgnore abstract long migrationRelevantFileCount();
    }

    abstract static class BlueprintFileMixin {
        @JsonIgnore abstract boolean isPassThrough();
    }

    abstract static class MethodNodeMixin {
        @JsonIgnore abstract String signature();
        @JsonIgnore abstract String key();
    }

    abstract static class SemanticGraphMixin {
        @JsonIgnore abstract Map<String, List<ClassNode>> classesByFile();
    }
}
