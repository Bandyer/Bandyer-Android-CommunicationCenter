/*
 * Copyright (C) 2018 Bandyer S.r.l. All Rights Reserved.
 * See LICENSE.txt for licensing information
 */

package com.bandyer.demo_communication_center;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v4.content.ContextCompat;
import android.support.v4.graphics.drawable.DrawableCompat;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;

import com.bandyer.communication_center.call.Call;
import com.bandyer.communication_center.call.OnCallEventObserver;
import com.bandyer.communication_center.call_client.CallClient;
import com.bandyer.communication_center.call_client.CallException;
import com.bandyer.core_av.OnStreamListener;
import com.bandyer.core_av.Stream;
import com.bandyer.core_av.audiosession.AudioOutputDeviceType;
import com.bandyer.core_av.audiosession.AudioSession;
import com.bandyer.core_av.audiosession.AudioSessionListener;
import com.bandyer.core_av.audiosession.AudioSessionOptions;
import com.bandyer.core_av.capturer.CapturerAV;
import com.bandyer.core_av.publisher.Publisher;
import com.bandyer.core_av.room.Room;
import com.bandyer.core_av.room.RoomObserver;
import com.bandyer.core_av.room.RoomState;
import com.bandyer.core_av.room.RoomToken;
import com.bandyer.core_av.subscriber.Subscriber;
import com.bandyer.core_av.utils.proximity_listener.ProximitySensorListener;
import com.bandyer.core_av.view.BandyerView;
import com.bandyer.core_av.view.OnViewStatusListener;

import org.jetbrains.annotations.NotNull;

import java.util.List;

import butterknife.BindView;
import butterknife.OnClick;

/**
 * This activity will take care of handling the actual video call process.
 * The steps taken so far, were needed to contact the other participants and manage the call signaling. From this moment on, the actual
 * call can begin and we can start talking and seeing the other participants.
 * <p>
 * Please note, that before reaching to this point if camera and microphone permissions were not granted, the app will crash as soon as the sdk tries to access the camera or the microphone.
 * Please also note that you must take care of camera and microphone permissions. If the user doesn't grant or revokes the permission
 * to access one of those two resources, the sdk will not complain and local video or audio streams will not be sent to the remote parties.
 * So you must check that the user has granted camera and microphone permissions before joining a room.
 *
 * @author kristiyan
 */
public class CallActivity extends BaseActivity implements RoomObserver, OnCallEventObserver {

    private static final String ROOM_TOKEN = "token";

    @BindView(R.id.subscribersListView)
    LinearLayout subscribersListView;

    @BindView(R.id.publisherView)
    BandyerView publisherView;

    private Room room;
    private CapturerAV capturerAV;
    private Publisher publisher;
    private boolean isVolumeMuted = false;

    public static void show(BaseActivity activity, String token) {
        Intent intent = new Intent(activity, CallActivity.class);
        intent.putExtra(ROOM_TOKEN, token);
        activity.startActivityForResult(intent, 0);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call);

        // if you want to handle the different audio devices setup the AudioSession
        AudioSession.getInstance().startWithDefaultOptions(this, new AudioSessionListener() {
            @Override
            public void onOutputDeviceConnected(AudioOutputDeviceType audioOutputDeviceType, AudioOutputDeviceType audioOutputDeviceType1, List<? extends AudioOutputDeviceType> list) {

            }

            @Override
            public void onOutputDeviceAttached(AudioOutputDeviceType audioOutputDeviceType, AudioOutputDeviceType audioOutputDeviceType1, List<? extends AudioOutputDeviceType> list) {

            }

            @Override
            public void onOutputDeviceDetached(AudioOutputDeviceType audioOutputDeviceType, AudioOutputDeviceType audioOutputDeviceType1, List<? extends AudioOutputDeviceType> list) {

            }
        });

        // Request the current call from the call client
        Call call = CallClient.getInstance().getOngoingCall();
        if (call != null)
            call.addEventObserver(this);

