package org.kotsasmin.keyValueChecker.detector;

import com.github.retrooper.packetevents.util.Vector3i;
import org.bukkit.scheduler.BukkitTask;
import org.kotsasmin.keyValueChecker.data.TranslationCheckItem;

import java.util.List;

// data gia to trexwn check tou paikti
public class CheckData {
    private final int startIndex;
    private final List<TranslationCheckItem> currentKeys;
    private final Vector3i signLoc;
    private final BukkitTask timeoutTask;

    public CheckData(int startIndex, List<TranslationCheckItem> currentKeys, Vector3i signLoc, BukkitTask timeoutTask) {
        this.startIndex = startIndex;
        this.currentKeys = currentKeys;
        this.signLoc = signLoc;
        this.timeoutTask = timeoutTask;
    }

    public int getStartIndex() {
        return startIndex;
    }

    public List<TranslationCheckItem> getCurrentKeys() {
        return currentKeys;
    }

    public Vector3i getSignLoc() {
        return signLoc;
    }

    public BukkitTask getTimeoutTask() {
        return timeoutTask;
    }
}
