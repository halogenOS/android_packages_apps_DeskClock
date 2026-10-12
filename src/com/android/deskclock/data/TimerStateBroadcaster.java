/*
 * SPDX-License-Identifier: Apache-2.0
 * SPDX-FileCopyrightText: 2026 The halogenOS Project
 */

package com.android.deskclock.data;

import static android.text.format.DateUtils.SECOND_IN_MILLIS;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Broadcasts the state of the timer that matters most, so that privileged system apps can
 * mirror it: right away on every change and once a second while it runs. Ringing timers come
 * first, then the running one that ends soonest, then paused ones. Once no timer is left, a
 * single broadcast with {@link #STATE_NONE} says so.
 */
final class TimerStateBroadcaster implements TimerListener {

    static final String ACTION_TIMER_STATE = "com.android.deskclock.action.TIMER_STATE";
    static final String PERMISSION_RECEIVE_TIMER_STATE =
            "com.android.deskclock.permission.RECEIVE_TIMER_STATE";

    static final String EXTRA_TIMER_ID = "timer_id";
    static final String EXTRA_STATE = "state";
    static final String EXTRA_ELAPSED_MILLIS = "elapsed_millis";
    static final String EXTRA_TOTAL_MILLIS = "total_millis";
    static final String EXTRA_SENT_AT = "sent_at";

    static final String STATE_NONE = "none";

    private final Context mContext;
    private final TimerModel mTimerModel;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTick = this::broadcast;

    private boolean mNoneSent;

    private TimerStateBroadcaster(Context context, TimerModel timerModel) {
        mContext = context;
        mTimerModel = timerModel;
    }

    /** Starts broadcasting the state of the timers in {@code timerModel}. */
    static void start(Context context, TimerModel timerModel) {
        final TimerStateBroadcaster broadcaster = new TimerStateBroadcaster(context, timerModel);
        timerModel.addTimerListener(broadcaster);
        broadcaster.broadcast();
    }

    @Override
    public void timerAdded(Timer timer) {
        broadcast();
    }

    @Override
    public void timerUpdated(Timer before, Timer after) {
        broadcast();
    }

    @Override
    public void timerRemoved(Timer timer) {
        broadcast();
    }

    private void broadcast() {
        mHandler.removeCallbacks(mTick);
        final Timer timer = mostImportantTimer();
        if (timer == null) {
            if (!mNoneSent) {
                send(stateIntent(STATE_NONE));
                mNoneSent = true;
            }
            return;
        }

        mNoneSent = false;
        send(stateIntent(timer.getState().name().toLowerCase(Locale.ROOT))
                .putExtra(EXTRA_TIMER_ID, timer.getId())
                .putExtra(EXTRA_ELAPSED_MILLIS, timer.getElapsedTime())
                .putExtra(EXTRA_TOTAL_MILLIS, timer.getTotalLength()));
        if (timer.isRunning()) {
            mHandler.postDelayed(mTick, SECOND_IN_MILLIS);
        }
    }

    private Timer mostImportantTimer() {
        final List<Timer> active = mTimerModel.getTimers().stream()
                .filter(timer -> !timer.isReset())
                .collect(Collectors.toList());
        return active.isEmpty() ? null : Collections.min(active, Timer.EXPIRY_COMPARATOR);
    }

    private static Intent stateIntent(String state) {
        return new Intent(ACTION_TIMER_STATE)
                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_SENT_AT, DataModel.getDataModel().elapsedRealtime());
    }

    private void send(Intent intent) {
        mContext.sendBroadcast(intent, PERMISSION_RECEIVE_TIMER_STATE);
    }
}
