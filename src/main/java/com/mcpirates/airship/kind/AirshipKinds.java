package com.mcpirates.airship.kind;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Registry of all known {@link AirshipKind}s, indexed by name.
 *
 * <p>The lift-off trigger no longer iterates kinds and runs geometric predicates. Each
 * ship's structure NBT carries a {@link com.mcpirates.airship.anchor.MCPShipAnchorBlock}
 * whose BE stores the kind name directly; {@link #byName} resolves that to the kind
 * instance and the trigger proceeds from there.
 */
public final class AirshipKinds {

    public static final List<AirshipKind> ALL = List.of(
            AirshipSmallKind.INSTANCE,
            CrossbowBoardKind.INSTANCE,
            GalleonKind.INSTANCE,
            RamshipKind.INSTANCE
    );

    private static final Map<String, AirshipKind> BY_NAME =
            ALL.stream().collect(Collectors.toUnmodifiableMap(AirshipKind::name, k -> k));

    /** Look up a kind by its {@link AirshipKind#name name} — e.g. {@code "airship_small"}.
     *  Returns null on miss (unknown name, e.g. an old structure from before a rename). */
    public static AirshipKind byName(String name) {
        return BY_NAME.get(name);
    }

    private AirshipKinds() {}
}
