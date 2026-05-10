package com.altrix.orchestrator.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Verifies that no class outside the persistence adapter package accesses JPA
 * entities or repositories directly (#167).
 * <p>
 * The rule prevents controllers, domain services, and Kafka listeners from
 * bypassing the repository abstraction and issuing raw JPA queries.
 */
@AnalyzeClasses(
        packages = "com.altrix.orchestrator",
        importOptions = ImportOption.DoNotIncludeTests.class)
class LayerAccessTest {

    @ArchTest
    static final ArchRule only_persistence_adapters_may_access_jpa_repositories =
            noClasses().that().resideOutsideOfPackage("..adapter.out.persistence..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("..adapter.out.persistence..")
                    .andShould().haveSimpleNameEndingWith("JpaRepository")
                    .because("JPA repositories must only be accessed from the persistence adapter");

    @ArchTest
    static final ArchRule domain_must_not_use_lombok_data_on_entities =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("jakarta.persistence..")
                    .because("domain classes must not use JPA annotations directly");

    @ArchTest
    static final ArchRule kafka_listeners_must_not_access_persistence_directly =
            noClasses().that().resideInAPackage("..adapter.in.kafka..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("..adapter.out.persistence..")
                    .because("Kafka listeners must go through domain use-cases, not JPA directly");
}
