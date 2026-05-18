package com.mcpirates.pirates.roles;

import net.minecraft.nbt.CompoundTag;

public final class PirateRoleCodec {

    private static final String KEY_ID = "id";
    private static final String KEY_NEXT_FIRE = "nextFireTick";
    private static final String ID_CANNONEER = "cannoneer";
    private static final String ID_CROSSBOWMAN = "crossbowman";

    private PirateRoleCodec() {}

    public static CompoundTag write(PirateRole role) {
        CompoundTag tag = new CompoundTag();
        if (role instanceof CannoneerRole) {
            tag.putString(KEY_ID, ID_CANNONEER);
        } else if (role instanceof CrossbowmanRole xb) {
            tag.putString(KEY_ID, ID_CROSSBOWMAN);
            tag.putLong(KEY_NEXT_FIRE, xb.nextFireTick());
        } else {
            throw new IllegalArgumentException("unknown PirateRole: " + role.getClass());
        }
        return tag;
    }

    public static PirateRole read(CompoundTag tag) {
        String id = tag.getString(KEY_ID);
        return switch (id) {
            case ID_CANNONEER -> CannoneerRole.INSTANCE;
            case ID_CROSSBOWMAN -> new CrossbowmanRole(tag.getLong(KEY_NEXT_FIRE));
            default -> throw new IllegalArgumentException("unknown PirateRole id: " + id);
        };
    }
}
