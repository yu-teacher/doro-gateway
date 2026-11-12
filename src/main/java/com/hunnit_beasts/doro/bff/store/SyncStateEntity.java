package com.hunnit_beasts.doro.bff.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 탈퇴 동기화가 어디까지 처리했는지(증분 조회 기준 시각). */
@Entity
@Table(name = "iam_sync_state")
public class SyncStateEntity {

    @Id
    @Column(length = 50)
    private String name;

    @Column(name = "cursor_at", nullable = false)
    private Instant cursorAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SyncStateEntity() {}

    public SyncStateEntity(String name, Instant cursorAt) {
        this.name = name;
        this.cursorAt = cursorAt;
        this.updatedAt = Instant.now();
    }

    public Instant getCursorAt() { return cursorAt; }

    public void advanceTo(Instant cursor) {
        this.cursorAt = cursor;
        this.updatedAt = Instant.now();
    }
}
