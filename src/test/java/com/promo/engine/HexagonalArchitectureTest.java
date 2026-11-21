package com.promo.engine;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class HexagonalArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.promo.engine");

    @Test
    void domainLayerMustHaveZeroFrameworkOrInfrastructureDependencies() {
        noClasses()
                .that().resideInAPackage("com.promo.engine.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "software.amazon..",
                        "com.fasterxml.jackson..",
                        "jakarta..",
                        "io.micrometer..",
                        "com.promo.engine.application..",
                        "com.promo.engine.adapters..",
                        "com.promo.engine.config.."
                )
                .check(CLASSES);
    }

    @Test
    void applicationLayerMustRemainIsolatedFromFrameworksAndAdapters() {
        noClasses()
                .that().resideInAPackage("com.promo.engine.application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "software.amazon..",
                        "com.fasterxml.jackson..",
                        "jakarta..",
                        "com.promo.engine.adapters..",
                        "com.promo.engine.config.."
                )
                .check(CLASSES);
    }
}
