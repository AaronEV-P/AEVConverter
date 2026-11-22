/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.aaronev.aevconverter.ui.shell;

import com.aaronev.aevconverter.ui.Dialogs;
import com.aaronev.aevconverter.ui.Resources;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 *
 * @author psmob
 */
public class MainShellController {

    private static final Logger LOG = Logger.getLogger(MainShellController.class.getName());

    @FXML
    private VBox rootContainer;
    @FXML
    private StackPane contentArea;

    @FXML
    private void initialize() {
        showConvertFormat();
    }

    // Method for showing in the StackPane the convertFormatView.fxml
    @FXML
    private void showConvertFormat() {
        loadContent(Resources.FXML_CONVERT);
    }

    // Method for showing in the StackPane the resizeImageVew.fxml
    @FXML
    private void showResizeImage() {
        loadContent(Resources.FXML_RESIZE);
    }

    private void loadContent(String fxmlPath) {
        try {
            Parent content = FXMLLoader.load(Resources.url(fxmlPath));
            contentArea.getChildren().setAll(content);
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to load view: " + fxmlPath,e);

            Dialogs.error("No se pudo cargar la vista solicitada:\n" + fxmlPath);
        }
    }
}
