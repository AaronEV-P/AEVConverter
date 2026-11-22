package com.aaronev.aevconverter.model;

import java.util.Optional;

/**
 * Image formats supported by the application.
 */
public enum ImageFormat {
    PNG("png", new String[]{"*.png"}, "PNG"),
    JPG("jpg", new String[]{"*.jpg", "*.jpeg"}, "JPG / JPEG"),
    WEBP("webp", new String[]{"*.webp"}, "WEBP"),
    ICO("ico", new String[]{"*.ico"}, "ICO");

    private final String extension;
    private final String[] filterPatterns;
    private final String extensionTag;

    private ImageFormat(String extension, String[] filterPatterns, String extensionTag) {
        this.extension = extension;
        this.filterPatterns = filterPatterns;
        this.extensionTag = extensionTag;
    }

    public String getExtension() {
        return extension;
    }

    public String[] getFilterPatterns() {
        return filterPatterns.clone();
    }

    public String getExtensionTag() {
        return extensionTag;
    }

    /**
     * Returns the image format matching the given file name's extension. Supports both "jpg" and "jpeg" as JPG. Returns
     * an empty Optional if the file name is null or the extension is unsupported.
     *
     * @param fileName the name of the file
     * @return an Optional containing the matching image format, or an empty Optional
     */
    public static Optional<ImageFormat> fromExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return Optional.empty();
        }

        String ext = extractExtension(fileName);

        if (ext.isBlank()) {
            return Optional.empty();
        }

        for (ImageFormat format : ImageFormat.values()) {
            if (format.extension.equalsIgnoreCase(ext)) {
                return Optional.of(format);
            }
        }

        if ("jpeg".equalsIgnoreCase(ext)) {
            return Optional.of(JPG);
        }

        return Optional.empty();
    }

    // Extracts the file extension from a file name.
    // Example: "picture.png" returns "png".
    private static String extractExtension(String fileName) {
        int index = fileName.lastIndexOf('.');

        if (index < 0 || index == fileName.length() - 1) {
            return "";
        }

        return fileName.substring(index + 1);
    }

    /**
     * Returns the display label shown in a ComboBox.
     *
     * @return String, the display label for this image format
     */
    @Override
    public String toString() {
        return extensionTag;
    }
}
