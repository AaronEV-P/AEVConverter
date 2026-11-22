package com.aaronev.aevconverter;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;
import java.util.logging.Logger;
import javafx.scene.image.Image;

/**
 * JavaFX App
 */
public class App extends Application {

    private static Scene scene;
    private static final Logger LOG = Logger.getLogger(App.class.getName());

    @Override
    public void start(Stage stage) throws IOException {
        configureLogging();
        scene = new Scene(loadFXML("ui/shell/mainShell"), 800, 500);

        // Set the application window icon
        stage.getIcons().add(new Image(App.class.getResourceAsStream("icons/icon_logo.png")));
        stage.setMinWidth(600);
        stage.setMinHeight(450);
        stage.setTitle("AEVConverter");
        stage.setScene(scene);
        stage.show();
    }

    /**
     * Initializes application logging
     */
    private void configureLogging() {
        try {
            LogConfiguration.configure();
        } catch (IOException e) {
            System.err.println(
                    "Failed to initialize application logging.");
            e.printStackTrace(System.err);
        }
    }

    static void setRoot(String fxml) throws IOException {
        scene.setRoot(loadFXML(fxml));
    }

    private static Parent loadFXML(String fxml) throws IOException {
        String resourcePath = fxml + ".fxml";
        URL resource = App.class.getResource(resourcePath);

        if (resource == null) {
            throw new IOException(
                    "FXML resource not found on the classpath: " + resourcePath);
        }

        return new FXMLLoader(resource).load();
    }

    public static void main(String[] args) {
        launch();
    }

}
