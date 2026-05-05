package com.altrix.orchestrator.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * Enforces naming conventions across all layers (#166).
 *
 * <ul>
 *   <li>{@code @Entity} classes must end with {@code Entity}.</li>
 *   <li>{@code JpaRepository} subtypes must end with {@code JpaRepository}.</li>
 *   <li>{@code @RestController} classes must end with {@code Controller}.</li>
 *   <li>Classes in {@code domain.service} must end with {@code Service}.</li>
 *   <li>Classes in {@code domain.port.out} that are interfaces must end with {@code Port}.</li>
 * </ul>
 */
@AnalyzeClasses(
        packages = "com.altrix.orchestrator",
        importOptions = ImportOption.DoNotIncludeTests.class)
class NamingConventionTest {

    @ArchTest
    static final ArchRule jpa_entities_end_with_Entity =
            classes().that().areAnnotatedWith(Entity.class)
                    .should().haveSimpleNameEndingWith("Entity")
                    .because("JPA entities must be named *Entity to distinguish them from domain models");

    @ArchTest
    static final ArchRule jpa_repositories_end_with_JpaRepository =
            classes().that().areAssignableTo(JpaRepository.class)
                    .and().areInterfaces()
                    .should().haveSimpleNameEndingWith("JpaRepository")
                    .because("Spring Data repositories must be named *JpaRepository");

    @ArchTest
    static final ArchRule rest_controllers_end_with_Controller =
            classes().that().areAnnotatedWith(RestController.class)
                    .should().haveSimpleNameEndingWith("Controller")
                    .because("REST controllers must be named *Controller");

    @ArchTest
    static final ArchRule domain_services_end_with_Service =
            classes().that().resideInAPackage("..domain.service..")
                    .and().areNotInterfaces()
                    .and().areTopLevelClasses()
                    .should().haveSimpleNameEndingWith("Service")
                    .because("domain service implementations must be named *Service");

    @ArchTest
    static final ArchRule outbound_ports_end_with_Port =
            classes().that().resideInAPackage("..domain.port.out..")
                    .and().areInterfaces()
                    .should().haveSimpleNameEndingWith("Port")
                    .because("driven ports must be named *Port");
}
