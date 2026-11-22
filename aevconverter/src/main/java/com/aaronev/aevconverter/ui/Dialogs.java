package com.aaronev.aevconverter.ui;

import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.DialogPane;
import javafx.scene.layout.Region;

/**
 * Utility class for displaying application alerts with the dark theme applied.
 */
public final class Dialogs {

    private Dialogs() {
    }

    /**
     * Displays an info alert to the user.
     * @param title the title of the info alert
     * @param message the message displayed in the info alert
     */
    public static void info(String title, String message) {
        show(AlertType.INFORMATION, title, message);
    }

    /**
     * Displays an error alert to the user
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

        DialogPane panel = alert.getDialogPane();
        panel.getStylesheets().add(Resources.styles());
        panel.getStyleClass().add("alert-dark");
        panel.setPrefWidth(400);
        panel.setMinHeight(Region.USE_PREF_SIZE);

        alert.showAndWait();
    }
}
