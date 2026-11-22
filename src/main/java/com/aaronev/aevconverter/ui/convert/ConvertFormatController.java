package com.aaronev.aevconverter.ui.convert;

import com.aaronev.aevconverter.io.ImageFormatIO;
import com.aaronev.aevconverter.io.ImageScaler;
import com.aaronev.aevconverter.model.ImageFormat;
import com.aaronev.aevconverter.ui.Dialogs;
import com.aaronev.aevconverter.ui.Resources;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Controller for converting multiple images from one supported format to another.
 */
public class ConvertFormatController implements Initializable {

    private static final Logger LOG = Logger.getLogger(ConvertFormatController.class.getName());

    private static final int THUMBNAIL_CARD_SIZE = 120;
    private static final int THUMBNAIL_SIZE = 110;

    @FXML
    private ComboBox<ImageFormat> destinationFormatComboBox;
    @FXML
    private Button loadButton;
    @FXML
    private Label statusLabel;
    @FXML
    private FlowPane thumbnailPane;
    @FXML
    private Button convertButton;
    @FXML
    private ScrollPane imageScrollPane;

    private final List<File> loadedFiles = new ArrayList<>();
    private ExecutorService thumbnailExecutor = createThumbnailExecutor();
    private final AtomicBoolean subsamplingWarningShown = new AtomicBoolean();

