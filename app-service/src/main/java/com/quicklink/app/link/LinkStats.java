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
@Table(name = "link_stats")
@Getter
@Setter
@NoArgsConstructor
public class LinkStats {

    @Id
    private String shortCode;

    @Column(nullable = false)
    private Long clicks;

    @Column(insertable = false, updatable = false)
    private Instant updatedAt;
}
