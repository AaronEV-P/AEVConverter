package com.aaronev.aevconverter.update;

/**
 * Contains information about an available application update.
 */
public record UpdateInfo(
        String currentVersion,
        String version,
        String releaseUrl) {

}
