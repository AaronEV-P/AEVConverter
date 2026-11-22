package com.aaronev.aevconverter.io;

import com.aaronev.aevconverter.model.ImageFormat;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * Reads and writes images using the ImageIO API. Handles JPEG's lack of alpha-channel support by filling transparent
 * areas with a white background. Automatically prepares ICO images by scaling them down to a maximum of 256 pixels per
 * dimension and converting them to ARGB. Provides detailed diagnostics when a registered writer cannot encode an image.
 */
public final class ImageFormatIO {

    private static final Logger LOG = Logger.getLogger(ImageFormatIO.class.getName());
    private static final int MAX_ICO_DIMENSION = 256;

    private ImageFormatIO() {
    }

    /**
     * Reads an image using the ImageIO reader registered for its format.
     *
     * @param file the source image file
     * @return the decoded image
     * @throws IOException if the image cannot be read or no suitable reader is available
     */
    public static BufferedImage readImage(File file) throws IOException {
        Objects.requireNonNull(file, "file must not be null");

        BufferedImage image = ImageIO.read(file);
        if (image == null) {
            throw new IOException(
                    "Unsupported image format or no suitable ImageIO reader found: " + file.getAbsolutePath());
        }

        return image;
    }

    /**
     * Writes an image using the ImageIO writer registered for the destination format. Transparent areas are filled with
     * white when writing JPEG images. ICO images are automatically scaled down to fit within 256 by 256 pixels while
     * preserving their aspect ratio, and converted to a standard ARGB layout.
     *
     * <p>
     * The method checks both whether a writer is registered for the format and whether a writer can encode the
     * particular image type. For ICO, the registered writer is selected by format name because the TwelveMonkeys ICO
     * provider rejects standard BufferedImage types during SPI compatibility filtering. Any actual encoding failure is
     * then reported by the writer with diagnostic details about the image.</p>
     *
     * @param image the source image
     * @param destinationFormat the destination image format
     * @param destination the output file
     * @return {@code true} if an image was written; {@code false} if no writer is registered for the format
     * @throws IOException if no registered writer can encode the image, an output stream cannot be created, or writing
     * fails
     */
    public static boolean writeImage(
            BufferedImage image,
            ImageFormat destinationFormat,
            File destination) throws IOException {

        Objects.requireNonNull(image, "image must not be null");
        Objects.requireNonNull(destinationFormat, "destinationFormat must not be null");
        Objects.requireNonNull(destination, "destination must not be null");

        BufferedImage imageToWrite = image;

        try {
            if (destinationFormat == ImageFormat.JPG && image.getColorModel().hasAlpha()) {
                imageToWrite = createRgbImage(image);
            } else if (destinationFormat == ImageFormat.ICO) {
                // The ICO-specific image preparation is centralized here for all callers.
                imageToWrite = prepareIcoImage(image);
            }

            String formatName = destinationFormat.getExtension();
            List<String> registeredWriters = getRegisteredWriterClassNames(formatName);

            if (registeredWriters.isEmpty()) {
                LOG.log(Level.WARNING, "No ImageIO writer is registered for format ''{0}''. Check the plugin dependency and service/module configuration.", formatName);
                return false;
            }

            Iterator<ImageWriter> compatibleWriters;
            if (destinationFormat == ImageFormat.ICO) {
                // The ICO provider is registered, but its SPI canEncodeImage check rejects standard image types.
                // Select it by format so its writer implementation can attempt the actual encoding.
                compatibleWriters = ImageIO.getImageWritersByFormatName(formatName);
            } else {
                ImageTypeSpecifier imageType = ImageTypeSpecifier.createFromRenderedImage(imageToWrite);
                compatibleWriters = ImageIO.getImageWriters(imageType, formatName);
            }

            if (!compatibleWriters.hasNext()) {
                String message = destinationFormat == ImageFormat.ICO
                        ? "No ImageIO writer instance is available for registered format '" + formatName + "'."
                        : "ImageIO writer(s) are registered for format '" + formatName
                        + "', but none can encode this image. Registered writer(s): "
                        + String.join(", ", registeredWriters);
                throw new IOException(message
                        + ". Image details: " + describeImage(imageToWrite)
                        + ". Destination: " + destination.getAbsolutePath());
            }

            ImageWriter writer = compatibleWriters.next();
            disposeRemainingWriters(compatibleWriters);
            String writerClassName = writer.getClass().getName();

            try {
                Optional<ImageOutputStream> optionalOutput = Optional.ofNullable(
                        ImageIO.createImageOutputStream(destination));
                if (optionalOutput.isEmpty()) {
                    throw new IOException(
                            "ImageIO could not create an output stream for: " + destination.getAbsolutePath());
                }

                try (ImageOutputStream output = optionalOutput.orElseThrow()) {
                    writer.setOutput(output);
                    ImageWriteParam writeParam = writer.getDefaultWriteParam();
                    configureWriteParam(writeParam, destinationFormat);
                    writer.write(null, new IIOImage(imageToWrite, null, null), writeParam);
                    output.flush();
                }

                long outputSize = destination.length();
                if (outputSize <= 0) {
                    throw new IOException("The writer completed without producing a non-empty output file.");
                }

                LOG.log(Level.FINE, "Image written successfully. Format=''{0}'', writer={1}, bytes={2}, {3}, destination={4}", new Object[]{formatName, writerClassName, outputSize, describeImage(imageToWrite), destination.getAbsolutePath()});
                return true;
            } catch (IOException | RuntimeException exception) {
                throw new IOException(
                        "ImageIO writer '" + writerClassName + "' failed to write format '" + formatName
                        + "'. Image details: " + describeImage(imageToWrite)
                        + ". Destination: " + destination.getAbsolutePath()
                        + ". Cause: " + exception.getMessage(),
                        exception);
            } finally {
                writer.dispose();
            }
        } finally {
            if (imageToWrite != image) {
                imageToWrite.flush();
            }
        }
    }

