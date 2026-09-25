package com.wechat.wechatsummary.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ResizableSemaphoreTest {

    @Test
    void startsWithGivenPermits() {
        ResizableSemaphore gate = new ResizableSemaphore(3);
        assertEquals(3, gate.permits());
    }

    @Test
    void clampsToAtLeastOnePermit() {
        assertEquals(1, new ResizableSemaphore(0).permits());
        assertEquals(1, new ResizableSemaphore(-5).permits());
    }

    @Test
    void acquireAndReleaseCycle() {
        ResizableSemaphore gate = new ResizableSemaphore(1);
        gate.acquireUninterruptibly();
        gate.release();
        gate.acquireUninterruptibly();
        gate.release();
    }

    @Test
    void raisingPermitsWakesBlockedAcquirers() throws InterruptedException {
        ResizableSemaphore gate = new ResizableSemaphore(1);
        gate.acquireUninterruptibly(); // exhaust the single permit

        AtomicBoolean acquired = new AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            gate.acquireUninterruptibly();
            acquired.set(true);
            gate.release();
            done.countDown();
        });
        waiter.setDaemon(true);
        waiter.start();

        assertTrue(!done.await(200, TimeUnit.MILLISECONDS), "waiter should block while exhausted");
        gate.release(); // free the initial permit for the waiter
        assertTrue(done.await(5, TimeUnit.SECONDS), "waiter should proceed after release");
        assertTrue(acquired.get());
    }

    @Test
    void setPermitsTakesEffectImmediately() {
        ResizableSemaphore gate = new ResizableSemaphore(2);
        gate.setPermits(7);
        assertEquals(7, gate.permits());
        gate.setPermits(0);
        assertEquals(1, gate.permits());
    }
}
