/*
 * Copyright (C) 2018 Bandyer S.r.l. All Rights Reserved.
 * See LICENSE.txt for licensing information
 */

package com.bandyer.demo_communication_center

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.bandyer.communication_center.live_pointer.LivePointer
import com.bandyer.communication_center.live_pointer.PointerEventListener
import com.bandyer.communication_center.live_pointer.model.PointerEvent
import com.bandyer.android_audiosession.AudioOutputDeviceType
import com.bandyer.android_audiosession.AudioSession
import com.bandyer.android_audiosession.AudioSessionOptions
import com.bandyer.android_audiosession.audiosession.AudioSessionListener
import com.bandyer.android_common.proximity_listener.ProximitySensor
import com.bandyer.android_common.proximity_listener.ProximitySensorListener
import com.bandyer.communication_center.call.*
import com.bandyer.communication_center.call.participant.CallParticipant
import com.bandyer.communication_center.call.participant.OnCallParticipantObserver
import com.bandyer.communication_center.call_client.CallClient
import com.bandyer.core_av.OnStreamListener
import com.bandyer.core_av.Stream
import com.bandyer.core_av.capturer.CameraCapturer
import com.bandyer.core_av.capturer.Capturer
import com.bandyer.core_av.capturer.capturer
import com.bandyer.core_av.capturer.video.provider.camera.CameraVideoFeeder
import com.bandyer.core_av.publisher.Publisher
import com.bandyer.core_av.publisher.RecordingException
import com.bandyer.core_av.publisher.RecordingListener
import com.bandyer.core_av.room.*
import com.bandyer.core_av.subscriber.Subscriber
import com.bandyer.core_av.view.BandyerView
import com.bandyer.core_av.view.StreamView
import com.bandyer.sdk_design.call.widgets.LivePointerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.activity_call.*
import kotlinx.android.synthetic.main.activity_call_actions.*

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
class CallActivity : BaseActivity(), RoomObserver, OnCallEventObserver, ProximitySensorListener {

    private var room: Room? = null

    private var capturerAV: CameraCapturer? = null
    private var capturerAudio: Capturer<*, *>? = null
    private var publisher: Publisher? = null
    private var isVolumeMuted = false
    private var snackbar: Snackbar? = null

    override fun onProximitySensorChanged(isNear: Boolean) {
        Log.e("CallActivity", "sensor $isNear")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_call)

        // if you are interested only in the proximity sensor you may use our utility
        ProximitySensor.bind(this)

        // if you want to handle the different audio devices setup the AudioSession
        AudioSession.getInstance().startWithOptions(
                this,
                AudioSessionOptions.Builder()
                        // .disableAutomaticAudioDeviceChange()
                        .withDefaultSpeakerPhoneOutputHardWareDevice()
                        .build(),
                object : AudioSessionListener {
                    override fun onOutputDeviceConnected(oldAudioOutputDevice: AudioOutputDeviceType, connectedAudioOutputDevice: AudioOutputDeviceType, availableOutputs: List<AudioOutputDeviceType>) {
                        Log.e("Audio", "changed from old: " + oldAudioOutputDevice.name + " to connected: " + connectedAudioOutputDevice.name)
                        snackbar?.dismiss()
                        snackbar = Snackbar.make(hangup!!, connectedAudioOutputDevice.name, Snackbar.LENGTH_SHORT)
                        snackbar?.show()
                    }

                    override fun onOutputDeviceAttached(currentAudioOutputDevice: AudioOutputDeviceType, attachedAudioOutputDevice: AudioOutputDeviceType, availableOutputs: List<AudioOutputDeviceType>) {
                        Log.e("Audio", "current: " + currentAudioOutputDevice.name + " attached audioDevice: " + attachedAudioOutputDevice.name)
                    }

                    override fun onOutputDeviceDetached(currentAudioOutputDevice: AudioOutputDeviceType, detachedAudioOutputDevice: AudioOutputDeviceType, availableOutputs: List<AudioOutputDeviceType>) {
                        Log.e("Audio", "current: " + currentAudioOutputDevice.name + " detached audioDevice: " + detachedAudioOutputDevice.name)
                    }
                },
                object : ProximitySensorListener {
                    override fun onProximitySensorChanged(isNear: Boolean) {
                    }
                })

        hangup.setOnClickListener {
            onBackPressed()
        }

