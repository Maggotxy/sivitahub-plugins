package cn.originalrpg.hub;

/** Pure angle policy used to suppress redundant mannequin metadata updates. */
final class HubNpcFacingPolicy {
    static final float MINIMUM_CHANGE_DEGREES = 2.0F;

    private HubNpcFacingPolicy() {
    }

    static boolean shouldRotate(float currentYaw, float currentPitch, float targetYaw, float targetPitch) {
        if (!Float.isFinite(targetYaw) || !Float.isFinite(targetPitch)) return false;
        if (!Float.isFinite(currentYaw) || !Float.isFinite(currentPitch)) return true;
        return angularDistance(currentYaw, targetYaw) >= MINIMUM_CHANGE_DEGREES
                || Math.abs(currentPitch - targetPitch) >= MINIMUM_CHANGE_DEGREES;
    }

    static float angularDistance(float left, float right) {
        float difference = (right - left) % 360.0F;
        if (difference > 180.0F) difference -= 360.0F;
        if (difference < -180.0F) difference += 360.0F;
        return Math.abs(difference);
    }
}
