package me.arrow.core.thread;

import lombok.Getter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A simple class to track the amount of profiles using a selected thread.
 */
public class ProfileThread {

    private final ExecutorService thread = Executors.newSingleThreadExecutor();

    @Getter
    private int profileCount;

    public void execute(Runnable runnable) {

        //Fixes strange issues when the thread has been shut down and the packet has been delayed
        if (this.thread.isShutdown()) return;

        this.thread.execute(runnable);
    }

    public ProfileThread incrementAndGet() {

        this.profileCount++;

        return this;
    }

    public void decrement() {
        this.profileCount--;
    }

    public ProfileThread shutdownThread() {

        this.thread.shutdownNow();

        return this;
    }
}