        /**
         * On click over the biggest bandyerView.
         * Show the call actions such as switch camera, toggle audio/video etc.
         */
        publisherView.setOnClickListener {
            val actions = findViewById<View>(R.id.actions)
            actions.visibility = if (actions.visibility == View.GONE) View.VISIBLE else View.GONE
        }

        /**
         * You may want to give the user an option to switch camera.
         * You need to interact with the capturer or the publisher view to mute audio or video of everyone.
         * Stream object contained in the publisher and subscriber contain information of the current audio/video status and other features.
         */
        switch_camera.setOnClickListener {
            capturerAV?.video?.frameProvider?.switchVideoFeeder()
        }

        /**
         * You may want to give the user an option to mute his camera feed.
         * You need to interact with the capturer or the publisher view to mute audio or video of everyone.
         * Stream object contained in the publisher and subscriber contain information of the current audio/video status and other features.
         */
        toggle_camera_off.setOnClickListener {
            it as FloatingActionButton
            val canVideo = CallClient.getInstance().ongoingCall?.options?.callType != CallType.AUDIO_ONLY && CallClient.getInstance().sessionUser?.canVideo == true
            capturerAV?.video?.videoEnabled = (capturerAV?.video?.videoEnabled != true && canVideo)
            if (CallClient.getInstance().ongoingCall?.options?.callType == CallType.AUDIO_UPGRADABLE && canVideo)
                CallClient.getInstance().ongoingCall?.upgradeCallType()
            setVideoCameraDrawable(capturerAV?.video?.videoEnabled == true)
        }

        /**
         * You may want to give the user an option to mute his audio.
         * You need to interact with the capturer or the publisher view to mute audio or video of everyone.
         * Stream object contained in the publisher and subscriber contain information of the current audio/video status and other features.
         */
        toggle_microphone_off.setOnClickListener {
            it as FloatingActionButton
            val audio = capturerAudio?.audio ?: capturerAV?.audio ?: return@setOnClickListener
            audio.audioEnabled = !audio.audioEnabled
            val color = if (audio.audioEnabled) Color.WHITE else ContextCompat.getColor(this, R.color.colorAccent)
            DrawableCompat.setTint(it.drawable, color)
        }

        /**
         * You may want to give the user an option to mute all the audio in case he needs to.
         * You need to interact with the room to mute audio or video of everyone.
         */
        toggle_volume_off.setOnClickListener {
            it as FloatingActionButton
            isVolumeMuted = !isVolumeMuted
            room?.muteAudioAllActors(false, isVolumeMuted)

            val color = if (isVolumeMuted) ContextCompat.getColor(this, R.color.colorAccent) else Color.WHITE
            DrawableCompat.setTint(it.drawable, color)
        }

        // Request the current call from the call client
        CallClient.getInstance().ongoingCall!!.addEventObserver(this)
        setVideoCameraDrawable(CallClient.getInstance().sessionUser!!.canVideo && CallClient.getInstance().ongoingCall!!.options?.callType == CallType.AUDIO_VIDEO)

        val token = intent.getStringExtra(ROOM_TOKEN)!!
        // Once we have the token we will start joining the virtual room.
        // This must be done only once.
        room = Room.get(RoomToken(token))
        room?.addRoomObserver(this)
        room?.join()

