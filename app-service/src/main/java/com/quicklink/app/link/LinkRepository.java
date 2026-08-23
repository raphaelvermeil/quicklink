package com.quicklink.app.link;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Query;



public interface LinkRepository extends JpaRepository<Link, Long> {

    Optional<Link> findByShortCode(String shortCode);

    @Query(value = "SELECT nextval('link_code_seq')", nativeQuery = true)
    long nextCodeValue();
}

