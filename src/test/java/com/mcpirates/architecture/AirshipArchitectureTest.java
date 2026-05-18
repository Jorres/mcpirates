package com.mcpirates.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

/**
 * Boundary rules for the airship module. Each rule guards a specific refactor milestone:
 * once the milestone lands, its rule flips from {@code @Disabled} to enforced.
 *
 * <p>Test scope is narrow (no Minecraft types on the test classpath, see build.gradle),
 * so every type is referred to by string name — ArchUnit reads .class files directly.
 *
 * <p><b>Allowlist-as-contract.</b> Field rules ban {@code BlockPos} / {@code List} /
 * {@code Map} on {@link com.mcpirates.airship.Airship} with a small explicit allowlist of
 * legitimate non-hardware uses (see {@link #LEGITIMATE_FIELDS}). Adding any new field of
 * these types — regardless of naming — fails the rule, forcing a conscious decision
 * between "this belongs on Airship" (amend the allowlist) and "this belongs behind an
 * abstraction" (move it into {@code ShipLift} / {@code ShipControls} / {@code CombatBehavior}).
 *
 * <p>Diagnosed leaks today, each tied to a step that closes it:
 * <ul>
 *   <li>Hardware-address fields on {@code Airship}: {@code slThrottleLevers},
 *       {@code slBurnerPositions}, {@code slLeftClutchLever}, {@code slRightClutchLever},
 *       {@code slCannonMounts}, {@code cannoneerByMount}.</li>
 *   <li>{@code AirshipLiftoffTrigger}, {@code CaptainSpawner}
 *       call {@code AirshipKind} delta methods to resolve those positions.</li>
 *   <li>{@code AirshipBrain.register} calls {@code AirshipKind.makeControls(...)} itself
 *       instead of receiving a pre-built {@code ShipControls}.</li>
 * </ul>
 */
public class AirshipArchitectureTest {

    private static final String BLOCK_POS = "net.minecraft.core.BlockPos";
    private static final String AIRSHIP_KIND = "com.mcpirates.airship.interfaces.AirshipKind";

    /**
     * Fields on {@code Airship} that legitimately have type {@code BlockPos} /
     * {@code List} / {@code Map} and stay even after the full refactor. Anything not on
     * this list is either a hardware leak (must move into an abstraction) or a new
     * legitimate field that should be added here with justification.
     */
    private static final Set<String> LEGITIMATE_FIELDS = Set.of(
            "airpadAnchor",       // BlockPos — world identity, RETURN target, DefeatedAirships key
            "anchoredEntities"    // List<AnchoredEntity> — crew refs for isAnyCrewAlive
    );

    /** Phase C target fields (legitimate today, removed by Phase C). */
    private static final Set<String> PHASE_C_DEFERRED_FIELDS = Set.of(
            "slCannonMounts",     // List<BlockPos>
            "cannoneerByMount",   // Map<BlockPos, UUID>
            "slPrimaryAnchor"     // BlockPos — kept so MOORED→LIFTOFF promotion's CaptainSpawner
                                  // can resolve the SL-frame seat-scan bbox. Phase C: move into
                                  // an AssemblyMetadata wrapper carried by the SubLevel.
    );

    private static JavaClasses CLASSES;

    @BeforeAll
    static void importClasses() {
        CLASSES = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.mcpirates");
    }

    // ─── Enabled today ───────────────────────────────────────────────────

    /**
     * Sanity check: ArchUnit sees prod classes. Without this, a package rename could
     * silently empty the class set and every other rule would pass vacuously.
     */
    @Test
    void importsTheAirshipPackage() {
        long airshipClasses = CLASSES.stream()
                .filter(c -> c.getPackageName().startsWith("com.mcpirates.airship"))
                .count();
        if (airshipClasses < 10) {
            throw new AssertionError("ArchUnit imported " + airshipClasses
                    + " classes from com.mcpirates.airship — check the import path.");
        }
    }

    /**
     * Methods on {@link com.mcpirates.airship.interfaces.AirshipKind} are an API surface —
     * every method is a contract that 4 implementations have to honour and every caller
     * couples to. Each addition deserves explicit thought, not a reflexive "throw it on the
     * interface". This rule pins the count so adding (or removing) a method requires
     * updating {@link #EXPECTED_AIRSHIP_KIND_METHODS} alongside, which forces the
     * conversation.
     */
    private static final int EXPECTED_AIRSHIP_KIND_METHODS = 13;

