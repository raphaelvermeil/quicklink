package com.quicklink.worker;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The worker's narrow view of media_asset: a handful of status transitions.
 * Status values mirror app-service's MediaStatus enum, which stores enum names
 * as strings — so these literals and that enum must stay in step.
 */
@Component
class MediaAssetStore {

    private final JdbcTemplate jdbc;

    MediaAssetStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<String> findStatus(long assetId) {
        return jdbc.query("SELECT status FROM media_asset WHERE id = ?",
                        rs -> rs.next() ? Optional.of(rs.getString(1)) : Optional.<String>empty(),
                        assetId);
    }

    void markProcessing(long assetId) {
        jdbc.update("UPDATE media_asset SET status = 'PROCESSING', updated_at = now() WHERE id = ?",
                assetId);
    }

    void markReady(long assetId, String processedKey, long processedBytes) {
        jdbc.update("""
                UPDATE media_asset
                   SET status = 'READY', processed_key = ?, processed_bytes = ?,
                       error_message = NULL, updated_at = now()
                 WHERE id = ?
                """, processedKey, processedBytes, assetId);
    }

    /**
     * Guarded: a retry can succeed after the dead-letter copy is already in flight,
     * and overwriting READY with FAILED would be a lie.
     */
    void markFailedIfNotReady(long assetId, String errorMessage) {
        jdbc.update("""
                UPDATE media_asset
                   SET status = 'FAILED', error_message = ?, updated_at = now()
                 WHERE id = ? AND status <> 'READY'
                """, errorMessage, assetId);
    }

    void markFailed(long assetId, String errorMessage) {
        jdbc.update("""
                UPDATE media_asset
                   SET status = 'FAILED', error_message = ?, updated_at = now()
                 WHERE id = ?
                """, errorMessage, assetId);
    }
}