        String token = getIntent().getStringExtra(ROOM_TOKEN);
        // Once we have the token we will start joining the virtual room.
        // This must be done only once.
        room = new Room(new RoomToken(token));
        room.addRoomObserver(this);
        room.join();
    }

    /**
     * on Stop leave room
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // leave room
        if (room != null)
            room.leave();
    }

    @Override
    public void finish() {
        setResult(Activity.RESULT_OK);
        // leave room
        if (room != null)
            room.leave();
        super.finish();
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    @OnClick(R.id.hangup)
    void onHangUp() {
        onBackPressed();
    }

    /**
     * On click over the biggest bandyerView.
     * Show the call actions such as switch camera, toggle audio/video etc.
     */
    @OnClick(R.id.publisherView)
    void onShowCallActions() {
        View actions = findViewById(R.id.actions);
        actions.setVisibility(actions.getVisibility() == View.GONE ? View.VISIBLE : View.GONE);
    }

    /**
     * You may want to give the user an option to switch camera.
     * You need to interact with the capturer or the publisher view to mute audio or video of everyone.
     * Stream object contained in the publisher and subscriber contain information of the current audio/video status and other features.
     */
    @OnClick(R.id.switch_camera)
    void onSwitchCamera(FloatingActionButton view) {
        if (capturerAV == null)
            return;
        capturerAV.switchVideoFeeder();
    }

    /**
     * You may want to give the user an option to mute his camera feed.
     * You need to interact with the capturer or the publisher view to mute audio or video of everyone.
     * Stream object contained in the publisher and subscriber contain information of the current audio/video status and other features.
     */
    @OnClick(R.id.toggle_camera_off)
    void onToggleCameraOff(FloatingActionButton view) {
        if (capturerAV == null || publisher == null || publisher.getStream() == null)
            return;

        capturerAV.setVideoEnabled(!capturerAV.isVideoEnabled());
        int color = capturerAV.isVideoEnabled() ? Color.WHITE : ContextCompat.getColor(this, R.color.colorAccent);
        DrawableCompat.setTint(view.getDrawable(), color);
    }

    /**
     * You may want to give the user an option to mute his audio.
     * You need to interact with the capturer or the publisher view to mute audio or video of everyone.
     * Stream object contained in the publisher and subscriber contain information of the current audio/video status and other features.
     */
    @OnClick(R.id.toggle_microphone_off)
    void onToggleMicOff(FloatingActionButton view) {
        if (capturerAV == null || publisher == null || publisher.getStream() == null)
            return;

        capturerAV.setAudioEnabled(!capturerAV.isAudioEnabled());
        int color = capturerAV.isAudioEnabled() ? Color.WHITE : ContextCompat.getColor(this, R.color.colorAccent);
        DrawableCompat.setTint(view.getDrawable(), color);
    }

    /**
     * You may want to give the user an option to mute all the audio in case he needs to.
     * You need to interact with the room to mute audio or video of everyone.
     */
    @OnClick(R.id.toggle_volume_off)
    void onToggleVolumeOff(FloatingActionButton view) {
        if (capturerAV == null || publisher == null || publisher.getStream() == null)
            return;

        isVolumeMuted = !isVolumeMuted;
        room.muteAllAudio(isVolumeMuted);
        int color = isVolumeMuted ? ContextCompat.getColor(this, R.color.colorAccent) : Color.WHITE;
        DrawableCompat.setTint(view.getDrawable(), color);
    }

    private int getDp(int size) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, size, getResources().getDisplayMetrics());
    }

    /**
     * When a new stream is added to the room this method will be invoked.
     * Here we have the chance to subscribe to the stream just added.
     * If a remote stream is added to the room we subscribe to it, creating a subscriber object that is responsible for handling the process
     * of subscribing to the remote audio and video feeds.
     * <p>
     * Once a subscriber has been setup, we signal the room we are ready to subscribe to the remote stream.
     * Subscribing to a remote stream is an asynchronous process. If something goes wrong while starting the subscribe process an error will be
     * notified via the room observer. Otherwise, the subscribing process is started and any error occurring from this moment on
     * will be reported to the observers registered on the subscriber object.
     */
    @Override
    public void onRemotePublisherJoined(Stream stream) {
        final Subscriber subscriber = new Subscriber(stream);
        //subscriber.addSubscribeObserver();
        room.subscribe(subscriber);

        // set the view where the stream will be played
        final BandyerView subscriberView = new BandyerView(this);
        subscriberView.setTag(stream.getStreamId());
        subscriberView.bringToFront(true);
        int size = getDp(120);


        // add the view to the view-list of subscribers
        subscribersListView.addView(subscriberView, new LinearLayout.LayoutParams(size, size));

        // bind the subscriber to a view, where the video/audio will be played
        subscriber.setView(subscriberView, new OnStreamListener() {

            @Override
            public void onReadyToPlay(@NonNull Stream stream) {
                subscriberView.play(stream);
            }
        });
    }

    /**
     * One of the participants has left.
     * We need to unsubscribe from their stream, as they will not stream anything anymore
     */
    @Override
    public void onRemotePublisherLeft(Stream stream) {
        View view = subscribersListView.findViewWithTag(stream.getStreamId());
        if (view != null)
            subscribersListView.removeView(view);

        Subscriber subscriber = room.getSubscriber(stream);
        if (subscriber == null)
            return;
        room.unsubscribe(subscriber);
    }

    /**
     * When the room is successfully connected, we are ready to publish our audio and video streams.
     * We create a publisher that is responsible for interacting with the room.
     * The publisher will take care of streaming our video and audio feeds to the other participants in the room.
     */
    @Override
    public void onRoomEnter() {
        capturerAV = new CapturerAV(this);
        capturerAV.start();
        // Once a publisher has been setup, we must publish its stream in the room.
        // Publishing is an asynchronous process. If something goes wrong while starting the publish process, an error will be set in the error method of the observers.
        // Otherwise if the publish process can be started, any error occurred will be reported
        // to the observers registered on the publisher object.

        publisher = new Publisher(CallClient.getInstance().getSessionUser())
                // .addPublisherObserver()
                .setCapturer(capturerAV);

        room.publish(publisher);

        // bind the publisher to a view, where the video/audio will be played
        publisher.setView(publisherView, new OnStreamListener() {

            @Override
            public void onReadyToPlay(@NonNull Stream stream) {
                publisherView.play(stream);
            }
        });
    }

    /**
     * When the room disconnects or there was an error this method will be invoked on any room observer.
     * Here the activity will be dismissed.
     * If your navigation flow is different you could for example prompt an error message to the user.
     */
    @Override
    public void onRoomExit() {
        onBackPressed();
    }

    @Override
    public void onRoomError(String s) {
        onBackPressed();
    }


    @Override
    public void onCallStarted(@NotNull Call call, @NotNull RoomToken roomToken) {
        Log.d("CallActivity", "onCallStarted " + roomToken);
    }

    @Override
    public void onCallEnded(@NotNull Call call, @NotNull Call.EndReason callEndReason) {
        Log.d("CallActivity", "onCallEnded " + callEndReason);
        CallActivity.this.onBackPressed();
    }

    @Override
    public void onCallError(@NotNull Call call, @NotNull CallException reason) {
        Log.e("CallActivity", "onCallError " + reason);
        CallActivity.this.onBackPressed();
    }

    @Override
    public void onCallStatusChanged(@NotNull Call call, @NotNull Call.Status status) {
        Log.d("CallActivity", "onCallStatusChanged " + status.name());
    }

    @Override
    public void onLocalPublisherRemoved(Publisher publisher) {
        room.unpublish(publisher);
    }

    @Override
    public void onRoomReconnecting() {
        Log.d("CallActivity", "onRoomReconnecting ");
    }

    @Override
    public void onRoomStateChanged(RoomState roomState) {
        Log.d("CallActivity", "onRoomStateChanged " + roomState.name());
    }
}
