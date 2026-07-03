package com.storycreator.image;

/**
 * Optional overrides for image generation parameters.
 */
public record ImageGenerationOptions(
    String promptOverride,
    Long imageConfigIdOverride,
    Integer widthOverride,
    Integer heightOverride
) {
    public static ImageGenerationOptions none() {
        return new ImageGenerationOptions(null, null, null, null);
    }
}
