package com.psiphon3;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Pair;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.android.billingclient.api.Purchase;
import com.jakewharton.rxrelay2.BehaviorRelay;
import com.jakewharton.rxrelay2.PublishRelay;
import com.jakewharton.rxrelay2.Relay;
import com.psiphon3.TunnelState;
import com.psiphon3.billing.SubscriptionState;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.BiFunction;

import static android.content.Context.ACTIVITY_SERVICE;

public class TunnelServiceInteractor {
    private static final String SERVICE_STARTING_BROADCAST_INTENT = "SERVICE_STARTING_BROADCAST_INTENT";
    private Relay<TunnelState> tunnelStateRelay = BehaviorRelay.<TunnelState>create().toSerialized();
    private Relay<Boolean> dataStatsRelay = PublishRelay.<Boolean>create().toSerialized();
    private Relay<Boolean> knownRegionsRelay = PublishRelay.<Boolean>create().toSerialized();
    private Relay<Boolean> authorizationsRemovedRelay = PublishRelay.<Boolean>create().toSerialized();

    private Relay<SubscriptionState> subscriptionStatusPublishRelay = PublishRelay.<SubscriptionState>create().toSerialized();

    private final Messenger incomingMessenger = new Messenger(new IncomingMessageHandler(this));
    private Disposable restartServiceDisposable = null;

    private Rx2ServiceBindingFactory serviceBindingFactory;

    private boolean isPaused = true;

