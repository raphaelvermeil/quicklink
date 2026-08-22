package com.quicklink.app.link;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "link")
@Getter
@Setter
@NoArgsConstructor
public class Link {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "link_seq")
    @SequenceGenerator(name = "link_seq", sequenceName = "link_id_seq", allocationSize = 1)
    private Long id;

    @Column(nullable = false, unique = true, length = 16)
    private String shortCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private TargetType targetType;

    private String targetUrl;

    private Long mediaAssetId;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    private Instant expiresAt;
}
