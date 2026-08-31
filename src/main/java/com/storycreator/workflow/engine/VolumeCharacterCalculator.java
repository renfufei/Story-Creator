package com.storycreator.workflow.engine;

/**
 * Calculates how many characters to introduce per volume using cumulative floor logic.
 */
public final class VolumeCharacterCalculator {

    private VolumeCharacterCalculator() {}

    /**
     * Calculate how many characters should be introduced for the given volume.
     *
     * @param volumeNumber   current volume (1-based)
     * @param totalVolumes   total number of volumes
     * @param rate           introduction rate (e.g., 0.5 means 1 character every 2 volumes)
     * @param alreadyIntroduced number of characters of this type already introduced in prior volumes
     * @param isRecurring    true for recurring characters (restricted in first/last volume)
     * @return number of characters to introduce (>= 0)
     */
    public static int calculateForVolume(int volumeNumber, int totalVolumes, double rate,
                                         int alreadyIntroduced, boolean isRecurring) {
        // Recurring characters: not introduced in first or last volume
        if (isRecurring && (volumeNumber == 1 || volumeNumber == totalVolumes)) {
            return 0;
        }
        int cumulative = (int) Math.floor(volumeNumber * rate);
        return Math.max(0, cumulative - alreadyIntroduced);
    }
}
