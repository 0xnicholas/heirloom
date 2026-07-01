package com.heirloom.cdc.repository;

import com.heirloom.cdc.domain.CdcSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;

@Repository
public class CdcSourceRepository {

    private final CdcSourceJpaRepository jpa;

    @PersistenceContext
    private EntityManager em;

    public CdcSourceRepository(CdcSourceJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Transactional
    public CdcSource save(CdcSource source) {
        return jpa.save(source);
    }

    public Optional<CdcSource> findByName(String name) {
        return jpa.findByName(name);
    }

    public List<CdcSource> findAll() {
        return jpa.findAll();
    }

    @Transactional
    public void delete(CdcSource source) {
        jpa.delete(source);
    }
}
