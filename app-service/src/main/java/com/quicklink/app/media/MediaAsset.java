package com.quicklink.app.media;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "media_asset")
@Getter
@Setter
@NoArgsConstructor
public class MediaAsset {

    /**
     * IDENTITY, not SEQUENCE. The id is needed immediately AFTER the insert (to link
     * the short code to this asset and to publish the job), so Hibernate issuing the
     * INSERT at persist() time is exactly the behaviour we want here.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private MediaStatus status;

    @Column(nullable = false, length = 64)
    private String contentType;

    @Column(nullable = false)
    private String originalKey;

    private String processedKey;

    private Long originalBytes;

    private Long processedBytes;

    private String errorMessage;

    /** Written by Postgres via DEFAULT now(), same as link.created_at. */
    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    /** Status changes several times, so this has to move on every write, not just insert. */
    @UpdateTimestamp
    private Instant updatedAt;
}
