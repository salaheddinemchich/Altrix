package com.altrix.job.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Hexagonal + naming convention rules for platform-job (#164 #166 #167). */
@AnalyzeClasses(
        packages = "com.altrix.job",
        importOptions = ImportOption.DoNotIncludeTests.class)
class HexagonalArchitectureTest {

    @ArchTest
    static final ArchRule domain_must_not_depend_on_spring =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAPackage("org.springframework..")
                    .because("domain layer must be framework-free");

    @ArchTest
    static final ArchRule domain_must_not_depend_on_jpa =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAPackage("jakarta.persistence..")
                    .because("domain layer must be persistence-free");

    @ArchTest
    static final ArchRule domain_services_must_not_depend_on_adapters =
            noClasses().that().resideInAPackage("..domain.service..")
                    .should().dependOnClassesThat().resideInAPackage("..adapter..")
                    .because("domain services must only depend on domain ports");

    @ArchTest
    static final ArchRule inbound_adapters_must_not_access_persistence_directly =
            noClasses().that().resideInAPackage("..adapter.in..")
                    .should().dependOnClassesThat().resideInAPackage("..adapter.out.persistence..")
                    .because("REST/Kafka adapters must go through domain use-cases");

    @ArchTest
    static final ArchRule entities_end_with_Entity =
            classes().that().areAnnotatedWith(Entity.class)
                    .should().haveSimpleNameEndingWith("Entity");

    @ArchTest
    static final ArchRule repositories_end_with_JpaRepository =
            classes().that().areAssignableTo(JpaRepository.class).and().areInterfaces()
                    .should().haveSimpleNameEndingWith("JpaRepository");

    @ArchTest
    static final ArchRule controllers_end_with_Controller =
            classes().that().areAnnotatedWith(RestController.class)
                    .should().haveSimpleNameEndingWith("Controller");

    @ArchTest
    static final ArchRule domain_must_not_depend_on_jackson =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAPackage("com.fasterxml.jackson..")
                    .because("domain layer must not depend on serialisation frameworks");

    @ArchTest
    static final ArchRule outbound_ports_end_with_Port =
            classes().that().resideInAPackage("..domain.port.out..").and().areInterfaces()
                    .should().haveSimpleNameEndingWith("Port");
}
