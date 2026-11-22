package com.aaronev.aevconverter.io;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Objects;

/**
 * Resizes images and calculates dimensions while preserving aspect ratios. This class does not depend on JavaFX.
 */
public final class ImageScaler {

    private ImageScaler() {
    }

    /**
     * Resizes an image to the exact target dimensions without cropping. The image may be distorted if the target aspect
     * ratio differs from the original. Large reductions are performed in multiple steps to improve image quality.
     *
     * @param source the source image
     * @param targetWidth the target width in pixels
     * @param targetHeight the target height in pixels
     * @return the resized image, or the original instance if its dimensions already match
     * @throws NullPointerException if the source image is null
     * @throws IllegalArgumentException if either target dimension is not positive
     */
    public static BufferedImage scale(BufferedImage source, int targetWidth, int targetHeight) {

        Objects.requireNonNull(source, "Source image must not be null.");

        requirePositive("targetWidth", targetWidth);
        requirePositive("targetHeight", targetHeight);

        int currentWidth = source.getWidth();
        int currentHeight = source.getHeight();

        if (currentWidth == targetWidth && currentHeight == targetHeight) {
            return source;
        }

        int imageType = source.getColorModel().hasAlpha()
                ? BufferedImage.TYPE_INT_ARGB
                : BufferedImage.TYPE_INT_RGB;

        BufferedImage current = source;
        boolean currentIsIntermediate = false;
        boolean completed = false;

        try {
            while (currentWidth != targetWidth || currentHeight != targetHeight) {
                int nextWidth = nextStep(currentWidth, targetWidth);
                int nextHeight = nextStep(currentHeight, targetHeight);

                BufferedImage next = new BufferedImage(nextWidth, nextHeight, imageType);

                boolean nextCompleted = false;

                try {
                    Graphics2D graphics = next.createGraphics();

                    try {
                        graphics.setRenderingHint(
                                RenderingHints.KEY_INTERPOLATION,
                                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        graphics.setRenderingHint(
                                RenderingHints.KEY_RENDERING,
                                RenderingHints.VALUE_RENDER_QUALITY);

                        graphics.drawImage(
                                current, 0, 0, nextWidth, nextHeight, null);
                    } finally {
                        graphics.dispose();
                    }

                    nextCompleted = true;
                } finally {
                    if (!nextCompleted) {
                        next.flush();
                    }
                }

                if (currentIsIntermediate) {
                    current.flush();
                }

                current = next;
                currentIsIntermediate = true;
                currentWidth = nextWidth;
                currentHeight = nextHeight;
            }

            completed = true;
            return current;
        } finally {
            // If the operation failed and the current image is an intermediate image, release its resources.
            if (!completed && currentIsIntermediate) {
                current.flush();
            }
        }
    }

    /**
     * Scales an image down, if necessary, so that it fits within the specified bounds while preserving its aspect
     * ratio. This method never enlarges, crops, or pads the source image.
     *
     * @param source the source image
     * @param maxWidth maximum permitted width
     * @param maxHeight maximum permitted height
     * @return the scaled image, or the original instance if it already fits
     * @throws NullPointerException if the source image is null
     * @throws IllegalArgumentException if either maximum dimension is not positive
     */
    public static BufferedImage scaleDownToFit(
            BufferedImage source, int maxWidth, int maxHeight) {

        Objects.requireNonNull(source, "Source image must not be null.");
        requirePositive("maxWidth", maxWidth);
        requirePositive("maxHeight", maxHeight);

        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        double scaleFactor = Math.min(
                1.0,
                Math.min(maxWidth / (double) sourceWidth, maxHeight / (double) sourceHeight));

        int targetWidth = Math.min(
                maxWidth, roundedDimension(sourceWidth * scaleFactor));
        int targetHeight = Math.min(
                maxHeight, roundedDimension(sourceHeight * scaleFactor));

        return scale(source, targetWidth, targetHeight);
    }

    /**
     * Determines the next resizing step. If the image is more than twice the target size, it is reduced by half.
     * Otherwise, it is resized directly to the target dimension.
     */
    private static int nextStep(int current, int target) {
        return current > 2L * target ? current / 2 : target;
    }

    /**
     * Calculates the largest dimensions that fit within the specified bounds while preserving the original aspect
     * ratio. The image may be enlarged if it is smaller than the specified bounds.
     *
     * @param originalWidth the original image width
     * @param originalHeight the original image height
     * @param maxWidth the maximum allowed width
     * @param maxHeight the maximum allowed height
     * @return the calculated dimensions
     * @throws IllegalArgumentException if any dimension is not positive
     */
    public static Dimension fitDimensions(
            int originalWidth, int originalHeight,
            int maxWidth, int maxHeight) {

        requirePositive("originalWidth", originalWidth);
        requirePositive("originalHeight", originalHeight);
        requirePositive("maxWidth", maxWidth);
        requirePositive("maxHeight", maxHeight);

        double scale = Math.min(
                maxWidth / (double) originalWidth,
                maxHeight / (double) originalHeight);

        return new Dimension(
                roundedDimension(originalWidth * scale),
                roundedDimension(originalHeight * scale));
    }

    /**
     * Calculates the height corresponding to a given width while preserving the original aspect ratio.
     *
     * @param width the target width
     * @param originalWidth the original image width
     * @param originalHeight the original image height
     * @return the calculated height, with a minimum of one pixel
     * @throws IllegalArgumentException if any input dimension is not positive or the result exceeds the supported range
     */
    public static int calculateProportionalHeight(
            int width, int originalWidth, int originalHeight) {

        requirePositive("width", width);
        requirePositive("originalWidth", originalWidth);
        requirePositive("originalHeight", originalHeight);

        double height = width * (double) originalHeight / originalWidth;
        return roundedDimension(height);
    }

    /**
     * Calculates the width corresponding to a given height while preserving the original aspect ratio.
     *
     * @param height the target height
     * @param originalWidth the original image width
     * @param originalHeight the original image height
     * @return the calculated width, with a minimum of one pixel
     * @throws IllegalArgumentException if any input dimension is not positive or the result exceeds the supported range
     */
    public static int calculateProportionalWidth(
            int height, int originalWidth, int originalHeight) {

        requirePositive("height", height);
        requirePositive("originalWidth", originalWidth);
        requirePositive("originalHeight", originalHeight);

        double width = height * (double) originalWidth / originalHeight;
        return roundedDimension(width);
    }

    /**
     * Validates that a dimension is greater than zero.
     */
    private static void requirePositive(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(
                    name + " must be greater than zero: " + value);
        }
    }

    /**
     * Rounds a calculated dimension and verifies that it fits in an integer.
     */
    private static int roundedDimension(double value) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(
                    "The calculated dimension is invalid: " + value);
        }

        long rounded = Math.round(value);

        if (rounded > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "The calculated dimension exceeds the supported range.");
        }

        return (int) Math.max(1L, rounded);
    }
}
