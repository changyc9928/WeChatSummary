package com.wechat.wechatsummary.util;

/**
 * A semaphore whose permit count can be changed at runtime.
 *
 * <p>Used for the AI provider throttle so the preprocessing concurrency settings edited in
 * the UI take effect without restarting the backend. Unlike
 * {@link java.util.concurrent.Semaphore}, raising the limit wakes waiting acquirers
 * immediately.
 */
public final class ResizableSemaphore {

    private int permits;
    private int used;

    public ResizableSemaphore(int permits) {
        this.permits = Math.max(1, permits);
        this.used = 0;
    }

    public synchronized void setPermits(int permits) {
        this.permits = Math.max(1, permits);
        notifyAll();
    }

    public synchronized int permits() {
        return permits;
    }

    public void acquireUninterruptibly() {
        synchronized (this) {
            boolean interrupted = false;
            while (used >= permits) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            used++;
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public synchronized void release() {
        if (used > 0) {
            used--;
        }
        notifyAll();
    }
}