    @Test
    void airshipKindInterfaceMethodCount() {
        var kind = CLASSES.stream()
                .filter(c -> c.getName().equals(AIRSHIP_KIND))
                .findFirst()
                .orElseThrow(() -> new AssertionError("AirshipKind not found in imported classes"));
        int actual = kind.getMethods().size();
        if (actual != EXPECTED_AIRSHIP_KIND_METHODS) {
            throw new AssertionError(
                    "AirshipKind has " + actual + " methods, expected "
                            + EXPECTED_AIRSHIP_KIND_METHODS
                            + ". If this fails, you may abort and consult whether we can modify the interface.");
        }
    }

    // ─── Enforced from Step 2 ────────────────────────────────────────────

    /**
     * AirshipBrain doesn't construct {@code ShipControls} — the kind builds it,
     * caller (trigger / rehydrator) assigns it onto {@code Airship}, brain receives
     * the fully-formed instance and just reads {@code a.controls} at tick time.
     */
    @Test
    void brainDoesNotConstructShipControls() {
        ArchRule rule = noClasses()
                .that().haveSimpleName("AirshipBrain")
                .should().callMethodWhere(callTarget(AIRSHIP_KIND, "makeControls"))
                .because("the kind builds controls; the brain receives them ready-made");
        rule.check(CLASSES);
    }

    // ─── Enforced from Step 6 ────────────────────────────────────────────

    /**
     * Airship has no {@code BlockPos} / {@code List} / {@code Map} field except the
     * allowlist {@link #LEGITIMATE_FIELDS} plus the Phase-C-deferred set. Lift and
     * steering hardware lives behind {@code ShipLift} / {@code ShipControls}.
     */
    @Test
    void airshipHasNoHardwareFields_exceptCannons() {
        ArchRule rule = noFields()
                .that().areDeclaredInClassesThat().haveSimpleName("Airship")
                .and(fieldNameNotIn(union(LEGITIMATE_FIELDS, PHASE_C_DEFERRED_FIELDS)))
                .should().haveRawType(BLOCK_POS)
                .orShould().haveRawType("java.util.List")
                .orShould().haveRawType("java.util.Map")
                .because("hardware addresses live behind ShipLift / ShipControls, not on Airship")
                .allowEmptyShould(true);
        rule.check(CLASSES);
    }

    // ─── Disabled, target Phase C ────────────────────────────────────────

    /**
     * Phase C target: cannon state ({@code slCannonMounts}, {@code cannoneerByMount})
     * moves into {@code CombatBehavior}. Only {@link #LEGITIMATE_FIELDS} survive.
     */
    @Test
    @Disabled("enabled after Phase C — cannon state moved into CombatBehavior")
    void airshipHasNoHardwareFields_cannonsToo() {
        ArchRule rule = noFields()
                .that().areDeclaredInClassesThat().haveSimpleName("Airship")
                .and(fieldNameNotIn(LEGITIMATE_FIELDS))
                .should().haveRawType(BLOCK_POS)
                .orShould().haveRawType("java.util.List")
                .orShould().haveRawType("java.util.Map")
                .because("after Phase C, Airship holds only state-machine + abstractions");
        rule.check(CLASSES);
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private static DescribedPredicate<JavaCall<?>> callTarget(String ownerFqn, String methodName) {
        return new DescribedPredicate<>("call to " + ownerFqn + "." + methodName) {
            @Override
            public boolean test(JavaCall<?> call) {
                return call.getTargetOwner().getName().equals(ownerFqn)
                        && call.getName().equals(methodName);
            }
        };
    }

    private static DescribedPredicate<com.tngtech.archunit.core.domain.JavaField>
            fieldNameNotIn(Set<String> allowedNames) {
        return new DescribedPredicate<>("name not in allowlist " + allowedNames) {
            @Override
            public boolean test(com.tngtech.archunit.core.domain.JavaField field) {
                return !allowedNames.contains(field.getName());
            }
        };
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        java.util.HashSet<String> out = new java.util.HashSet<>(a);
        out.addAll(b);
        return Set.copyOf(out);
    }
}
