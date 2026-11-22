package com.aaronev.aevconverter.ui.resize;

import com.aaronev.aevconverter.io.ImageFormatIO;
import com.aaronev.aevconverter.io.ImageScaler;
import com.aaronev.aevconverter.model.ImageFormat;
import com.aaronev.aevconverter.ui.Dialogs;
import com.aaronev.aevconverter.ui.Resources;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.ResourceBundle;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * Controller for resizing a single image without cropping it.
 */
public class ResizeImageController implements Initializable {

    private static final Logger LOG = Logger.getLogger(ResizeImageController.class.getName());

    private static final int MAX_DIMENSION = 99_999;
    private static final long MAX_OUTPUT_PIXELS = 40_000_000L;
    private static final int MAX_PREVIEW_WIDTH = 1_600;
    private static final int MAX_PREVIEW_HEIGHT = 1_200;
    private static final int PREVIEW_MARGIN = 32;

    @FXML
    private Button loadImageButton;
    @FXML
    private Label statusLabel;
    @FXML
    private StackPane previewContainer;
    @FXML
    private ImageView imagePreview;
    @FXML
    private Label originalDimensionsLabel;
    @FXML
    private TextField widthField;
    @FXML
    private TextField heightField;
    @FXML
    private CheckBox keepAspectRatioCheckBox;
    @FXML
    private Button resizeButton;

    private Optional<LoadedImage> loadedImage = Optional.empty();
    private boolean updatingDimensions;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        imagePreview.fitWidthProperty().bind(
                previewContainer.widthProperty().subtract(PREVIEW_MARGIN));
        imagePreview.fitHeightProperty().bind(
                previewContainer.heightProperty().subtract(PREVIEW_MARGIN));

        UnaryOperator<TextFormatter.Change> dimensionFilter = change -> {
            String newText = change.getControlNewText();
            // JavaFX requires a null return value to reject an edit in a TextFormatter filter.
            return newText.isEmpty() || newText.matches("\\d{1," + Integer.toString(MAX_DIMENSION).length() + "}")
                    ? change
                    : null;
        };
        widthField.setTextFormatter(new TextFormatter<>(dimensionFilter));
        heightField.setTextFormatter(new TextFormatter<>(dimensionFilter));

        widthField.textProperty().addListener((observable, oldValue, newValue) -> recalculateHeight());
        heightField.textProperty().addListener((observable, oldValue, newValue) -> recalculateWidth());
        keepAspectRatioCheckBox.selectedProperty().addListener((observable, oldValue, selected) -> {
            if (selected) {
                recalculateHeight();
            }
        });
    }

    // -------------------- Aspect ratio --------------------
    private void recalculateHeight() {
        if (updatingDimensions || !keepAspectRatioCheckBox.isSelected() || loadedImage.isEmpty()) {
            return;
        }

        OptionalInt width = parseDimension(widthField.getText());
        if (width.isEmpty()) {
            return;
        }

        LoadedImage image = loadedImage.orElseThrow();
        try {
            int height = ImageScaler.calculateProportionalHeight(
                    width.getAsInt(), image.originalWidth(), image.originalHeight());
            updateCalculatedDimension(heightField, height);
        } catch (IllegalArgumentException exception) {
            showDimensionLimitStatus();
        }
    }

    private void recalculateWidth() {
        if (updatingDimensions || !keepAspectRatioCheckBox.isSelected() || loadedImage.isEmpty()) {
            return;
        }

        OptionalInt height = parseDimension(heightField.getText());
        if (height.isEmpty()) {
            return;
        }

        LoadedImage image = loadedImage.orElseThrow();
        try {
            int width = ImageScaler.calculateProportionalWidth(
                    height.getAsInt(), image.originalWidth(), image.originalHeight());
            updateCalculatedDimension(widthField, width);
        } catch (IllegalArgumentException exception) {
            showDimensionLimitStatus();
        }
    }

    private void updateCalculatedDimension(TextField field, int dimension) {
        if (dimension > MAX_DIMENSION) {
            showDimensionLimitStatus();
            return;
        }

        updatingDimensions = true;
        try {
            field.setText(Integer.toString(dimension));
        } finally {
            updatingDimensions = false;
        }
        loadedImage.ifPresent(image -> setStatus("Imagen cargada: " + image.file().getName(), true));
    }

    private void showDimensionLimitStatus() {
        statusLabel.getStyleClass().removeAll("status-ok", "status-error");
        statusLabel.getStyleClass().add("status-error");
        statusLabel.setText("La dimensión calculada supera el máximo de " + MAX_DIMENSION + " px.");
    }

    private static OptionalInt parseDimension(String text) {
        if (text.isBlank()) {
            return OptionalInt.empty();
        }
        try {
            int value = Integer.parseInt(text.trim());
            return value > 0 ? OptionalInt.of(value) : OptionalInt.empty();
        } catch (NumberFormatException exception) {
            return OptionalInt.empty();
        }
    }

    // -------------------- Image loading --------------------
    @FXML
    private void loadImage() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Seleccionar imagen");

        FileChooser.ExtensionFilter imageFilter
                = new FileChooser.ExtensionFilter(
                        "JPG/JPEG, WEBP, PNG",
                        "*.jpg", "*.jpeg", "*.webp", "*.png");

        fileChooser.getExtensionFilters().setAll(imageFilter);
        fileChooser.setSelectedExtensionFilter(imageFilter);

        Window owner = loadImageButton.getScene().getWindow();

        Optional.ofNullable(fileChooser.showOpenDialog(owner))
                .ifPresent(this::loadSelectedImage);
    }
    
