package com.olyseus.i_seed.control;

import java.util.concurrent.atomic.AtomicReference;

public class PipelineStatus {
    enum Status {
        DISCONNECTED,
        CONNECTION_IN_PROGRESS,
        CONNECTED,
        DISCONNECTION_IN_PROGRESS
    }

    private AtomicReference<Status> status = new AtomicReference<Status>(Status.DISCONNECTED);

    public boolean isConnectionInProgress() {
        return status.get() == Status.CONNECTION_IN_PROGRESS;
    }

    public boolean isDisconnectionInProgress() {
        return status.get() == Status.DISCONNECTION_IN_PROGRESS;
    }

    public boolean isConnected() {
        return status.get() == Status.CONNECTED;
    }

    public void setConnectionInProgress() {
        status.set(Status.CONNECTION_IN_PROGRESS);
    }

    public void setDisconnectionInProgress() {
        status.set(Status.DISCONNECTION_IN_PROGRESS);
    }

    public void setConnected(boolean connected) {
        if (connected) {
            status.set(Status.CONNECTED);
        } else {
            status.set(Status.DISCONNECTED);
        }
    }
}
