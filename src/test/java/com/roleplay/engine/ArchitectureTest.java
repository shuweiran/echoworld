package com.roleplay.engine;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Guards the dependencies that keep spatial rules independent from web adapters.
 * Add a narrow rule here before extracting a new module; do not use this test to
 * force a speculative package rewrite.
 */
@AnalyzeClasses(packages = "com.roleplay.engine", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule spatialRulesDoNotDependOnWebControllers = noClasses()
            .that().resideInAnyPackage(
                    "..simulation.track..",
                    "..simulation.movement..",
                    "..simulation.map..")
            .should().dependOnClassesThat().resideInAnyPackage("..controller..");

    @ArchTest
    static final ArchRule coreModelDoesNotDependOnWebControllers = noClasses()
            .that().resideInAnyPackage("..core..", "..agent..")
            .should().dependOnClassesThat().resideInAnyPackage("..controller..");

    @ArchTest
    static final ArchRule movementDoesNotPlanRoutes = noClasses()
            .that().haveSimpleName("MovementSystem")
            .should().dependOnClassesThat().resideInAnyPackage("..simulation.navigation..");

    @ArchTest
    static final ArchRule worldKernelDoesNotDependOnWebControllers = noClasses()
            .that().resideInAnyPackage(
                    "..simulation.navigation..",
                    "..simulation.spatial..",
                    "..simulation.action..",
                    "..simulation.worldobject..")
            .should().dependOnClassesThat().resideInAnyPackage("..controller..");

    @ArchTest
    static final ArchRule v2DomainModulesDoNotDependOnSpringOrAdapters = noClasses()
            .that().resideInAnyPackage(
                    "..simulation.agentruntime..",
                    "..simulation.worlddefinition..",
                    "..simulation.replication..",
                    "..simulation.persistence..",
                    "..simulation.observability..",
                    "..simulation.navigation.portal..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..controller..",
                    "..service..",
                    "org.springframework..",
                    "jakarta.persistence..",
                    "javax.persistence..");
}