    /**
     * Creates a small daemon-thread pool for thumbnail generation. Idle threads are allowed to terminate after 30
     * seconds so that changing views does not leave an idle pool indefinitely.
     */
    private static ExecutorService createThumbnailExecutor() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2,
                2,
                30,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                task -> {
                    Thread thread = new Thread(task, "thumbnail-worker");
                    thread.setDaemon(true);
                    return thread;
                });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        destinationFormatComboBox.getItems().setAll(ImageFormat.values());
        destinationFormatComboBox.getSelectionModel().selectFirst();
        configureDragAndDrop();
        updateStatus();
    }

    // -------------------- File selection and thumbnails --------------------
    @FXML
    private void loadFiles() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Seleccionar imágenes");

        // Build the filter from the formats declared by the application.
        List<String> imagePatterns = new ArrayList<>();
        for (ImageFormat format : ImageFormat.values()) {
            Collections.addAll(imagePatterns, format.getFilterPatterns());
        }

        FileChooser.ExtensionFilter imageFilter = new FileChooser.ExtensionFilter(
                "Formatos de imagen compatibles", imagePatterns.toArray(String[]::new));

        fileChooser.getExtensionFilters().setAll(imageFilter);
        fileChooser.setSelectedExtensionFilter(imageFilter);

        Window owner = loadButton.getScene().getWindow();
        Optional.ofNullable(fileChooser.showOpenMultipleDialog(owner))
                .filter(selectedFiles -> !selectedFiles.isEmpty())
                .ifPresent(this::addFiles);
    }

    /**
     * Enables drag-and-drop of supported image files onto the thumbnail area.
     */
    /**
     * Enables drag-and-drop of supported image files onto the entire scrollable area.
     */
    private void configureDragAndDrop() {

        imageScrollPane.setOnDragOver(event -> {
            if (event.getDragboard().hasFiles()
                    && event.getDragboard().getFiles().stream()
                            .anyMatch(this::hasSupportedExtension)) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });

        imageScrollPane.setOnDragEntered(event -> {
            if (event.getDragboard().hasFiles()
                    && event.getDragboard().getFiles().stream()
                            .anyMatch(this::hasSupportedExtension)) {

                if (!imageScrollPane.getStyleClass().contains("drag-over")) {
                    imageScrollPane.getStyleClass().add("drag-over");
                }
            }
        });

        imageScrollPane.setOnDragExited(event
                -> imageScrollPane.getStyleClass().remove("drag-over"));

        imageScrollPane.setOnDragDropped(event -> {
            boolean completed = event.getDragboard().hasFiles()
                    && addFiles(event.getDragboard().getFiles());

            event.setDropCompleted(completed);
            imageScrollPane.getStyleClass().remove("drag-over");
            event.consume();
        });
    }

    /**
     * Adds supported image files, ignores duplicates, and reports how many files were rejected.
     *
     * @return true if at least one supported image was supplied
     */
    private boolean addFiles(List<File> files) {
        int rejectedCount = 0;
        boolean foundSupportedFile = false;

        for (File file : files) {
            if (!isSupportedImageFile(file)) {
                rejectedCount++;
                continue;
            }

            foundSupportedFile = true;
            if (!loadedFiles.contains(file)) {
                loadedFiles.add(file);
                addThumbnailCard(file);
            }
        }

        updateStatus();

        if (rejectedCount > 0) {
            Dialogs.info("Archivos omitidos",
                    rejectedCount + " archivo(s) no se han añadido porque no tienen un formato compatible "
                    + "o no se pueden leer como imágenes.");
        }

        return foundSupportedFile;
    }

    /**
     * Checks the file extension against the formats supported by the application.
     */
    private boolean hasSupportedExtension(File file) {
        return file != null
                && file.isFile()
                && ImageFormat.fromExtension(file.getName()).isPresent();
    }

    /**
     * Checks both the declared extension and whether an installed ImageIO reader recognizes the file contents. Only the
     * image header is inspected; the full image is decoded later by the thumbnail worker.
     */
    private boolean isSupportedImageFile(File file) {
        if (!hasSupportedExtension(file) || !file.canRead()) {
            return false;
        }

        try (ImageInputStream input = ImageIO.createImageInputStream(file)) {
            if (input == null) {
                return false;
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return false;
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                return reader.getWidth(0) > 0 && reader.getHeight(0) > 0;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException exception) {
            LOG.log(Level.FINE, "Rejected unsupported or unreadable image: " + file, exception);
            return false;
        }
    }

    /**
     * Adds an image card and generates its thumbnail on a background thread.
     */
    private void addThumbnailCard(File file) {
        StackPane card = new StackPane();
        card.setPrefSize(THUMBNAIL_CARD_SIZE, THUMBNAIL_CARD_SIZE);
        card.getStyleClass().add("thumbnail-card");

        ImageView imageView = new ImageView();
        imageView.setFitWidth(THUMBNAIL_SIZE);
        imageView.setFitHeight(THUMBNAIL_SIZE);
        imageView.setPreserveRatio(true);

        Button removeButton = new Button("X");
        removeButton.getStyleClass().add("btn-remove-image");
        StackPane.setAlignment(removeButton, Pos.TOP_RIGHT);
        removeButton.setOnAction(event -> {
            loadedFiles.remove(file);
            thumbnailPane.getChildren().remove(card);
            updateStatus();
        });

        card.getChildren().addAll(imageView, removeButton);
        thumbnailPane.getChildren().add(card);

        Task<Optional<WritableImage>> thumbnailTask = new Task<>() {
            @Override
            protected Optional<WritableImage> call() {
                return createThumbnail(file, THUMBNAIL_SIZE, THUMBNAIL_SIZE);
            }
        };

        thumbnailTask.setOnSucceeded(event -> thumbnailTask.getValue().ifPresent(imageView::setImage));
        thumbnailExecutor.submit(thumbnailTask);
    }

    /**
     * Creates an aspect-ratio-preserving thumbnail. It tries ImageReader source subsampling first to reduce memory use,
     * then falls back to a normal decode if subsampling is unavailable. An empty Optional indicates that no thumbnail
     * could be produced.
     */
    private Optional<WritableImage> createThumbnail(File file, int maxWidth, int maxHeight) {
        if (Thread.currentThread().isInterrupted()) {
            return Optional.empty();
        }

        try {
            Optional<BufferedImage> subsampledImage;
            try {
                subsampledImage = readSubsampledImage(file, maxWidth, maxHeight);
            } catch (IOException exception) {
                if (Thread.currentThread().isInterrupted()) {
                    throw exception;
                }
                LOG.log(Level.FINE,
                        "Subsampled thumbnail read failed; retrying with a full decode: " + file, exception);
                subsampledImage = Optional.empty();
            }

            BufferedImage decodedImage = subsampledImage.isPresent()
                    ? subsampledImage.orElseThrow()
                    : ImageFormatIO.readImage(file);

            if (Thread.currentThread().isInterrupted()) {
                decodedImage.flush();
                return Optional.empty();
            }

            return Optional.of(toWritableThumbnail(decodedImage, maxWidth, maxHeight));
        } catch (ClosedByInterruptException exception) {
            return Optional.empty();
        } catch (IOException exception) {
            if (!Thread.currentThread().isInterrupted()) {
                LOG.log(Level.WARNING, "Failed to generate thumbnail for " + file, exception);
            }
            return Optional.empty();
        } catch (RuntimeException exception) {
            if (!Thread.currentThread().isInterrupted()) {
                LOG.log(Level.WARNING, "Unexpected error while generating thumbnail for " + file, exception);
            }
            return Optional.empty();
        }
    }

    /**
     * Reads a subsampled image, or returns an empty Optional when no reader or subsampling support is available.
     */
    private Optional<BufferedImage> readSubsampledImage(File file, int maxWidth, int maxHeight) throws IOException {
        Optional<ImageInputStream> optionalInput = Optional.ofNullable(ImageIO.createImageInputStream(file));
        if (optionalInput.isEmpty()) {
            return Optional.empty();
        }

        try (ImageInputStream input = optionalInput.orElseThrow()) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return Optional.empty();
            }

            ImageReader reader = readers.next();
            Optional<BufferedImage> reducedImage = Optional.empty();
            boolean ownershipTransferred = false;
            try {
                reader.setInput(input, true, true);
                int originalWidth = reader.getWidth(0);
                int originalHeight = reader.getHeight(0);

                // Choose a sampling factor large enough to reduce the limiting dimension.
                int widthFactor = (int) Math.ceil(originalWidth / (double) maxWidth);
                int heightFactor = (int) Math.ceil(originalHeight / (double) maxHeight);
                int samplingFactor = Math.max(1, Math.max(widthFactor, heightFactor));

                ImageReadParam readParam = reader.getDefaultReadParam();
                readParam.setSourceSubsampling(samplingFactor, samplingFactor, 0, 0);
                reducedImage = Optional.ofNullable(reader.read(0, readParam));
                if (reducedImage.isEmpty()) {
                    return Optional.empty();
                }

                BufferedImage decodedImage = reducedImage.orElseThrow();
                // Warn once if a reader appears to ignore the requested subsampling.
                boolean widthWasNotReduced = originalWidth > 1 && decodedImage.getWidth() >= originalWidth;
                boolean heightWasNotReduced = originalHeight > 1 && decodedImage.getHeight() >= originalHeight;
                if (samplingFactor > 1
                        && widthWasNotReduced && heightWasNotReduced
                        && subsamplingWarningShown.compareAndSet(false, true)) {
                    LOG.log(Level.WARNING, "The {0} plugin appears to ignore source subsampling. Thumbnail generation may decode the full image ({1}x{2}).", new Object[]{reader.getFormatName(), originalWidth, originalHeight});
                }

                ownershipTransferred = true;
                return reducedImage;
            } catch (UnsupportedOperationException | IllegalArgumentException exception) {
                return Optional.empty();
            } finally {
                if (!ownershipTransferred) {
                    reducedImage.ifPresent(BufferedImage::flush);
                }
                reader.dispose();
            }
        }
    }

    /**
     * Converts a decoded image into a JavaFX thumbnail and releases the temporary AWT images.
     */
    private WritableImage toWritableThumbnail(BufferedImage sourceImage, int maxWidth, int maxHeight) {
        BufferedImage scaledImage = sourceImage;
        try {
            Dimension dimensions = ImageScaler.fitDimensions(
                    sourceImage.getWidth(), sourceImage.getHeight(), maxWidth, maxHeight);
            scaledImage = ImageScaler.scale(sourceImage, dimensions.width, dimensions.height);

            WritableImage thumbnail = new WritableImage(scaledImage.getWidth(), scaledImage.getHeight());
            SwingFXUtils.toFXImage(scaledImage, thumbnail);
            return thumbnail;
        } finally {
            if (scaledImage != sourceImage) {
                scaledImage.flush();
            }
            sourceImage.flush();
        }
    }

    // -------------------- Conversion --------------------
    @FXML
    private void convertImages() {
        if (loadedFiles.isEmpty()) {
            return;
        }

        Optional<ImageFormat> selectedDestination = Optional.ofNullable(destinationFormatComboBox.getValue());
        if (selectedDestination.isEmpty()) {
            Dialogs.error("Selecciona un formato de destino.");
            return;
        }
        ImageFormat destinationFormat = selectedDestination.orElseThrow();

        if (!ImageFormatIO.canWrite(destinationFormat)) {
            Dialogs.error("No hay ningún ImageWriter registrado para " + destinationFormat.getExtensionTag()
                    + ".\nComprueba que la dependencia correspondiente esté disponible.");
            return;
        }

        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Seleccionar carpeta de destino para " + destinationFormat.getExtensionTag());
        Window owner = convertButton.getScene().getWindow();
        Optional<File> selectedDirectory = Optional.ofNullable(directoryChooser.showDialog(owner));
        if (selectedDirectory.isEmpty()) {
            return;
        }
        File destinationDirectory = selectedDirectory.orElseThrow();

        // Use an immutable snapshot so background work is independent of the UI list.
        List<File> filesToConvert = List.copyOf(loadedFiles);
        int totalFiles = filesToConvert.size();
        // Files with the same destination name are grouped so only one worker can handle that target at a time.
        List<List<File>> outputGroups = groupFilesByOutput(filesToConvert, destinationDirectory, destinationFormat);
        int totalGroups = outputGroups.size();
        // Multiple workers may add/remove their own files while conversion is in progress.
        List<File> createdOutputs = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean cancellationRequested = new AtomicBoolean(false);

        Stage progressWindow = new Stage();
        progressWindow.initModality(Modality.WINDOW_MODAL);
        progressWindow.initOwner(owner);
        progressWindow.setTitle("Convirtiendo imágenes...");
        progressWindow.setResizable(false);
        progressWindow.setOnCloseRequest(event -> event.consume());

        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(280);
        Label progressMessage = new Label("Iniciando conversión...");
        Label percentageLabel = new Label("0%");
        percentageLabel.getStyleClass().add("subtitle-label");
        Button cancelButton = new Button("Cancelar");
        cancelButton.getStyleClass().add("button-danger");

        VBox root = new VBox(12, progressMessage, progressBar, percentageLabel, cancelButton);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(20));
        root.getStyleClass().add("dialog-root");

        Scene scene = new Scene(root, 340, 170);
        scene.getStylesheets().add(Resources.styles());
        progressWindow.setScene(scene);

        Task<ConversionResult> conversionTask = new Task<>() {
            @Override
            protected ConversionResult call() {
                int converted = 0;
                int skippedExisting = 0;
                int failed = 0;
                int cleanupFailures = 0;
                int completed = 0;
                int nextGroupIndex = 0;
                int activeTasks = 0;
                boolean interrupted = false;

                // Read this for each conversion run; no machine-specific CPU count is hard-coded.
                int availableProcessors = Runtime.getRuntime().availableProcessors();
                int workerCount = Math.max(1, Math.min(availableProcessors, totalGroups));
                ExecutorService conversionExecutor = createConversionExecutor(workerCount);
                CompletionService<List<FileConversionResult>> completionService
                        = new ExecutorCompletionService<>(conversionExecutor);

                if (workerCount == 1) {
                    updateMessage("Convirtiendo con " + workerCount + " núcleo");
                } else {
                    updateMessage("Convirtiendo con " + workerCount + " núcleos...");

                }

                try {
                    // Keep at most workerCount tasks submitted at once to avoid an unbounded backlog.
                    while (nextGroupIndex < totalGroups && activeTasks < workerCount) {
                        submitConversion(completionService, outputGroups.get(nextGroupIndex++), destinationDirectory,
                                destinationFormat, cancellationRequested, createdOutputs);
                        activeTasks++;
                    }

                    while (activeTasks > 0) {
                        Future<List<FileConversionResult>> completedFuture;
                        try {
                            completedFuture = completionService.take();
                        } catch (InterruptedException exception) {
                            interrupted = true;
                            cancellationRequested.set(true);
                            break;
                        }

                        activeTasks--;
                        try {
                            List<FileConversionResult> groupResults = completedFuture.get();
                            for (FileConversionResult fileResult : groupResults) {
                                switch (fileResult.status()) {
                                    case CONVERTED ->
                                        converted++;
                                    case SKIPPED_EXISTING ->
                                        skippedExisting++;
                                    case FAILED ->
                                        failed++;
                                    case CANCELLED -> {
                                        // Cancellation is summarized once all active workers have stopped.
                                    }
                                }
                                cleanupFailures += fileResult.cleanupFailures();
                                if (fileResult.status() != FileConversionStatus.CANCELLED) {
                                    completed++;
                                    updateProgress(completed, totalFiles);
                                    if (workerCount == 1) {
                                        updateMessage("Procesadas: " + completed + " de " + totalFiles
                                                + " imágenes (" + workerCount + " núcleo)...");
                                    } else {
                                        updateMessage("Procesadas: " + completed + " de " + totalFiles
                                                + " imágenes (" + workerCount + " núcleos)...");
                                    }

                                }
                            }
                        } catch (InterruptedException exception) {
                            interrupted = true;
                            cancellationRequested.set(true);
                            break;
                        } catch (ExecutionException exception) {
                            failed++;
                            LOG.log(Level.SEVERE, "A conversion worker failed unexpectedly.", exception.getCause());
                        }

                        if (!cancellationRequested.get() && nextGroupIndex < totalGroups) {
                            submitConversion(completionService, outputGroups.get(nextGroupIndex++), destinationDirectory,
                                    destinationFormat, cancellationRequested, createdOutputs);
                            activeTasks++;
                        }
                    }
                } finally {
                    if (interrupted) {
                        conversionExecutor.shutdownNow();
                    } else {
                        conversionExecutor.shutdown();
                    }
                    interrupted |= awaitTerminationUninterruptibly(conversionExecutor);
                }

                boolean cancelled = cancellationRequested.get() || interrupted;
                if (cancelled) {
                    cleanupFailures += deleteCreatedOutputs(createdOutputs);
                }

                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                return new ConversionResult(converted, skippedExisting, failed, cancelled, cleanupFailures);
            }
        };

        progressBar.progressProperty().bind(conversionTask.progressProperty());
        progressMessage.textProperty().bind(conversionTask.messageProperty());
        conversionTask.progressProperty().addListener((observable, oldValue, newValue)
                -> Optional.ofNullable(newValue)
                        .map(Number::doubleValue)
                        .filter(progress -> progress >= 0)
                        .ifPresent(progress -> percentageLabel.setText((int) (progress * 100) + "%")));

        cancelButton.setOnAction(event -> {
            cancellationRequested.set(true);
            cancelButton.setDisable(true);
            progressMessage.textProperty().unbind();
            progressMessage.setText("Cancelando y limpiando archivos...");
        });

        conversionTask.setOnSucceeded(event -> {
            ConversionResult result = conversionTask.getValue();
            boolean cancelled = result.cancelled() || cancellationRequested.get();
            int cleanupFailures = result.cleanupFailures();

            // Handle the narrow race where cancellation is clicked just after the worker finishes.
            if (cancelled && !result.cancelled()) {
                cleanupFailures += deleteCreatedOutputs(createdOutputs);
            }

            progressWindow.close();
            if (cancelled) {
                String message = "Conversión cancelada. Se eliminaron los archivos creados por esta operación.";
                if (cleanupFailures > 0) {
                    message += "\nNo se pudieron eliminar " + cleanupFailures + " archivo(s); consulta el registro.";
                }
                Dialogs.info("Conversión cancelada", message);
                return;
            }

            String message = "Convertidas: " + result.converted() + " de " + totalFiles + "."
                    + "\nOmitidas porque el archivo de destino ya existía: " + result.skippedExisting() + "."
                    + "\nErrores: " + result.failed() + ".";
            if (result.cleanupFailures() > 0) {
                message += "\nNo se pudieron eliminar " + result.cleanupFailures() + " archivo(s) incompletos.";
            }
            Dialogs.info("Proceso finalizado", message);

            if (result.converted() == totalFiles && result.skippedExisting() == 0 && result.failed() == 0) {
                clearImages();
            }
        });

        conversionTask.setOnFailed(event -> {
            int cleanupFailures = deleteCreatedOutputs(createdOutputs);
            progressWindow.close();
            Optional.ofNullable(conversionTask.getException())
                    .ifPresent(exception -> LOG.log(
                            Level.SEVERE, "The conversion task failed unexpectedly.", exception));
            String message = "La conversión se interrumpió por un error inesperado.";
            if (cleanupFailures > 0) {
                message += "\nNo se pudieron eliminar " + cleanupFailures + " archivo(s) incompletos; consulta el registro.";
            }
            Dialogs.error(message);
        });

        progressWindow.show();
        Thread conversionThread = new Thread(conversionTask, "image-conversion");
        conversionThread.setDaemon(true);
        conversionThread.start();
    }

    private static ExecutorService createConversionExecutor(int workerCount) {
        AtomicInteger threadNumber = new AtomicInteger();
        return Executors.newFixedThreadPool(workerCount, runnable -> {
            Thread thread = new Thread(runnable, "image-conversion-worker-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    private static List<List<File>> groupFilesByOutput(
            List<File> sourceFiles, File destinationDirectory, ImageFormat destinationFormat) {
        Map<Path, List<File>> groupedFiles = new LinkedHashMap<>();
        Path normalizedDirectory = destinationDirectory.toPath().toAbsolutePath().normalize();

        for (File sourceFile : sourceFiles) {
            String outputName = buildOutputFileName(sourceFile.getName(), destinationFormat);
            Path outputPath = normalizedDirectory.resolve(outputName).normalize();
            groupedFiles.computeIfAbsent(outputPath, ignored -> new ArrayList<>()).add(sourceFile);
        }

        List<List<File>> groups = new ArrayList<>(groupedFiles.size());
        for (List<File> group : groupedFiles.values()) {
            groups.add(List.copyOf(group));
        }
        return List.copyOf(groups);
    }

    private static void submitConversion(
            CompletionService<List<FileConversionResult>> completionService,
            List<File> sourceFiles,
            File destinationDirectory,
            ImageFormat destinationFormat,
            AtomicBoolean cancellationRequested,
            List<File> createdOutputs) {

        completionService.submit(() -> convertOutputGroup(
                sourceFiles, destinationDirectory, destinationFormat, cancellationRequested, createdOutputs));
    }

    private static List<FileConversionResult> convertOutputGroup(
            List<File> sourceFiles,
            File destinationDirectory,
            ImageFormat destinationFormat,
            AtomicBoolean cancellationRequested,
            List<File> createdOutputs) {

        List<FileConversionResult> results = new ArrayList<>(sourceFiles.size());
        for (File sourceFile : sourceFiles) {
            if (cancellationRequested.get()) {
                break;
            }

            File outputFile = new File(
                    destinationDirectory, buildOutputFileName(sourceFile.getName(), destinationFormat));
            FileConversionResult result = convertSingleFile(
                    sourceFile, outputFile, destinationFormat, cancellationRequested, createdOutputs);
            results.add(result);
            if (result.status() == FileConversionStatus.CANCELLED) {
                break;
            }
        }
        return List.copyOf(results);
    }

    private static FileConversionResult convertSingleFile(
            File sourceFile,
            File outputFile,
            ImageFormat destinationFormat,
            AtomicBoolean cancellationRequested,
            List<File> createdOutputs) {

        if (cancellationRequested.get()) {
            return new FileConversionResult(FileConversionStatus.CANCELLED, 0);
        }

        boolean outputCreatedByThisTask = false;
        try {
            BufferedImage sourceImage = ImageFormatIO.readImage(sourceFile);
            try {
                if (cancellationRequested.get()) {
                    return new FileConversionResult(FileConversionStatus.CANCELLED, 0);
                }

                // Create the output atomically without overwriting an existing file.
                try {
                    Files.createFile(outputFile.toPath());
                    outputCreatedByThisTask = true;
                    createdOutputs.add(outputFile);
                } catch (FileAlreadyExistsException exception) {
                    return new FileConversionResult(FileConversionStatus.SKIPPED_EXISTING, 0);
                }

                if (cancellationRequested.get()) {
                    // The output is tracked and will be removed by cancellation cleanup after all workers stop.
                    return new FileConversionResult(FileConversionStatus.CANCELLED, 0);
                }

                // ImageFormatIO owns format-specific preparation, including automatic ICO downscaling.
                if (ImageFormatIO.writeImage(sourceImage, destinationFormat, outputFile)) {
                    return new FileConversionResult(FileConversionStatus.CONVERTED, 0);
                }

                LOG.log(Level.WARNING, "No ImageIO writer wrote the output file: {0}", outputFile);
                int cleanupFailures = cleanupWorkerOutput(outputFile, outputCreatedByThisTask, createdOutputs);
                return new FileConversionResult(FileConversionStatus.FAILED, cleanupFailures);
            } finally {
                sourceImage.flush();
            }
        } catch (IOException | RuntimeException exception) {
            int cleanupFailures = cleanupWorkerOutput(outputFile, outputCreatedByThisTask, createdOutputs);
            LOG.log(Level.WARNING, "Failed to convert " + sourceFile, exception);
            return new FileConversionResult(FileConversionStatus.FAILED, cleanupFailures);
        }
    }

    private static int cleanupWorkerOutput(
            File outputFile, boolean outputCreatedByThisTask, List<File> createdOutputs) {
        if (!outputCreatedByThisTask) {
            return 0;
        }
        if (deleteOutputFile(outputFile)) {
            createdOutputs.remove(outputFile);
            return 0;
        }
        return 1;
    }

    private static boolean awaitTerminationUninterruptibly(ExecutorService executor) {
        boolean interrupted = false;
        while (!executor.isTerminated()) {
            try {
                executor.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                interrupted = true;
                executor.shutdownNow();
            }
        }
        return interrupted;
    }

    private static String buildOutputFileName(String originalName, ImageFormat destinationFormat) {
        int extensionIndex = originalName.lastIndexOf('.');
        String baseName = extensionIndex > 0 ? originalName.substring(0, extensionIndex) : originalName;
        return baseName + "." + destinationFormat.getExtension();
    }

    // -------------------- UI status and list clearing --------------------
    private void updateStatus() {
        statusLabel.getStyleClass().removeAll("status-ok", "status-error");
        if (loadedFiles.isEmpty()) {
            statusLabel.setText("Imágenes no cargadas");
            statusLabel.getStyleClass().add("status-error");
            convertButton.setDisable(true);
        } else {
            statusLabel.setText("Imágenes cargadas (" + loadedFiles.size() + ")");
            statusLabel.getStyleClass().add("status-ok");
            convertButton.setDisable(false);
        }
    }

    @FXML
    private void clearImages() {
        boolean hadImages = !loadedFiles.isEmpty();

        // Interrupt pending thumbnail tasks and create a fresh pool for subsequent selections.
        thumbnailExecutor.shutdownNow();
        thumbnailExecutor = createThumbnailExecutor();

        loadedFiles.clear();
        thumbnailPane.getChildren().clear();
        updateStatus();

        // Releasing image references lets the JVM reclaim memory when needed; no explicit GC is forced.
        if (hadImages) {
            LOG.fine("Cleared the loaded image list and its thumbnails.");
        }
    }

    private static boolean deleteOutputFile(File file) {
        try {
            Files.deleteIfExists(file.toPath());
            return true;
        } catch (IOException exception) {
            LOG.log(Level.WARNING, "Failed to delete an incomplete output file: " + file, exception);
            return false;
        }
    }

    private static int deleteCreatedOutputs(List<File> files) {
        List<File> snapshot;
        synchronized (files) {
            snapshot = List.copyOf(files);
        }

        int failures = 0;
        for (File file : snapshot) {
            if (!deleteOutputFile(file)) {
                failures++;
            }
        }
        synchronized (files) {
            files.clear();
        }
        return failures;
    }

    private enum FileConversionStatus {
        CONVERTED,
        SKIPPED_EXISTING,
        FAILED,
        CANCELLED
    }

    private record FileConversionResult(FileConversionStatus status, int cleanupFailures) {

    }

    private record ConversionResult(
            int converted,
            int skippedExisting,
            int failed,
            boolean cancelled,
            int cleanupFailures) {

    }
}
