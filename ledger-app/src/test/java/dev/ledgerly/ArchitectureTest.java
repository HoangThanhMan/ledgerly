package dev.ledgerly;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.transaction.annotation.Transactional;

/**
 * Module boundaries of {@code ledger-app}, as described in docs/01-kien-truc.md §4. Each module is a top-level
 * package under {@code dev.ledgerly}. Its public API lives at the package root, everything under {@code internal} is
 * private to it.
 */
@AnalyzeClasses(packages = "dev.ledgerly", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String ROOT_PACKAGE = "dev.ledgerly.";

    /**
     * The modules each module may depend on, besides itself. {@code contracts} is the {@code ledger-contracts}
     * library: it shares the root package, so it is listed here and must stay free of application code. The modules
     * that build events ({@code wallet}) and the one that puts them on the wire ({@code outbox}) may use it.
     */
    private static final Map<String, Set<String>> ALLOWED_DEPENDENCIES = Map.of(
            "contracts", Set.of(),
            "shared", Set.of(),
            "ledger", Set.of("shared"),
            "idempotency", Set.of("shared"),
            "outbox", Set.of("shared", "contracts"),
            "bankgateway", Set.of("shared"),
            "wallet", Set.of("shared", "contracts", "ledger", "idempotency", "outbox"),
            "topup", Set.of("shared", "ledger", "idempotency", "outbox", "bankgateway"),
            "reconciliation", Set.of("shared", "ledger", "bankgateway", "topup"));

    @ArchTest
    static final ArchRule modulesAreFreeOfCycles =
            slices().matching("dev.ledgerly.(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule modulesDependOnlyOnAllowedModules = classes().should(dependOnlyOnAllowedModules());

    @ArchTest
    static final ArchRule internalsArePrivateToTheirModule = classes().should(notUseInternalsOfOtherModules());

    @ArchTest
    static final ArchRule domainIsFrameworkFree = noClasses()
            .that()
            .resideInAPackage("..internal.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "jakarta..", "java.sql..", "javax.sql..");

    @ArchTest
    static final ArchRule onlyApplicationClassesAreTransactional = noClasses()
            .that()
            .resideOutsideOfPackage("..internal.application..")
            .should()
            .beAnnotatedWith(Transactional.class)
            .orShould()
            .beAnnotatedWith("jakarta.transaction.Transactional");

    @ArchTest
    static final ArchRule onlyApplicationMethodsAreTransactional = noMethods()
            .that()
            .areDeclaredInClassesThat()
            .resideOutsideOfPackage("..internal.application..")
            .should()
            .beAnnotatedWith(Transactional.class)
            .orShould()
            .beAnnotatedWith("jakarta.transaction.Transactional");

    /** Money is a {@code long} in minor units (ADR-0003). Floating point cannot represent it exactly. */
    @ArchTest
    static final ArchRule domainHasNoFloatingPointFields = noFields()
            .that()
            .areDeclaredInClassesThat()
            .resideInAnyPackage("..internal.domain..", "dev.ledgerly.shared..")
            .should()
            .haveRawType(double.class)
            .orShould()
            .haveRawType(float.class)
            .orShould()
            .haveRawType(Double.class)
            .orShould()
            .haveRawType(Float.class);

    private static ArchCondition<JavaClass> dependOnlyOnAllowedModules() {
        return new ArchCondition<>("depend only on the modules the dependency matrix allows") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                Optional<String> module = moduleOf(origin);
                if (module.isEmpty()) {
                    return;
                }
                Set<String> allowed = ALLOWED_DEPENDENCIES.get(module.get());
                if (allowed == null) {
                    events.add(SimpleConditionEvent.violated(
                            origin,
                            "Module '" + module.get() + "' of " + origin.getName()
                                    + " is missing from the dependency matrix"));
                    return;
                }
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    Optional<String> target = moduleOf(dependency.getTargetClass());
                    if (target.isPresent() && !target.equals(module) && !allowed.contains(target.get())) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> notUseInternalsOfOtherModules() {
        return new ArchCondition<>("not use the internals of other modules") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                Optional<String> module = moduleOf(origin);
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    if (isInternal(target) && !moduleOf(target).equals(module)) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }

    /** The module a class belongs to: the first package segment under the root package, if any. */
    private static Optional<String> moduleOf(JavaClass javaClass) {
        String packageName = javaClass.getPackageName();
        if (!packageName.startsWith(ROOT_PACKAGE)) {
            return Optional.empty();
        }
        String[] segments = packageName.substring(ROOT_PACKAGE.length()).split("\\.", 2);
        return Optional.of(segments[0]);
    }

    private static boolean isInternal(JavaClass javaClass) {
        return moduleOf(javaClass).isPresent() && (javaClass.getPackageName() + ".").contains(".internal.");
    }
}
