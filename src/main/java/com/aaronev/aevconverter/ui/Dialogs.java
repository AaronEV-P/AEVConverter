package com.aaronev.aevconverter.ui;

import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.DialogPane;
import javafx.scene.layout.Region;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;

/**
 * Utility class for displaying application alerts with the dark theme applied.
 */
public final class Dialogs {

    private Dialogs() {
    }

    private static void applyTheme(Alert alert) {

        DialogPane panel = alert.getDialogPane();

        panel.getStylesheets().add(Resources.styles());
        panel.getStyleClass().add("alert-dark");
        panel.setPrefWidth(400);
        panel.setMinHeight(Region.USE_PREF_SIZE);
    }

    /**
     * Displays an info alert to the user.
     *
     * @param title the title of the info alert
     * @param message the message displayed in the info alert
     */
    public static void info(String title, String message) {
        show(AlertType.INFORMATION, title, message);
    }

    /**
     * Displays an error alert to the user
     *
     * @param message the message displayed in the error alert
     */
    public static void error(String message) {
        show(AlertType.ERROR, "Error", message);
    }

    private static void show(AlertType type, String title, String message) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);

        applyTheme(alert);

        alert.showAndWait();
    }

    /**
     * Displays an update notification.
     *
     * @param version the available application version
     * @return true if the user chooses to open the release page
     */
    public static boolean showUpdateAvailable(String currentVersion, String version) {

        ButtonType updateButton = new ButtonType(
                "Ver actualización",
                ButtonBar.ButtonData.OK_DONE);

        ButtonType laterButton = new ButtonType(
                "Más tarde",
                ButtonBar.ButtonData.CANCEL_CLOSE);

        Alert alert = new Alert(AlertType.INFORMATION);

        alert.setTitle("Actualización disponible");
        alert.setHeaderText(
                "Nueva versión disponible: " + version);

        alert.setContentText(
                "Versión instalada: v" + currentVersion + "\n"
                + "Nueva versión: " + version + "\n\n"
                + "¿Quieres visitar la página de descarga?");

        alert.getButtonTypes().setAll(updateButton, laterButton);

        applyTheme(alert);

        return alert.showAndWait()
                .filter(updateButton::equals)
                .isPresent();
    }
}
