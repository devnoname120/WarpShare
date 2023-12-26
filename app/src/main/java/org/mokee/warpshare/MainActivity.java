/*
 * Copyright (C) 2019 The MoKee Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mokee.warpshare;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.Formatter;
import android.util.ArrayMap;
import android.util.Log;
import android.view.*;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.RecyclerView;
import org.mokee.warpshare.airdrop.AirDropManager;
import org.mokee.warpshare.airdrop.AirDropPeer;
import org.mokee.warpshare.base.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static org.mokee.warpshare.airdrop.AirDropManager.STATUS_OK;

public class MainActivity extends AppCompatActivity implements DiscoverListener {

    private static final String TAG = "MainActivity";

    private static final int REQUEST_PICK = 1;
    private static final int REQUEST_SETUP = 2;

    private final ArrayMap<String, Peer> mPeers = new ArrayMap<>();

    private final Map<String, PeerState> mPeerStates = new HashMap<>();

    private PeersAdapter mAdapter;

    private String mPeerPicked = null;

    private PartialWakeLock mWakeLock;

    private AirDropManager mAirDropManager;

    private boolean mIsInSetup = false;
    ActivityResultLauncher<Intent> setupActivityResultLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        mIsInSetup = false;
                        if (result.getResultCode() != Activity.RESULT_OK) {
                            finish();
                        } else {
                            mAirDropManager.registerTrigger(TriggerReceiver.getTriggerIntent(this), this);
                        }
                    });
    private final WifiStateMonitor mWifiStateMonitor = new WifiStateMonitor() {
        @Override
        public void onReceive(Context context, Intent intent) {
            setupIfNeeded();
        }
    };
    private final BluetoothStateMonitor mBluetoothStateMonitor = new BluetoothStateMonitor() {
        @Override
        public void onReceive(Context context, Intent intent) {
            setupIfNeeded();
        }
    };
    private boolean mIsDiscovering = false;
    private boolean mShouldKeepDiscovering = false;
    // TODO: Combining two launchers into one
    ActivityResultLauncher<Intent> chooseFileActivityResultLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == Activity.RESULT_OK && mPeerPicked != null && result.getData() != null) {
                            final Peer peer = mPeers.get(mPeerPicked);
                            if (peer != null) {
                                if (result.getData().getClipData() == null) {
                                    sendFile(peer, result.getData().getData(), result.getData().getType());
                                } else {
                                    sendFile(peer, result.getData().getClipData(), result.getData().getType());
                                }
                            }
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mWakeLock = new PartialWakeLock(this, TAG);

        mAirDropManager = new AirDropManager(this,
                WarpShareApplication.from(this).getCertificateManager());
        mAirDropManager.registerTrigger(TriggerReceiver.getTriggerIntent(this), this);

        mAdapter = new PeersAdapter(this);

        final RecyclerView peersView = findViewById(R.id.peers);
        peersView.setAdapter(mAdapter);

        final Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mAirDropManager.destroy();
    }

    @Override
    protected void onResume() {
        super.onResume();

        mWifiStateMonitor.register(this);
        mBluetoothStateMonitor.register(this);

        if (setupIfNeeded()) {
            return;
        }

        if (!mIsDiscovering) {
            mAirDropManager.startDiscover(this);
            mIsDiscovering = true;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (mIsDiscovering && !mShouldKeepDiscovering) {
            mAirDropManager.stopDiscover();
            mIsDiscovering = false;
        }

        mWifiStateMonitor.unregister(this);
        mBluetoothStateMonitor.unregister(this);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        switch (requestCode) {
            case REQUEST_PICK:
                mShouldKeepDiscovering = false;
                if (resultCode == RESULT_OK && mPeerPicked != null && data != null) {
                    final Peer peer = mPeers.get(mPeerPicked);
                    if (peer != null) {
                        if (data.getClipData() == null) {
                            sendFile(peer, data.getData(), data.getType());
                        } else {
                            sendFile(peer, data.getClipData(), data.getType());
                        }
                    }
                }
                break;
            case REQUEST_SETUP:
                mIsInSetup = false;
                if (resultCode != RESULT_OK) {
                    finish();
                } else {
                    mAirDropManager.registerTrigger(TriggerReceiver.getTriggerIntent(this), this);
                }
                break;
            default:
                super.onActivityResult(requestCode, resultCode, data);
                break;
        }
    }

    @Override
    public void onPeerFound(Peer peer) {
        Log.d(TAG, "Found: " + peer.id + " (" + peer.name + ")");
        if (mPeers.containsKey(peer.id)) {
            Log.d(TAG, "Already found: " + peer.id + " (" + peer.name + ")");
            return;
        }
        mPeers.put(peer.id, peer);
        mPeerStates.put(peer.id, new PeerState());
        mAdapter.notifyDataSetChanged();
    }

    @Override
    public void onPeerDisappeared(Peer peer) {
        Log.d(TAG, "Disappeared: " + peer.id + " (" + peer.name + ")");
        mPeers.remove(peer.id);
        mPeerStates.remove(peer.id);
        mAdapter.notifyDataSetChanged();
    }

    private boolean setupIfNeeded() {
        if (mIsInSetup) {
            return true;
        }

        final boolean granted;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            granted = (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PERMISSION_GRANTED) && (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PERMISSION_GRANTED);
        } else {
            granted = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PERMISSION_GRANTED;
        }

        final boolean ready = mAirDropManager.ready() == STATUS_OK;
        if (!granted || !ready) {
            mIsInSetup = true;
            setupActivityResultLauncher.launch(new Intent(this, SetupActivity.class));
            return true;
        } else {
            return false;
        }
    }

    private void handleItemClick(Peer peer) {
        mPeerPicked = peer.id;
        mShouldKeepDiscovering = true;
        Intent requestIntent = new Intent(Intent.ACTION_GET_CONTENT);
        requestIntent.addCategory(Intent.CATEGORY_OPENABLE);
        requestIntent.setType("*/*");
        requestIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        chooseFileActivityResultLauncher.launch(Intent.createChooser(requestIntent, "File"));
    }

    private void handleItemCancelClick(Peer peer, PeerState state) {
        if (state.sending != null) {
            state.sending.cancel();
        }
        handleSendFailed(peer);
    }

    private void handleSendConfirming(PeerState state) {
        state.status = R.string.status_waiting_for_confirm;
        state.bytesTotal = -1;
        state.bytesSent = 0;
        mAdapter.notifyDataSetChanged();
        mShouldKeepDiscovering = true;
        mWakeLock.acquire();
    }

    private void handleSendRejected(PeerState state) {
        state.status = R.string.status_rejected;
        mAdapter.notifyDataSetChanged();
        mShouldKeepDiscovering = false;
        mWakeLock.release();
    }

    private void handleSending(PeerState state) {
        state.status = R.string.status_sending;
        mAdapter.notifyDataSetChanged();
    }

    private void handleSendSucceed(PeerState state) {
        state.status = 0;
        mAdapter.notifyDataSetChanged();
        mShouldKeepDiscovering = false;
        mWakeLock.release();
    }

    private void handleSendFailed(Peer peer) {
        final PeerState state = mPeerStates.get(peer.id);
        if (state != null) {
            state.status = 0;
        }
        mAdapter.notifyDataSetChanged();
        mShouldKeepDiscovering = false;
        mWakeLock.release();
    }

    private void sendFile(Peer peer, Uri uri, String type) {
        final Entity entity = new Entity(this, uri, type);
        if (!entity.ok()) {
            Log.w(TAG, "No file was selected");
            handleSendFailed(peer);
            return;
        }

        final List<Entity> entities = new ArrayList<>();
        entities.add(entity);

        sendFile(peer, entities);
    }

    private void sendFile(Peer peer, ClipData clipData, String type) {
        if (clipData == null) {
            Log.w(TAG, "ClipData should not be null");
            handleSendFailed(peer);
            return;
        }

        final List<Entity> entities = new ArrayList<>();
        for (int i = 0; i < clipData.getItemCount(); i++) {
            final Entity entity = new Entity(this, clipData.getItemAt(i).getUri(), type);
            if (entity.ok()) {
                entities.add(entity);
            }
        }

        if (entities.isEmpty()) {
            Log.w(TAG, "No file was selected");
            handleSendFailed(peer);
            return;
        }

        sendFile(peer, entities);
    }

    private void sendFile(final Peer peer, final List<Entity> entities) {
        final PeerState state = mPeerStates.get(peer.id);
        if (state == null) {
            Log.w(TAG, "state should not be null");
            handleSendFailed(peer);
            return;
        }

        handleSendConfirming(state);

        final SendListener listener = new SendListener() {
            @Override
            public void onAccepted() {
                handleSending(state);
            }

            @Override
            public void onRejected() {
                handleSendRejected(state);
            }

            @Override
            public void onProgress(long bytesSent, long bytesTotal) {
                state.bytesSent = bytesSent;
                state.bytesTotal = bytesTotal;
                mAdapter.notifyDataSetChanged();
            }

            @Override
            public void onSent() {
                handleSendSucceed(state);
            }

            @Override
            public void onSendFailed() {
                handleSendFailed(peer);
            }
        };


        if (peer instanceof AirDropPeer) {
            state.sending = mAirDropManager.send((AirDropPeer) peer, entities, listener);
        } else {
            Log.d(TAG, "Unknown peer type: " + peer.getClass().getName());
        }
    }

    private static class PeerState {

        @StringRes
        int status = 0;

        long bytesTotal = -1;
        long bytesSent = 0;

        SendingSession sending = null;

    }

    private class PeersAdapter extends RecyclerView.Adapter<PeersAdapter.ViewHolder> {

        private final LayoutInflater mInflater;

        PeersAdapter(Context context) {
            mInflater = LayoutInflater.from(context);
            setHasStableIds(true);
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(mInflater.inflate(R.layout.item_peer_main, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            final String id = mPeers.keyAt(position);
            final Peer peer = mPeers.valueAt(position);
            final PeerState state = mPeerStates.get(id);

            assert state != null;

            holder.nameView.setText(peer.name);
            if (state.status != 0) {
                holder.itemView.setSelected(true);
                holder.statusView.setVisibility(View.VISIBLE);
                if (state.status == R.string.status_sending && state.bytesTotal != -1) {
                    holder.statusView.setText(getString(R.string.status_sending_progress,
                            Formatter.formatFileSize(MainActivity.this, state.bytesSent),
                            Formatter.formatFileSize(MainActivity.this, state.bytesTotal)));
                } else {
                    holder.statusView.setText(state.status);
                }
            } else {
                holder.itemView.setSelected(false);
                holder.statusView.setVisibility(View.GONE);
            }
            if (state.status != 0 && state.status != R.string.status_rejected) {
                holder.itemView.setEnabled(false);
                holder.progressBar.setVisibility(View.VISIBLE);
                holder.cancelButton.setVisibility(View.VISIBLE);
                if (state.bytesTotal == -1 || state.status != R.string.status_sending) {
                    holder.progressBar.setIndeterminate(true);
                } else {
                    holder.progressBar.setIndeterminate(false);
                    holder.progressBar.setMax((int) state.bytesTotal);
                    holder.progressBar.setProgress((int) state.bytesSent, true);
                }
            } else {
                holder.itemView.setEnabled(true);
                holder.progressBar.setVisibility(View.GONE);
                holder.cancelButton.setVisibility(View.GONE);
            }
            if (peer instanceof AirDropPeer) {
                holder.iconView.setImageResource(R.drawable.ic_apple_24dp);
            } else {
                holder.iconView.setImageDrawable(null);
            }
            holder.itemView.setOnClickListener(v -> handleItemClick(peer));
            holder.cancelButton.setOnClickListener(v -> handleItemCancelClick(peer, state));
        }

        @Override
        public long getItemId(int position) {
            return mPeers.keyAt(position).hashCode();
        }

        @Override
        public int getItemCount() {
            return mPeers.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {

            ImageView iconView;
            TextView nameView;
            TextView statusView;
            ProgressBar progressBar;
            View cancelButton;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                iconView = itemView.findViewById(R.id.icon);
                nameView = itemView.findViewById(R.id.name);
                statusView = itemView.findViewById(R.id.status);
                progressBar = itemView.findViewById(R.id.progress);
                cancelButton = itemView.findViewById(R.id.cancel);
            }
        }
    }
}
