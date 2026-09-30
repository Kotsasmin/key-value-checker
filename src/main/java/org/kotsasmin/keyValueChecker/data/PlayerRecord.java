package org.kotsasmin.keyValueChecker.data;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class PlayerRecord {
    private final String playerName;
    private volatile long lastIncidentTime;
    private volatile String lastAction;
    // CopyOnWriteArrayList gia na min exoume ConcurrentModificationException sto netty thread
    private final CopyOnWriteArrayList<String> detectedMods;

    public PlayerRecord(String playerName, long lastIncidentTime, String lastAction, Collection<String> detectedMods) {
        this.playerName = playerName;
        this.lastIncidentTime = lastIncidentTime;
        this.lastAction = lastAction;
        this.detectedMods = new CopyOnWriteArrayList<>(detectedMods != null ? detectedMods : Collections.emptyList());
    }

    public String getPlayerName() {
        return playerName;
    }

    public long getLastIncidentTime() {
        return lastIncidentTime;
    }

    public void setLastIncidentTime(long lastIncidentTime) {
        this.lastIncidentTime = lastIncidentTime;
    }

    public String getLastAction() {
        return lastAction;
    }

    public void setLastAction(String lastAction) {
        this.lastAction = lastAction;
    }

    public List<String> getDetectedMods() {
        return detectedMods;
    }

    public boolean addDetectedMod(String mod) {
        return detectedMods.addIfAbsent(mod);
    }
}
