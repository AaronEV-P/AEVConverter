package com.aaronev.aevconverter.ui;

import java.net.URL;

import javafx.scene.image.Image;

/**
 * Rutas de los recursos (FXML, CSS, iconos) en un único sitio. Son rutas absolutas dentro
 * del classpath, así que mover una clase de paquete no rompe la carga de sus recursos.
 *
 * Los recursos viven en src/main/resources/com/aaronev/aevconverter/...
 */
public final class Resources {

    private static final String BASE = "/com/aaronev/aevconverter/";

    public static final String FXML_SHELL = BASE + "ui/shell/mainShell.fxml";
    public static final String FXML_CONVERT = BASE + "ui/convert/convertFormatView.fxml";
    public static final String FXML_RESIZE = BASE + "ui/resize/resizeImageView.fxml";

    public static final String CSS_STYLES = BASE + "css/styles.css";
    public static final String APP_ICON = BASE + "icons/logo_icon_dimensioned.png";

    private Resources() {
    }

    /**
     * @throws IllegalStateException with the exact patch if the resource don't exists
     */
    public static URL url(String path) {
        URL url = Resources.class.getResource(path);
        if (url == null) {
            throw new IllegalStateException("Resource not found in the classpath: " + path);
        }
        return url;
    }

    public static String styles() {
        return url(CSS_STYLES).toExternalForm();
    }

    public static Image icon() {
        return new Image(url(APP_ICON).toExternalForm());
    }
}