    /**
     * Configures format-specific write parameters. TwelveMonkeys' ICO writer reads the compression type directly, so
     * its parameter must use explicit compression mode and a supported type.
     */
    private static void configureWriteParam(ImageWriteParam writeParam, ImageFormat format) throws IOException {
        if (format != ImageFormat.ICO) {
            return;
        }

        if (!writeParam.canWriteCompressed()) {
            throw new IOException("The registered ICO writer does not expose compression settings.");
        }

        writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        String[] compressionTypes = writeParam.getCompressionTypes();

        if (compressionTypes == null || compressionTypes.length == 0) {
            // Some writers have no named compression type; explicit mode still satisfies the writer's API contract.
            return;
        }

        String selectedType = findCompressionType(compressionTypes, "PNG")
                .or(() -> findCompressionType(compressionTypes, "RGB"))
                .orElse(compressionTypes[0]);
        writeParam.setCompressionType(selectedType);
        LOG.fine("Configured ICO compression type: " + selectedType);
    }

    private static Optional<String> findCompressionType(String[] compressionTypes, String keyword) {
        String normalizedKeyword = keyword.toUpperCase(java.util.Locale.ROOT);
        for (String compressionType : compressionTypes) {
            if (compressionType.toUpperCase(java.util.Locale.ROOT).contains(normalizedKeyword)) {
                return Optional.of(compressionType);
            }
        }
        return Optional.empty();
    }