//    
//    private static List<String> getAllSupportedFilterPatterns() {
//        List<String> patterns = new ArrayList<>();
//
//        for (ImageFormat format : ImageFormat.values()) {
//            if (format != ImageFormat.ICO) {
//                patterns.addAll(Arrays.asList(format.getFilterPatterns()));
//            }
//        }
//
//        return patterns.stream().distinct().toList();
//    }

    private void loadSelectedImage(File file) {
        Optional<ImageFormat> detectedFormat
                = ImageFormat.fromExtension(file.getName());

        if (detectedFormat.isEmpty()) {
            Dialogs.error("Formato de archivo no reconocido.");
            return;
        }

        ImageFormat format = detectedFormat.orElseThrow();

        // ICO images are not supported by the resize feature.
        if (format == ImageFormat.ICO) {
            Dialogs.error(
                    "El formato ICO no está admitido. Selecciona una imagen JPG/JPEG, WEBP o PNG.");
            return;
        }

        try {
            BufferedImage decodedImage = ImageFormatIO.readImage(file);
            int originalWidth = decodedImage.getWidth();
            int originalHeight = decodedImage.getHeight();
            WritableImage preview = createPreview(decodedImage);

            loadedImage = Optional.of(
                    new LoadedImage(file, format, originalWidth, originalHeight));

            imagePreview.setImage(preview);
            originalDimensionsLabel.setText(
                    "Dimensiones originales: "
                    + originalWidth + " x " + originalHeight + " px");

            Dimension initialDimensions
                    = fitInitialDimensions(originalWidth, originalHeight);

            setDimensionFields(
                    initialDimensions.width, initialDimensions.height);

            setStatus("Imagen cargada: " + file.getName(), true);

            widthField.setDisable(false);
            heightField.setDisable(false);
            resizeButton.setDisable(false);

        } catch (IOException | RuntimeException exception) {
            LOG.log(Level.WARNING, "Failed to load image: " + file, exception);
            Dialogs.error(
                    "Error al leer la imagen: " + getErrorMessage(exception));
        }
    }

    /**
     * Creates a preview copy and releases the decoded AWT image after conversion.
     */
    private static WritableImage createPreview(BufferedImage sourceImage) {
        BufferedImage previewSource = sourceImage;
        try {
            if (sourceImage.getWidth() > MAX_PREVIEW_WIDTH || sourceImage.getHeight() > MAX_PREVIEW_HEIGHT) {
                Dimension previewDimensions = ImageScaler.fitDimensions(
                        sourceImage.getWidth(), sourceImage.getHeight(),
                        MAX_PREVIEW_WIDTH, MAX_PREVIEW_HEIGHT);
                previewSource = ImageScaler.scale(
                        sourceImage, previewDimensions.width, previewDimensions.height);
            }

            WritableImage preview = new WritableImage(previewSource.getWidth(), previewSource.getHeight());
            SwingFXUtils.toFXImage(previewSource, preview);
            return preview;
        } finally {
            if (previewSource != sourceImage) {
                previewSource.flush();
            }
            sourceImage.flush();
        }
    }

    /**
     * Fits the initial target dimensions within the per-axis and total-pixel limits.
     */
    private static Dimension fitInitialDimensions(int originalWidth, int originalHeight) {
        double scale = Math.min(1.0, Math.min(
                MAX_DIMENSION / (double) originalWidth,
                MAX_DIMENSION / (double) originalHeight));
        double originalPixels = (double) originalWidth * originalHeight;
        scale = Math.min(scale, Math.sqrt(MAX_OUTPUT_PIXELS / originalPixels));

        int width = Math.max(1, (int) Math.floor(originalWidth * scale));
        int height = Math.max(1, (int) Math.floor(originalHeight * scale));
        while ((long) width * height > MAX_OUTPUT_PIXELS) {
            if (width / (double) originalWidth >= height / (double) originalHeight) {
                width--;
            } else {
                height--;
            }
        }
        return new Dimension(width, height);
    }

    private void setDimensionFields(int width, int height) {
        updatingDimensions = true;
        try {
            widthField.setText(Integer.toString(width));
            heightField.setText(Integer.toString(height));
        } finally {
            updatingDimensions = false;
        }
    }

    private void setStatus(String message, boolean successful) {
        statusLabel.getStyleClass().removeAll("status-ok", "status-error");
        statusLabel.getStyleClass().add(successful ? "status-ok" : "status-error");
        statusLabel.setText(message);
    }

    // -------------------- Image resizing and saving --------------------
    @FXML
    private void resizeImage() {
        if (loadedImage.isEmpty()) {
            return;
        }

        OptionalInt requestedWidth = parseDimension(widthField.getText());
        OptionalInt requestedHeight = parseDimension(heightField.getText());
        if (requestedWidth.isEmpty() || requestedHeight.isEmpty()) {
            Dialogs.error("Introduce un ancho y un alto válidos (mayores que 0).");
            return;
        }

        int width = requestedWidth.getAsInt();
        int height = requestedHeight.getAsInt();
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
            Dialogs.error("El ancho y el alto no pueden superar " + MAX_DIMENSION + " px.");
            return;
        }
        if ((long) width * height > MAX_OUTPUT_PIXELS) {
            Dialogs.error("La imagen de destino supera el límite de "
                    + String.format("%,d", MAX_OUTPUT_PIXELS) + " píxeles.");
            return;
        }

        LoadedImage selectedImage = loadedImage.orElseThrow();
        ImageFormat format = selectedImage.format();
        if (!ImageFormatIO.canWrite(format)) {
            Dialogs.error("No hay ningún ImageWriter registrado para " + format.getExtensionTag()
                    + ".\nComprueba que la dependencia correspondiente esté disponible.");
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Guardar imagen redimensionada");
        fileChooser.setInitialFileName(buildSuggestedFileName(
                selectedImage.file().getName(), width, height, format));
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                format.getExtensionTag(), format.getFilterPatterns()));

        Window owner = resizeButton.getScene().getWindow();
        Optional<File> selectedDestination = Optional.ofNullable(fileChooser.showSaveDialog(owner));
        if (selectedDestination.isEmpty()) {
            return;
        }

        File destination = ensureExtension(selectedDestination.orElseThrow(), format);
        saveResizedImage(selectedImage, width, height, destination, owner);
    }

    private void saveResizedImage(
            LoadedImage selectedImage, int width, int height, File destination, Window owner) {
        Path sourcePath = selectedImage.file().toPath().toAbsolutePath().normalize();
        Path destinationPath = destination.toPath().toAbsolutePath().normalize();

        try {
            if (sourcePath.equals(destinationPath)
                    || (Files.exists(destinationPath) && Files.isSameFile(sourcePath, destinationPath))) {
                Dialogs.error("El archivo de destino no puede ser la misma imagen de origen.");
                return;
            }
        } catch (IOException exception) {
            LOG.log(Level.WARNING, "Could not compare the source and destination paths.", exception);
            Dialogs.error("No se pudo comprobar el archivo de destino: " + getErrorMessage(exception));
            return;
        }

        if (Files.exists(destinationPath) && !confirmOverwrite(destination, owner)) {
            return;
        }

        Path parentDirectory = Optional.ofNullable(destinationPath.getParent())
                .orElseThrow(() -> new IllegalStateException("The destination path has no parent directory."));

        try {
            Files.createDirectories(parentDirectory);
            Path temporaryFile = Files.createTempFile(parentDirectory, ".aevconverter-resize-", ".tmp");
            boolean saved = false;
            try {
                BufferedImage sourceImage = ImageFormatIO.readImage(selectedImage.file());
                try {
                    BufferedImage resizedImage = ImageScaler.scale(sourceImage, width, height);
                    try {
                        if (!ImageFormatIO.writeImage(resizedImage, selectedImage.format(), temporaryFile.toFile())) {
                            Dialogs.error("No hay ningún ImageIO writer que pueda escribir "
                                    + selectedImage.format().getExtensionTag() + ".");
                            return;
                        }
                    } finally {
                        if (resizedImage != sourceImage) {
                            resizedImage.flush();
                        }
                    }
                } finally {
                    sourceImage.flush();
                }

                moveIntoPlace(temporaryFile, destinationPath);
                saved = true;
            } finally {
                if (!saved) {
                    deleteTemporaryFile(temporaryFile);
                }
            }
        } catch (IOException | RuntimeException exception) {
            LOG.log(Level.WARNING, "Failed to resize image: " + selectedImage.file(), exception);
            Dialogs.error("Error al redimensionar la imagen: " + getErrorMessage(exception));
            return;
        }

        Dialogs.info("Imagen redimensionada",
                "Guardada correctamente en:\n" + destinationPath);
        LOG.fine("Resized image saved to: " + destinationPath);
    }

    private boolean confirmOverwrite(File destination, Window owner) {
        ButtonType overwriteButton = new ButtonType("Sobrescribir", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.initOwner(owner);
        confirmation.setTitle("Confirmar sobrescritura");
        confirmation.setHeaderText("El archivo de destino ya existe.");
        confirmation.setContentText("¿Quieres reemplazar este archivo?\n" + destination.getAbsolutePath());
        confirmation.getButtonTypes().setAll(overwriteButton, cancelButton);
        confirmation.getDialogPane().getStyleClass().add("alert-dark");
        confirmation.getDialogPane().getStylesheets().add(Resources.styles());
        Optional<ButtonType> result = confirmation.showAndWait();
        return result.filter(overwriteButton::equals).isPresent();
    }

    private static void moveIntoPlace(Path temporaryFile, Path destination) throws IOException {
        try {
            Files.move(temporaryFile, destination,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporaryFile, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteTemporaryFile(Path temporaryFile) {
        try {
            Files.deleteIfExists(temporaryFile);
        } catch (IOException exception) {
            LOG.log(Level.WARNING, "Failed to remove temporary output file: " + temporaryFile, exception);
        }
    }

    /**
     * Adds or normalizes the selected format's extension, including on Linux save dialogs.
     */
    private static File ensureExtension(File file, ImageFormat format) {
        String name = file.getName();
        Optional<ImageFormat> existingFormat = ImageFormat.fromExtension(name);
        if (existingFormat.filter(format::equals).isPresent()) {
            return file;
        }

        String baseName = name;
        if (existingFormat.isPresent()) {
            baseName = name.substring(0, name.lastIndexOf('.'));
        } else if (name.endsWith(".")) {
            baseName = name.substring(0, name.length() - 1);
        }

        Path path = file.toPath();
        return path.resolveSibling(baseName + "." + format.getExtension()).toFile();
    }

    private static String buildSuggestedFileName(
            String originalName, int width, int height, ImageFormat format) {
        int extensionIndex = originalName.lastIndexOf('.');
        String baseName = extensionIndex > 0
                ? originalName.substring(0, extensionIndex)
                : originalName;
        return baseName + "_" + width + "x" + height + "." + format.getExtension();
    }

    private static String getErrorMessage(Exception exception) {
        return Optional.ofNullable(exception.getMessage())
                .filter(message -> !message.isBlank())
                .orElse(exception.getClass().getSimpleName());
    }

    private record LoadedImage(File file, ImageFormat format, int originalWidth, int originalHeight) {

    }
}
