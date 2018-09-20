/*
 * Copyright 2018 Idealnaya rabota LLC
 * Licensed under Multy.io license.
 * See LICENSE for details
 */

package io.multy.viewmodels;

import android.arch.lifecycle.MutableLiveData;
import android.support.annotation.NonNull;

import com.google.gson.Gson;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URISyntaxException;

import io.multy.Multy;
import io.multy.R;
import io.multy.api.MultyApi;
import io.multy.api.socket.SocketManager;
import io.multy.model.entities.wallet.MultisigEvent;
import io.multy.model.entities.wallet.Owner;
import io.multy.model.entities.wallet.Wallet;
import io.multy.model.responses.WalletsResponse;
import io.multy.storage.RealmManager;
import io.realm.RealmList;
import io.socket.client.Ack;
import io.socket.emitter.Emitter;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import timber.log.Timber;

public class CreateMultisigViewModel extends BaseViewModel {

    private boolean isCreator = false;
    private long walletId;
    private SocketManager socketManager;
    private MutableLiveData<Wallet> multisigWallet = new MutableLiveData<>();
    private MutableLiveData<Wallet> linkedWallet = new MutableLiveData<>();
    private MutableLiveData<String> inviteCode = new MutableLiveData<>();

    public void connectSockets(Emitter.Listener args) {
        try {
            if (socketManager == null) {
                socketManager = new SocketManager();
            }
            final String eventReceive = SocketManager.getEventReceive(RealmManager.getSettingsDao().getUserId().getUserId());
            socketManager.listenEvent(eventReceive, args);
            socketManager.connect();
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
    }

    public void disconnectSockets() {
        if (socketManager != null && socketManager.isConnected()) {
            socketManager.disconnect();
        }
    }

    public boolean isCreator() {
        return isCreator;
    }

    public long getWalletId() {
        return walletId;
    }

    public void setWalletId(long walletId) {
        this.walletId = walletId;
        getMultisigWallet();
        checkCreatorStatus();
    }

    public MutableLiveData<String> getInviteCode() {
        return inviteCode;
    }

    public void setInviteCode(String inviteCode) {
        this.inviteCode.setValue(inviteCode);
    }

    public MutableLiveData<Wallet> getMultisigWallet() {
        if (multisigWallet.getValue() == null || !multisigWallet.getValue().isValid()) {
            if (walletId == 0 && inviteCode.getValue() != null) {
                Wallet wallet = RealmManager.getAssetsDao().getMultisigWallet(inviteCode.getValue());
                if (wallet != null) {
                    walletId = wallet.getId();
                    multisigWallet.setValue(wallet);
                }
            } else if (walletId != 0) {
                multisigWallet.setValue(RealmManager.getAssetsDao().getWalletById(walletId));
            }
        }
        return multisigWallet;
    }

    public MutableLiveData<Wallet> getLinkedWallet() {
        if (linkedWallet.getValue() == null || !linkedWallet.getValue().isValid()) {
            updateLinkedWallet();
        }
        return linkedWallet;
    }

    public void setLinkedWallet(Wallet wallet) {
        linkedWallet.setValue(wallet);
//        updateMultisigWallet();
    }

    public void updateMultisigWallet() {
        MultyApi.INSTANCE.getWalletsVerbose().enqueue(new Callback<WalletsResponse>() {
            @Override
            public void onResponse(@NonNull Call<WalletsResponse> call, @NonNull Response<WalletsResponse> response) {
                WalletsResponse body = response.body();
                if (response.isSuccessful() && body != null) {
                    RealmManager.getAssetsDao().saveWallets(body.getWallets());
                    getMultisigWallet();
                }
            }

            @Override
            public void onFailure(@NonNull Call<WalletsResponse> call, @NonNull Throwable t) {
                errorMessage.postValue(t.getLocalizedMessage());
            }
        });
    }

    public void updateLinkedWallet() {
        if (getMultisigWallet().getValue() != null) {
            RealmList<Owner> owners = getMultisigWallet().getValue().getMultisigWallet().getOwners();
            linkedWallet.setValue(RealmManager.getAssetsDao().getMultisigLinkedWallet(owners));
        }
    }

    private void checkCreatorStatus() {
        if (multisigWallet.getValue() != null && multisigWallet.getValue().isMultisig()) {
            final String userId = RealmManager.getSettingsDao().getUserId().getUserId();
            for (Owner owner : multisigWallet.getValue().getMultisigWallet().getOwners()) {
                if (owner.isCreator() && owner.getUserId().equals(userId)) {
                    isCreator = true;
                    break;
                }
            }
        }
    }

    public void validate(String inviteCode, Ack ack) {
        if (socketManager != null) {
            final String userId = RealmManager.getSettingsDao().getUserId().getUserId();
            MultisigEvent.Payload payload = new MultisigEvent.Payload();
            payload.inviteCode = inviteCode;
            payload.userId = userId;
            MultisigEvent event = new MultisigEvent(SocketManager.SOCKET_VALIDATE, System.currentTimeMillis(), payload);
            try {
                Timber.i("sending socket " + new JSONObject(new Gson().toJson(event)).toString());
                final String eventJson = new Gson().toJson(event);
                socketManager.sendEvent(SocketManager.EVENT_MESSAGE_SEND, new JSONObject(eventJson), ack);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }

    public void join() {
        if (socketManager != null) {
            Wallet linkedWallet = getLinkedWallet().getValue();
            final String userId = RealmManager.getSettingsDao().getUserId().getUserId();
            MultisigEvent.Payload payload = new MultisigEvent.Payload();
            payload.address = linkedWallet.getActiveAddress().getAddress();
            payload.inviteCode = inviteCode.getValue();
            payload.walletIndex = linkedWallet.getIndex();
            payload.currencyId = linkedWallet.getCurrencyId();
            payload.networkId = linkedWallet.getNetworkId();
            payload.userId = userId;
            final MultisigEvent event = new MultisigEvent(SocketManager.SOCKET_JOIN, System.currentTimeMillis(), payload);
            try {
                final String eventJson = new Gson().toJson(event);
                socketManager.sendEvent(SocketManager.EVENT_MESSAGE_SEND, new JSONObject(eventJson), ack -> {
                    Timber.i("JOIN ack");
                    Timber.v("JOIN: " + ack[0].toString());
                    updateMultisigWallet();
                });
            } catch (JSONException e) {
                e.printStackTrace();
                errorMessage.setValue(Multy.getContext().getString(R.string.something_went_wrong));
            }
        }
    }

    public void kickUser(String addressToKick) {
        if (socketManager != null) {
            Wallet linkedWallet = getLinkedWallet().getValue();
            final String userId = RealmManager.getSettingsDao().getUserId().getUserId();
            MultisigEvent.Payload payload = new MultisigEvent.Payload();
            payload.address = linkedWallet.getActiveAddress().getAddress();
            payload.inviteCode = inviteCode.getValue();
            payload.walletIndex = linkedWallet.getIndex();
            payload.currencyId = linkedWallet.getCurrencyId();
            payload.networkId = linkedWallet.getNetworkId();
            payload.userId = userId;
            payload.addressToKick = addressToKick;
            final MultisigEvent event = new MultisigEvent(SocketManager.SOCKET_KICK, System.currentTimeMillis(), payload);
            final String eventJson = new Gson().toJson(event);
            try {
                socketManager.sendEvent(SocketManager.EVENT_MESSAGE_SEND, new JSONObject(eventJson), args -> {
                  updateMultisigWallet();
                  Timber.v("KICK: " + args[0].toString());
                });
            } catch (JSONException e) {
                e.printStackTrace();
                errorMessage.setValue(Multy.getContext().getString(R.string.something_went_wrong));
            }
        }
    }

    public void leaveWallet(Ack ack) {
        if (socketManager != null) {
            Wallet linkedWallet = getLinkedWallet().getValue();
            final String userId = RealmManager.getSettingsDao().getUserId().getUserId();
            MultisigEvent.Payload payload = new MultisigEvent.Payload();
            payload.address = linkedWallet.getActiveAddress().getAddress();
            payload.inviteCode = inviteCode.getValue();
            payload.walletIndex = linkedWallet.getIndex();
            payload.currencyId = linkedWallet.getCurrencyId();
            payload.networkId = linkedWallet.getNetworkId();
            payload.userId = userId;
            final MultisigEvent event = new MultisigEvent(SocketManager.SOCKET_LEAVE, System.currentTimeMillis(), payload);
            try {
                final String eventJson = new Gson().toJson(event);
                socketManager.sendEvent(SocketManager.EVENT_MESSAGE_SEND, new JSONObject(eventJson), ack);
            } catch (JSONException e) {
                e.printStackTrace();
                errorMessage.setValue(Multy.getContext().getString(R.string.something_went_wrong));
            }
        }
    }

    public void deleteWallet(Ack ack) {
        if (socketManager != null) {
            Wallet linkedWallet = getLinkedWallet().getValue();
            final String userId = RealmManager.getSettingsDao().getUserId().getUserId();
            MultisigEvent.Payload payload = new MultisigEvent.Payload();
            payload.address = linkedWallet.getActiveAddress().getAddress();
            payload.inviteCode = inviteCode.getValue();
            payload.walletIndex = linkedWallet.getIndex();
            payload.currencyId = linkedWallet.getCurrencyId();
            payload.networkId = linkedWallet.getNetworkId();
            payload.userId = userId;
            final MultisigEvent event = new MultisigEvent(SocketManager.SOCKET_DELETE, System.currentTimeMillis(), payload);
            try {
                final String eventJson = new Gson().toJson(event);
                socketManager.sendEvent(SocketManager.EVENT_MESSAGE_SEND, new JSONObject(eventJson), ack);
            } catch (JSONException e) {
                e.printStackTrace();
                errorMessage.setValue(Multy.getContext().getString(R.string.something_went_wrong));
            }
        }
    }
}