    /**
     * Converts an image to RGB before writing JPEG, filling transparent pixels with white.
     */
    private static BufferedImage createRgbImage(BufferedImage source) {
        BufferedImage rgbImage = new BufferedImage(
                source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        boolean completed = false;
        Graphics2D graphics = rgbImage.createGraphics();

        try {
            graphics.drawImage(source, 0, 0, Color.WHITE, null);
            completed = true;
            return rgbImage;
        } finally {
            graphics.dispose();
            if (!completed) {
                rgbImage.flush();
            }
        }
    }

    /**
     * Prepares an image for ICO writing by scaling it down to at most 256 pixels per dimension and ensuring it uses a
     * standard ARGB pixel layout. The aspect ratio is preserved; the image is not cropped or padded.
     */
    private static BufferedImage prepareIcoImage(BufferedImage source) {
        BufferedImage scaledImage = ImageScaler.scaleDownToFit(
                source, MAX_ICO_DIMENSION, MAX_ICO_DIMENSION);

        if (scaledImage.getType() == BufferedImage.TYPE_INT_ARGB) {
            return scaledImage;
        }

        BufferedImage argbImage = new BufferedImage(
                scaledImage.getWidth(), scaledImage.getHeight(), BufferedImage.TYPE_INT_ARGB);
        boolean completed = false;
        Graphics2D graphics = argbImage.createGraphics();

        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(scaledImage, 0, 0, null);
            completed = true;
            return argbImage;
        } finally {
            graphics.dispose();
            if (scaledImage != source) {
                scaledImage.flush();
            }
            if (!completed) {
                argbImage.flush();
            }
        }
    }

    /**
     * Checks whether at least one ImageIO writer is registered for the given format. This does not guarantee that a
     * writer can encode every image type or size for that format.
     *
     * @param format the image format to check
     * @return {@code true} if at least one writer is registered, {@code false} otherwise
     */
    public static boolean canWrite(ImageFormat format) {
        Objects.requireNonNull(format, "format must not be null");

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(format.getExtension());
        boolean available = writers.hasNext();
        while (writers.hasNext()) {
            writers.next().dispose();
        }
        return available;
    }

    private static List<String> getRegisteredWriterClassNames(String formatName) {
        List<String> classNames = new ArrayList<>();
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(formatName);

        while (writers.hasNext()) {
            ImageWriter writer = writers.next();
            try {
                classNames.add(writer.getClass().getName());
            } finally {
                writer.dispose();
            }
        }

        return List.copyOf(classNames);
    }

    private static void disposeRemainingWriters(Iterator<ImageWriter> writers) {
        while (writers.hasNext()) {
            writers.next().dispose();
        }
    }

    private static String describeImage(BufferedImage image) {
        return image.getWidth() + "x" + image.getHeight()
                + ", bufferedImageType=" + imageTypeName(image.getType())
                + " (" + image.getType() + ")"
                + ", colorModel=" + image.getColorModel().getClass().getName()
                + ", hasAlpha=" + image.getColorModel().hasAlpha()
                + ", transferType=" + image.getRaster().getTransferType();
    }

    private static String imageTypeName(int type) {
        return switch (type) {
            case BufferedImage.TYPE_CUSTOM ->
                "TYPE_CUSTOM";
            case BufferedImage.TYPE_INT_RGB ->
                "TYPE_INT_RGB";
            case BufferedImage.TYPE_INT_ARGB ->
                "TYPE_INT_ARGB";
            case BufferedImage.TYPE_INT_ARGB_PRE ->
                "TYPE_INT_ARGB_PRE";
            case BufferedImage.TYPE_3BYTE_BGR ->
                "TYPE_3BYTE_BGR";
            case BufferedImage.TYPE_4BYTE_ABGR ->
                "TYPE_4BYTE_ABGR";
            case BufferedImage.TYPE_4BYTE_ABGR_PRE ->
                "TYPE_4BYTE_ABGR_PRE";
            case BufferedImage.TYPE_USHORT_565_RGB ->
                "TYPE_USHORT_565_RGB";
            case BufferedImage.TYPE_USHORT_555_RGB ->
                "TYPE_USHORT_555_RGB";
            case BufferedImage.TYPE_BYTE_GRAY ->
                "TYPE_BYTE_GRAY";
            case BufferedImage.TYPE_USHORT_GRAY ->
                "TYPE_USHORT_GRAY";
            case BufferedImage.TYPE_BYTE_BINARY ->
                "TYPE_BYTE_BINARY";
            case BufferedImage.TYPE_BYTE_INDEXED ->
                "TYPE_BYTE_INDEXED";
            default ->
                "UNKNOWN_TYPE";
        };
    }
}
