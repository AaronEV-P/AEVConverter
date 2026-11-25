module com.mycompany.aevconverter {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.logging;
    requires java.desktop;
    requires javafx.graphicsEmpty;
    requires javafx.swing;
    requires com.twelvemonkeys.imageio.bmp;
    requires java.net.http;
    requires java.prefs;
    requires com.google.gson;

    opens com.aaronev.aevconverter to javafx.fxml;
    opens com.aaronev.aevconverter.ui.shell to javafx.fxml;
    opens com.aaronev.aevconverter.ui.convert to javafx.fxml;
    opens com.aaronev.aevconverter.ui.resize to javafx.fxml;
    exports com.aaronev.aevconverter;
    
}