    public TunnelServiceInteractor(Context context) {
        // Listen to SERVICE_STARTING_BROADCAST_INTENT broadcast that may be sent by another instance
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(SERVICE_STARTING_BROADCAST_INTENT);
        BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action != null) {
                    if (action.equals(SERVICE_STARTING_BROADCAST_INTENT) && !isPaused) {
                        bindTunnelService(context, intent);
                    }
                }
            }
        };
        LocalBroadcastManager.getInstance(context).registerReceiver(broadcastReceiver, intentFilter);
        // Start an infinite subscription that will send most recent purchase data to the service
        // when every time tunnel state changes to "connected" so the tunnel has a
        // chance to update authorization and restart if the purchase is new.
        // NOTE: we assume there can be only one valid purchase and authorization at a time
        Observable.combineLatest(tunnelStateRelay, subscriptionStatusPublishRelay,
                ((BiFunction<TunnelState, SubscriptionState, Pair>) Pair::new))
                .switchMap(pair -> {
                    TunnelState s = (TunnelState) pair.first;
                    SubscriptionState subscriptionState = (SubscriptionState) pair.second;
                    if (subscriptionState.hasValidPurchase() && s.isRunning() && s.connectionData().isConnected()) {
                        return Observable.just(subscriptionState);
                    } else {
                        return Observable.empty();
                    }
                })
                .map(subscriptionState -> {
                    Purchase purchase = subscriptionState.purchase();
                    Bundle data = new Bundle();

                    data.putString(TunnelManager.DATA_PURCHASE_ID,
                            purchase.getSku());
                    data.putString(TunnelManager.DATA_PURCHASE_TOKEN,
                            purchase.getPurchaseToken());
                    data.putBoolean(TunnelManager.DATA_PURCHASE_IS_SUBSCRIPTION,
                            subscriptionState.status() != SubscriptionState.Status.HAS_TIME_PASS);
                    return data;
                })
                .doOnNext(data -> sendServiceMessage(TunnelManager.ClientToServiceMessage.PURCHASE.ordinal(), data))
                .subscribe();

    }

    public void resume(Context context) {
        isPaused = false;
        tunnelStateRelay.accept(TunnelState.unknown());
        String serviceName = getRunningService(context);
        if (serviceName != null) {
            final Intent bindingIntent = getVpnServiceIntent(context);
            bindTunnelService(context, bindingIntent);
        } else {
            tunnelStateRelay.accept(TunnelState.stopped());
        }
    }

    public void pause(Context context) {
        isPaused = true;
        tunnelStateRelay.accept(TunnelState.unknown());
        if (serviceBindingFactory != null) {
            sendServiceMessage(TunnelManager.ClientToServiceMessage.UNREGISTER.ordinal(), null);
            serviceBindingFactory.unbind(context);
        }
    }

    public void startTunnelService(Context context, TunnelManager.Config tunnelConfig) {
        tunnelStateRelay.accept(TunnelState.unknown());
        Intent intent = getServiceIntent(context, tunnelConfig);
        context.startService(intent);
        // Send tunnel starting service broadcast to all instances so they all bind
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent.setAction(SERVICE_STARTING_BROADCAST_INTENT));
    }

    public void stopTunnelService() {
        tunnelStateRelay.accept(TunnelState.unknown());
        sendServiceMessage(TunnelManager.ClientToServiceMessage.STOP_SERVICE.ordinal(), null);
    }

    public void scheduleRunningTunnelServiceRestart(Context context, TunnelManager.Config tunnelConfig) {
        String runningService = getRunningService(context);
        if (runningService == null) {
            // There is no running service, do nothing.
            return;
        }
        commandTunnelRestart(getServiceIntent(context, tunnelConfig).getExtras());
    }

    public Flowable<TunnelState> tunnelStateFlowable() {
        return tunnelStateRelay
                .distinctUntilChanged()
                .toFlowable(BackpressureStrategy.LATEST);
    }

    public Flowable<Boolean> dataStatsFlowable() {
        return dataStatsRelay
                .toFlowable(BackpressureStrategy.LATEST);
    }

    public Flowable<Boolean> knownRegionsFlowable() {
        return knownRegionsRelay
                .toFlowable(BackpressureStrategy.LATEST);
    }

    public Flowable<Boolean> authorizationsRemovedFlowable() {
        return authorizationsRemovedRelay
                .toFlowable(BackpressureStrategy.LATEST);
    }

    public boolean isServiceRunning(Context context) {
        return getRunningService(context) != null;
    }

    private Intent getVpnServiceIntent(Context context) {
        return new Intent(context, TunnelVpnService.class);
    }

    private String getRunningService(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(ACTIVITY_SERVICE);
        if (manager == null) {
            return null;
        }
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (service.uid == android.os.Process.myUid() &&  isVpnService(service.service.getClassName())) {
                return service.service.getClassName();
            }
        }
        return null;
    }

    private boolean isVpnService(String className) {
        return Utils.hasVpnService() && TunnelVpnService.class.getName().equals(className);
    }

    private void commandTunnelRestart(Bundle data) {
        sendServiceMessage(TunnelManager.ClientToServiceMessage.RESTART_SERVICE.ordinal(), data);
    }

    private void scheduleCompleteServiceRestart(Context context, TunnelManager.Config tunnelConfig) {
        if (restartServiceDisposable != null && !restartServiceDisposable.isDisposed()) {
            // call in progress, do nothing
            return;
        }
        // Start observing service connection for disconnected message then command service stop.
        restartServiceDisposable = serviceBindingFactory.getMessengerObservable()
                .doOnComplete(() -> startTunnelService(context, tunnelConfig))
                .subscribe();
        stopTunnelService();
    }

    private void bindTunnelService(Context context, Intent intent) {
        serviceBindingFactory = new Rx2ServiceBindingFactory(context, intent);
        serviceBindingFactory.getMessengerObservable()
                .doOnComplete(() -> tunnelStateRelay.accept(TunnelState.stopped()))
                .doOnComplete(() -> dataStatsRelay.accept(Boolean.FALSE))
                .subscribe();
        sendServiceMessage(TunnelManager.ClientToServiceMessage.REGISTER.ordinal(), null);
        LocaleManager localeManager = LocaleManager.getInstance(context);
        Bundle data = new Bundle();
        data.putString(TunnelManager.EXTRA_LANGUAGE_CODE, localeManager.getLanguage());
        sendServiceMessage(TunnelManager.ClientToServiceMessage.SET_LANGUAGE.ordinal(), data);
    }

    private Intent getServiceIntent(Context context, TunnelManager.Config tunnelConfig) {
        Intent intent = getVpnServiceIntent(context);
        // Indicate that the user triggered this start request
        intent.putExtra(TunnelVpnService.USER_STARTED_INTENT_FLAG, true);
        intent.putExtra(TunnelManager.DATA_TUNNEL_CONFIG_EGRESS_REGION, tunnelConfig.egressRegion);
        intent.putExtra(TunnelManager.DATA_TUNNEL_CONFIG_DISABLE_TIMEOUTS, tunnelConfig.disableTimeouts);
        return intent;
    }

    private void sendServiceMessage(int what, Bundle data) {
        if (serviceBindingFactory == null) {
            return;
        }
        serviceBindingFactory.getMessengerObservable()
                .take(1)
                .doOnNext(messenger -> {
                    try {
                        Message msg = Message.obtain(null, what);
                        msg.replyTo = incomingMessenger;
                        if (data != null) {
                            msg.setData(data);
                        }
                        messenger.send(msg);
                    } catch (RemoteException e) {
                        Utils.MyLog.g(String.format("sendServiceMessage failed: %s", e.getMessage()));
                    }
                })
                .subscribe();
    }

    private static TunnelManager.State getTunnelStateFromBundle(Bundle data) {
        TunnelManager.State tunnelState = new TunnelManager.State();
        if (data == null) {
            return tunnelState;
        }
        tunnelState.isRunning = data.getBoolean(TunnelManager.DATA_TUNNEL_STATE_IS_RUNNING);
        tunnelState.isVPN = data.getBoolean(TunnelManager.DATA_TUNNEL_STATE_IS_VPN);
        tunnelState.isConnected = data.getBoolean(TunnelManager.DATA_TUNNEL_STATE_IS_CONNECTED);
        tunnelState.listeningLocalSocksProxyPort = data.getInt(TunnelManager.DATA_TUNNEL_STATE_LISTENING_LOCAL_SOCKS_PROXY_PORT);
        tunnelState.listeningLocalHttpProxyPort = data.getInt(TunnelManager.DATA_TUNNEL_STATE_LISTENING_LOCAL_HTTP_PROXY_PORT);
        tunnelState.clientRegion = data.getString(TunnelManager.DATA_TUNNEL_STATE_CLIENT_REGION);
        tunnelState.sponsorId = data.getString(TunnelManager.DATA_TUNNEL_STATE_SPONSOR_ID);
        ArrayList<String> homePages = data.getStringArrayList(TunnelManager.DATA_TUNNEL_STATE_HOME_PAGES);
        if (homePages != null && tunnelState.isConnected) {
            tunnelState.homePages = homePages;
        }
        return tunnelState;
    }

    private static void getDataTransferStatsFromBundle(Bundle data) {
        if (data == null) {
            return;
        }
        data.setClassLoader(DataTransferStats.DataTransferStatsBase.Bucket.class.getClassLoader());
        DataTransferStats.getDataTransferStatsForUI().m_connectedTime = data.getLong(TunnelManager.DATA_TRANSFER_STATS_CONNECTED_TIME);
        DataTransferStats.getDataTransferStatsForUI().m_totalBytesSent = data.getLong(TunnelManager.DATA_TRANSFER_STATS_TOTAL_BYTES_SENT);
        DataTransferStats.getDataTransferStatsForUI().m_totalBytesReceived = data.getLong(TunnelManager.DATA_TRANSFER_STATS_TOTAL_BYTES_RECEIVED);
        DataTransferStats.getDataTransferStatsForUI().m_slowBuckets = data.getParcelableArrayList(TunnelManager.DATA_TRANSFER_STATS_SLOW_BUCKETS);
        DataTransferStats.getDataTransferStatsForUI().m_slowBucketsLastStartTime = data.getLong(TunnelManager.DATA_TRANSFER_STATS_SLOW_BUCKETS_LAST_START_TIME);
        DataTransferStats.getDataTransferStatsForUI().m_fastBuckets = data.getParcelableArrayList(TunnelManager.DATA_TRANSFER_STATS_FAST_BUCKETS);
        DataTransferStats.getDataTransferStatsForUI().m_fastBucketsLastStartTime = data.getLong(TunnelManager.DATA_TRANSFER_STATS_FAST_BUCKETS_LAST_START_TIME);
    }

    public void onSubscriptionState(SubscriptionState subscriptionState) {
        subscriptionStatusPublishRelay.accept(subscriptionState);
    }

    private static class IncomingMessageHandler extends Handler {
        private final WeakReference<TunnelServiceInteractor> weakServiceInteractor;
        private final TunnelManager.ServiceToClientMessage[] scm = TunnelManager.ServiceToClientMessage.values();
        private TunnelManager.State state;


        IncomingMessageHandler(TunnelServiceInteractor serviceInteractor) {
            this.weakServiceInteractor = new WeakReference<>(serviceInteractor);
        }

        @Override
        public void handleMessage(Message msg) {
            TunnelServiceInteractor tunnelServiceInteractor = weakServiceInteractor.get();
            if (tunnelServiceInteractor == null) {
                return;
            }
            if (msg.what > scm.length) {
                super.handleMessage(msg);
                return;
            }
            Bundle data = msg.getData();
            switch (scm[msg.what]) {
                case KNOWN_SERVER_REGIONS:
                    tunnelServiceInteractor.knownRegionsRelay.accept(Boolean.TRUE);
                    break;
                case TUNNEL_CONNECTION_STATE:
                    state = getTunnelStateFromBundle(data);
                    TunnelState tunnelState;
                    if (state.isRunning) {
                        TunnelState.ConnectionData connectionData = TunnelState.ConnectionData.builder()
                                .setIsConnected(state.isConnected)
                                .setClientRegion(state.clientRegion)
                                .setClientVersion(EmbeddedValues.CLIENT_VERSION)
                                .setPropagationChannelId(EmbeddedValues.PROPAGATION_CHANNEL_ID)
                                .setSponsorId(state.sponsorId)
                                .setHttpPort(state.listeningLocalHttpProxyPort)
                                .setVpnMode(state.isVPN)
                                .setHomePages(state.homePages)
                                .build();
                        tunnelState = TunnelState.running(connectionData);
                    } else {
                        tunnelState = TunnelState.stopped();
                    }
                    tunnelServiceInteractor.tunnelStateRelay.accept(tunnelState);
                    break;
                case DATA_TRANSFER_STATS:
                    getDataTransferStatsFromBundle(data);
                    tunnelServiceInteractor.dataStatsRelay.accept(state.isConnected);
                    break;
                case AUTHORIZATIONS_REMOVED:
                    tunnelServiceInteractor.authorizationsRemovedRelay.accept(Boolean.TRUE);
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }

    private static class Rx2ServiceBindingFactory {
        private final Observable<Messenger> messengerObservable;
        private ServiceConnection serviceConnection;

        Rx2ServiceBindingFactory(Context context, Intent intent) {
            this.messengerObservable = Observable.using(Connection::new,
                    (final Connection<Messenger> con) -> {
                        serviceConnection = con;
                        context.bindService(intent, con, 0);
                        return Observable.create(con);
                    },
                    __ -> unbind(context))
                    .replay(1)
                    .refCount();
        }

        Observable<Messenger> getMessengerObservable() {
            return messengerObservable;
        }

        void unbind(Context context) {
            if (serviceConnection != null) {
                try {
                    context.unbindService(serviceConnection);
                    serviceConnection = null;
                } catch (IllegalArgumentException e) {
                    // Ignore
                    // "java.lang.IllegalArgumentException: Service not registered"
                }
            }
        }

        private static class Connection<B extends Messenger> implements ServiceConnection, ObservableOnSubscribe<B> {
            private ObservableEmitter<? super B> subscriber;

            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                if (subscriber != null && !subscriber.isDisposed() && service != null) {
                    //noinspection unchecked - we trust this one
                    subscriber.onNext((B) new Messenger(service));
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                if (subscriber != null && !subscriber.isDisposed()) {
                    subscriber.onComplete();
                }
            }

            @Override
            public void subscribe(ObservableEmitter<B> observableEmitter) throws Exception {
                this.subscriber = observableEmitter;
            }
        }
    }
}
