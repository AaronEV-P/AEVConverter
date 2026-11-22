/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.aaronev.aevconverter;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Configures application logging and stores log files
 * in the application's directory.
 */
public final class LogConfiguration {

    private static final String LOG_DIRECTORY = "logs";
    private static final String LOG_FILE_NAME = "aevconverter.log";

    private static final int LOG_FILE_SIZE_LIMIT = 1_000_000;
    private static final int LOG_FILE_COUNT = 3;

    private LogConfiguration() {
    }

    /**
     * Configures the root logger and creates the log directory.
     *
     * @throws IOException if the log directory or file cannot be created
     */
    public static void configure() throws IOException {
        Path applicationDirectory = findApplicationDirectory();
        Path logDirectory = applicationDirectory.resolve(LOG_DIRECTORY);

        Files.createDirectories(logDirectory);

        Path logFile = logDirectory.resolve(LOG_FILE_NAME);

        FileHandler fileHandler = new FileHandler(
                logFile.toString(),
                LOG_FILE_SIZE_LIMIT,
                LOG_FILE_COUNT,
                true
        );

        fileHandler.setFormatter(new SimpleFormatter());

        Logger rootLogger = Logger.getLogger("");
        rootLogger.addHandler(fileHandler);
        rootLogger.setLevel(Level.INFO);
    }

    /**
     * Determines the application's directory from the running executable.
     * Falls back to the code location when running from an IDE.
     *
     * @return the application's directory
     * @throws IOException if the directory cannot be determined
     */
    private static Path findApplicationDirectory() throws IOException {
        String command = ProcessHandle.current()
                .info()
                .command()
                .orElse("");

        if (!command.isBlank()) {
            Path executable = Path.of(command).toAbsolutePath();

            if (Files.exists(executable)) {
                executable = executable.toRealPath();

                String executableName = executable.getFileName()
                        .toString()
                        .toLowerCase(Locale.ROOT);

                boolean isJavaLauncher =
                        executableName.equals("java")
                        || executableName.equals("java.exe")
                        || executableName.equals("javaw.exe");

                if (!isJavaLauncher) {
                    Path launcherDirectory = executable.getParent();

                    if (launcherDirectory != null) {
                        Path parentDirectory =
                                launcherDirectory.getParent();

                        // Linux application images use a bin/ directory
                        // alongside the lib/ directory.
                        if (parentDirectory != null
                                && "bin".equalsIgnoreCase(
                                        launcherDirectory.getFileName()
                                                .toString())
                                && Files.isDirectory(
                                        parentDirectory.resolve("lib"))) {
                            return parentDirectory;
                        }

                        // Windows launchers are normally located
                        // in the application's root directory.
                        return launcherDirectory;
                    }
                }
            }
        }

        return findDirectoryFromCodeSource();
    }

    /**
     * Determines the application directory using the code location.
     * This is useful when running from an IDE such as NetBeans.
     *
     * @return the application directory
     * @throws IOException if the directory cannot be determined
     */
    private static Path findDirectoryFromCodeSource() throws IOException {
        try {
            Path codeLocation = Path.of(
                    LogConfiguration.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI()
            ).toAbsolutePath().normalize();

            // Maven normally compiles classes into target/classes.
            if (Files.isDirectory(codeLocation)
                    && "classes".equalsIgnoreCase(
                            codeLocation.getFileName().toString())) {

                Path targetDirectory = codeLocation.getParent();

                if (targetDirectory != null
                        && "target".equalsIgnoreCase(
                                targetDirectory.getFileName().toString())
                        && targetDirectory.getParent() != null) {
                    return targetDirectory.getParent();
                }
            }

            Path currentDirectory = Files.isDirectory(codeLocation)
                    ? codeLocation
                    : codeLocation.getParent();

            if (currentDirectory == null) {
                throw new IOException(
                        "Unable to determine the application directory.");
            }

            // Look for a packaged application directory.
            for (Path current = currentDirectory;
                    current != null;
                    current = current.getParent()) {

                if (Files.isDirectory(current.resolve("bin"))
                        && Files.isDirectory(current.resolve("lib"))) {
                    return current;
                }
            }

            return currentDirectory;

        } catch (URISyntaxException | NullPointerException e) {
            throw new IOException(
                    "Unable to determine the application directory.", e);
        }
    }
}