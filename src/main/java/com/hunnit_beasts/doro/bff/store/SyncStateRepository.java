package com.hunnit_beasts.doro.bff.store;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncStateRepository extends JpaRepository<SyncStateEntity, String> {
}
