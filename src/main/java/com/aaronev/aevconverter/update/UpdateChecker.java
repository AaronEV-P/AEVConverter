package com.aaronev.aevconverter.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.prefs.Preferences;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Checks GitHub for newer published versions of AEV Converter.
 */
public final class UpdateChecker {

    private static final Logger LOG
            = Logger.getLogger(UpdateChecker.class.getName());

    private static final URI RELEASES_URI = URI.create(
            "https://api.github.com/repos/AaronEV-P/AEVConverter/releases?per_page=100");

    private static final String LAST_CHECK_KEY = "lastUpdateCheck";
    private static final long CHECK_INTERVAL = Duration.ofHours(24).toMillis();

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private UpdateChecker() {
    }

    /**
     * Checks for updates asynchronously.
     *
     * @param currentVersion currently installed application version
     * @return an optional update when a newer release exists
     */
    public static CompletableFuture<Optional<UpdateInfo>> checkForUpdates(
            String currentVersion) {

        if (!isCheckDue()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        HttpRequest request = HttpRequest.newBuilder(RELEASES_URI)
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2026-03-10")
                .header("User-Agent", "AEVConverter")
                .GET()
                .build();

        return HTTP_CLIENT.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {

                    saveLastCheck();

                    if (response.statusCode() != 200) {
                        throw new CompletionException(
                                new IOException(
                                        "GitHub returned HTTP "
                                        + response.statusCode()));
                    }

                    return findNewerRelease(
                            response.body(), currentVersion);
                })
                .exceptionally(exception -> {

                    Throwable cause = exception instanceof CompletionException
                            && exception.getCause() != null
                            ? exception.getCause()
                            : exception;

                    LOG.log(
                            Level.FINE,
                            "Could not check for application updates.",
                            cause);

                    return Optional.empty();
                });
    }

    private static Optional<UpdateInfo> findNewerRelease(
            String json, String currentVersion) {

        Version installedVersion = parseVersion(currentVersion)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Invalid installed application version: "
                        + currentVersion));

        JsonArray releases
                = JsonParser.parseString(json).getAsJsonArray();

        JsonObject selectedRelease = null;
        Version selectedVersion = installedVersion;

        for (JsonElement element : releases) {

            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject release = element.getAsJsonObject();

            if (getBoolean(release, "draft")) {
                continue;
            }

            // Pre-releases are inlcuded
            String tagName = getString(release, "tag_name");

            Optional<Version> releaseVersion
                    = parseVersion(tagName);

            if (releaseVersion.isEmpty()
                    || releaseVersion.orElseThrow()
                            .compareTo(selectedVersion) <= 0) {
                continue;
            }

            selectedVersion = releaseVersion.orElseThrow();
            selectedRelease = release;
        }

        if (selectedRelease == null) {
            return Optional.empty();
        }

        String releaseUrl = getString(selectedRelease, "html_url");

        if (releaseUrl.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(new UpdateInfo(
                currentVersion,
                getString(selectedRelease, "tag_name"),
                releaseUrl));
    }

    /**
     * Parses versions using the MAJOR.MINOR.PATCH format.
     */
    private static Optional<Version> parseVersion(String value) {

        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        String normalized = value.trim()
                .replaceFirst("^[vV]", "");

        String[] parts = normalized.split("\\.", -1);

        if (parts.length != 3) {
            return Optional.empty();
        }

        try {
            return Optional.of(new Version(
                    Long.parseLong(parts[0]),
                    Long.parseLong(parts[1]),
                    Long.parseLong(parts[2])));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private static String getString(JsonObject object, String name) {

        JsonElement value = object.get(name);

        return value == null || value.isJsonNull()
                ? ""
                : value.getAsString();
    }

    private static boolean getBoolean(JsonObject object, String name) {

        JsonElement value = object.get(name);

        return value != null
                && !value.isJsonNull()
                && value.getAsBoolean();
    }

    private static boolean isCheckDue() {

        try {
            long lastCheck = Preferences
                    .userNodeForPackage(UpdateChecker.class)
                    .getLong(LAST_CHECK_KEY, 0);

            return System.currentTimeMillis() - lastCheck
                    >= CHECK_INTERVAL;

        } catch (RuntimeException exception) {
            LOG.log(Level.FINE,
                    "Could not read update check preferences.",
                    exception);

            return true;
        }
    }

    private static void saveLastCheck() {

        try {
            Preferences.userNodeForPackage(UpdateChecker.class)
                    .putLong(LAST_CHECK_KEY, System.currentTimeMillis());

        } catch (RuntimeException exception) {
            LOG.log(Level.FINE,
                    "Could not save update check preferences.",
                    exception);
        }
    }

    private record Version(
            long major,
            long minor,
            long patch) implements Comparable<Version> {

        @Override
        public int compareTo(Version other) {

            int result = Long.compare(major, other.major);

            if (result == 0) {
                result = Long.compare(minor, other.minor);
            }

            if (result == 0) {
                result = Long.compare(patch, other.patch);
            }

            return result;
        }
    }
}