        @Suppress("EXPERIMENTAL_API_USAGE")
        LivePointer.startListeningToPointerEvents(CallClient.getInstance().ongoingCall!!, object : PointerEventListener {
            override fun onPointerEvent(call: Call, pointerEvent: PointerEvent) {
                val bandyerView = when {
                    (publisher!!.stream!!.streamId == pointerEvent.streamId) -> publisherView
                    else -> {
                        (0 until subscribersListView.childCount)
                                .map { subscribersListView.getChildAt(it) as BandyerView }
                                .first { it.tag == pointerEvent.streamId }
                    }
                } as FrameLayout

                val pointerViewTag = getPointerViewTag(pointerEvent)

                (bandyerView.findViewWithTag(pointerViewTag)
                        ?: LivePointerView(this@CallActivity).apply {
                            this.tag = pointerViewTag
                            val video = bandyerView.findViewById<View>(R.id.video)
                            bandyerView.addView(this, FrameLayout.LayoutParams(video.width, video.height).apply {
                                this.gravity = Gravity.CENTER
                            })
                        }).let { livePointerView ->

                    livePointerView.visibility = View.VISIBLE
                    livePointerView.updateLabelText(pointerEvent.requester.user.userAlias!!)

                    val horizontalPercentage =
                            if (publisher!!.stream!!.streamId == pointerEvent.streamId)
                                100 - pointerEvent.pointerPosition.horizontalPercent
                            else pointerEvent.pointerPosition.horizontalPercent

                    livePointerView.updateLivePointerPosition(
                            horizontalPercentage,
                            pointerEvent.pointerPosition.verticalPercent)
                }
            }

            override fun onPointerIdle(call: Call, lastPointerEvent: PointerEvent) {
                (when {
                    (publisher!!.stream!!.streamId == lastPointerEvent.streamId) -> publisherView
                    else -> {
                        (0 until subscribersListView.childCount)
                                .map { subscribersListView.getChildAt(it) as BandyerView }
                                .first { it.tag == lastPointerEvent.streamId }
                    }
                } as FrameLayout).findViewWithTag<LivePointerView>(getPointerViewTag(lastPointerEvent))?.hide()
            }
        })
    }

    private fun getPointerViewTag(pointerEvent: PointerEvent) = "LivePointerView.${pointerEvent.streamId}.${pointerEvent.requester.user.userAlias}"

    private fun setVideoCameraDrawable(enabled: Boolean) {
        val color = if (enabled) Color.WHITE else ContextCompat.getColor(this, R.color.colorAccent)
        DrawableCompat.setTint(toggle_camera_off.drawable, color)
    }

    override fun onPause() {
        snackbar?.dismiss()
        super.onPause()
    }

    override fun onDestroy() {
        room?.leave()
        super.onDestroy()
    }

    override fun onBackPressed() {
        finish()
    }

    override fun finish() {
        setResult(Activity.RESULT_OK)
        room?.leave()
        super.finish()
    }

    private fun getDp(size: Int): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, size.toFloat(), resources.displayMetrics).toInt()
    }

    /**
     * When the created publisher has been added to the room this method will be invoked.
     *
     *
     * Here is a good place where we can request to record our video which we are publishing.
     *
     * @param publisher Publisher
     */
    override fun onLocalPublisherJoined(publisher: Publisher) {
        val call = CallClient.getInstance().ongoingCall
        if (call?.options?.record == true) {
            publisher.startRecording(object : RecordingListener {
                override fun onSuccess(recordId: String, isRecording: Boolean) {
                    Snackbar.make(publisherView, "Recording has been started", Snackbar.LENGTH_SHORT).show()
                }

                override fun onError(recordId: String?, isRecording: Boolean, reason: RecordingException) {
                    Snackbar.make(publisherView, "Recording error" + reason.localizedMessage, Snackbar.LENGTH_SHORT).show()
                }
            })
        }
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
    override fun onRemotePublisherJoined(stream: Stream) {
        val subscriber = room!!.create(stream)
        //subscriber.addSubscribeObserver();
        room?.subscribe(subscriber)

        // set the view where the stream will be played
        val subscriberView = BandyerView(this)
        subscriberView.tag = stream.streamId
        subscriberView.bringToFront(true)
        val size = getDp(120)

        // add the view to the view-list of subscribers
        subscribersListView!!.addView(subscriberView, ViewGroup.LayoutParams(size, size))

        // bind the subscriber to a view, where the video/audio will be played
        subscriber.setView(subscriberView, object : OnStreamListener {

            override fun onReadyToPlay(view: StreamView, stream: Stream) {
                view.play(stream)
            }
        })
    }

    override fun onLocalPublisherRemoved(publisher: Publisher) {
        room?.unpublish(publisher)
    }

    override fun onRoomReconnecting() {
        Log.d("CallActivity", "onRoomReconnecting ")
    }

    override fun onRoomStateChanged(state: RoomState) {
        Log.d("CallActivity", "onRoomStateChanged " + state.name)
    }

    /**
     * One of the participants has left.
     * We need to unsubscribe from their stream, as they will not stream anything anymore
     */
    override fun onRemotePublisherLeft(stream: Stream) {
        subscribersListView.findViewWithTag<BandyerView>(stream.streamId)?.let {
            subscribersListView.removeView(it)
        }
        val subscriber = room!!.getSubscriber(stream) ?: return
        room?.unsubscribe(subscriber)
    }

    /**
     * When the room is successfully connected, we are ready to publish our audio and video streams.
     * We create a publisher that is responsible for interacting with the room.
     * The publisher will take care of streaming our video and audio feeds to the other participants in the room.
     */
    override fun onRoomEnter() {
        val call = CallClient.getInstance().ongoingCall
        val callType = call?.options?.callType
        val capturer = if (callType == CallType.AUDIO_ONLY) {
            capturerAudio = capturer(this) {
                audio = default()
            }
            capturerAudio!!
        } else {
            capturerAV = capturer(this) {
                audio = default()
                video = camera()
            }
            capturerAV!!.video!!.videoEnabled = (callType == CallType.AUDIO_VIDEO && CallClient.getInstance().sessionUser?.canVideo == true)
            publisherView.setMirror(capturerAV!!.video!!.frameProvider.currentCameraFeeder is CameraVideoFeeder.FRONT_CAMERA)
            capturerAV!!
        }

        capturer.start()
        // Once a publisher has been setup, we must publish its stream in the room.
        // Publishing is an asynchronous process. If something goes wrong while starting the publish process, an error will be set in the error method of the observers.
        // Otherwise if the publish process can be started, any error occurred will be reported
        // to the observers registered on the publisher object.

        publisher = room!!.create(CallClient.getInstance().sessionUser!!)
                // .addPublisherObserver()
                .setCapturer(capturer)

        room?.publish(publisher!!)

        // bind the publisher to a view, where the video/audio will be played
        publisher?.setView(publisherView, object : OnStreamListener {

            override fun onReadyToPlay(view: StreamView, stream: Stream) {
                view.play(stream)
            }
        })

        // This statement is needed to subscribe as a participant observer.
        // Once we are subscribed, we will be notified anytime a participant status changes
        call?.participants?.addObserver(object : OnCallParticipantObserver {
            override fun onCallParticipantStatusChanged(participant: CallParticipant) {
                Snackbar.make(publisherView, participant.status.name, Snackbar.LENGTH_LONG).show()
            }

            override fun onCallParticipantUpgradedCallType(participant: CallParticipant, callType: CallType) {
                Log.d("CallActivity", "onCallParticipantUpgradedCallType $participant sessionUser = ${CallClient.getInstance().sessionUser}")
                if (participant.user.userAlias == publisher?.user?.userAlias)
                    capturerAV?.video?.videoEnabled = (callType == CallType.AUDIO_VIDEO)
            }
        })
    }

    /**
     * When the room disconnects or there was an error this method will be invoked on any room observer.
     * Here the activity will be dismissed.
     * If your navigation flow is different you could for example prompt an error message to the user.
     */
    override fun onRoomExit() {
        onBackPressed()
    }

    override fun onRoomError(reason: String) {
        onBackPressed()
    }

    override fun onCallUpgraded() {
    }

    override fun onCallStarted(call: Call, roomToken: RoomToken) {
        Log.d("CallActivity", "onCallStarted $roomToken")
    }

    override fun onCallEnded(call: Call, callEndReason: Call.EndReason) {
        Log.d("CallActivity", "onCallEnded $callEndReason")
        onBackPressed()
    }

    override fun onCallError(call: Call, reason: CallException) {
        Log.e("CallActivity", "onCallError $reason")
        showErrorDialog("${reason.message}")
        if (reason !is CallUpgradeException)
            onBackPressed()
    }

    override fun onCallStatusChanged(call: Call, status: Call.Status) {
        Log.d("CallActivity", "onCallStatusChanged " + status.name)
    }

    override fun onLocalSubscriberJoined(subscriber: Subscriber) {
        Log.d("CallActivity", "onLocalSubscriberJoined $subscriber")
    }

    override fun onLocalSubscriberRemoved(subscriber: Subscriber) {
        Log.d("CallActivity", "onLocalSubscriberRemoved $subscriber")
    }

    override fun onLocalSubscriberUpdateStream(subscriber: Subscriber) {
        Log.d("CallActivity", "onLocalSubscriberUpdateStream $subscriber")
    }

    override fun onRemotePublisherUpdateStream(stream: Stream) {
        Log.d("CallActivity", "onRemotePublisherUpdateStream $stream")
    }

    override fun onLocalPublisherUpdateStream(publisher: Publisher) {
        Log.d("CallActivity", "onLocalPublisherUpdateStream $publisher")
    }

    override fun onRoomActorUpdateStream(roomActor: RoomActor) {
        Log.d("CallActivity", "onRoomActorUpdateStream $roomActor")
    }

    companion object {

        const val ROOM_TOKEN = "token"

        fun show(activity: BaseActivity, token: String) {
            val intent = Intent(activity, CallActivity::class.java)
            intent.putExtra(ROOM_TOKEN, token)

            activity.startActivityForResult(intent, 0)
        }
    }
}
