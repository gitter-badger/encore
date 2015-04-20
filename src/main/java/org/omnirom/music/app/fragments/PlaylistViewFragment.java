/*
 * Copyright (C) 2014 Fastboot Mobile, LLC.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation; either version 3 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See
 * the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program;
 * if not, see <http://www.gnu.org/licenses>.
 */

package org.omnirom.music.app.fragments;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.LayoutAnimationController;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.dd.CircularProgressButton;
import com.getbase.floatingactionbutton.FloatingActionButton;

import org.omnirom.music.app.AlbumActivity;
import org.omnirom.music.app.PlaylistActivity;
import org.omnirom.music.app.R;
import org.omnirom.music.app.adapters.PlaylistAdapter;
import org.omnirom.music.app.ui.PlayPauseDrawable;
import org.omnirom.music.app.ui.PlaylistListView;
import org.omnirom.music.art.RecyclingBitmapDrawable;
import org.omnirom.music.framework.AutoPlaylistHelper;
import org.omnirom.music.framework.PlaybackProxy;
import org.omnirom.music.framework.PluginsLookup;
import org.omnirom.music.model.Album;
import org.omnirom.music.model.Artist;
import org.omnirom.music.model.BoundEntity;
import org.omnirom.music.model.Playlist;
import org.omnirom.music.model.SearchResult;
import org.omnirom.music.model.Song;
import org.omnirom.music.providers.ILocalCallback;
import org.omnirom.music.providers.IMusicProvider;
import org.omnirom.music.providers.ProviderAggregator;
import org.omnirom.music.providers.ProviderConnection;
import org.omnirom.music.providers.ProviderIdentifier;
import org.omnirom.music.service.BasePlaybackCallback;
import org.omnirom.music.service.PlaybackService;
import org.omnirom.music.utils.Utils;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;


/**
 * A simple {@link android.support.v4.app.Fragment} subclass.
 * Use the {@link org.omnirom.music.app.fragments.PlaylistViewFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class PlaylistViewFragment extends MaterialReelBaseFragment implements ILocalCallback {
    private static final String TAG = "PlaylistViewFragment";

    public static final String KEY_PLAYLIST = "playlist";

    private static final int UPDATE_OFFLINE_STATUS = 1;
    private static final int UPDATE_DATA_SET = 2;

    private PlaylistAdapter mAdapter;
    private Playlist mPlaylist;
    private PlaylistListView mListViewContents;
    private FloatingActionButton mPlayFab;
    private PlayPauseDrawable mFabDrawable;
    private boolean mFabShouldResume;
    private Handler mHandler;
    private CircularProgressButton mOfflineBtn;
    private RecyclingBitmapDrawable mLogoBitmap;
    private ImageView mIvHero;
    private ImageView mIvSource;
    private TextView mTvPlaylistName;
    private boolean mIsSpecialPlaylist;

    private static class PlaylistViewHandler extends Handler {
        private WeakReference<PlaylistViewFragment> mParent;

        public PlaylistViewHandler(PlaylistViewFragment parent) {
            mParent = new WeakReference<>(parent);
        }

        @Override
        public void handleMessage(Message msg) {
            PlaylistViewFragment parent = mParent.get();

            if (parent == null) {
                return;
            }

            if (msg.what == UPDATE_OFFLINE_STATUS) {
                parent.updateOfflineStatus();
            } else if (msg.what == UPDATE_DATA_SET) {
                parent.mAdapter.notifyDataSetChanged();
            }
        }
    }

    private BasePlaybackCallback mPlaybackCallback = new BasePlaybackCallback() {
        @Override
        public void onSongStarted(final boolean buffering, final Song s) throws RemoteException {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mAdapter.notifyDataSetChanged();
                    mFabDrawable.setShape(PlayPauseDrawable.SHAPE_PAUSE);
                    mFabDrawable.setBuffering(buffering);

                    setReelBarTitle(s.getTitle());
                    mBarDrawable.setBuffering(buffering);
                    mBarDrawable.setShape(mFabDrawable.getRequestedShape());
                }
            });
        }

        @Override
        public void onPlaybackResume() throws RemoteException {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mFabDrawable.setShape(PlayPauseDrawable.SHAPE_PAUSE);
                    mFabDrawable.setBuffering(false);
                    mBarDrawable.setBuffering(false);
                    mBarDrawable.setShape(mFabDrawable.getRequestedShape());
                }
            });
        }

        @Override
        public void onPlaybackPause() throws RemoteException {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mFabDrawable.setShape(PlayPauseDrawable.SHAPE_PLAY);
                    mFabDrawable.setBuffering(false);
                    mBarDrawable.setBuffering(false);
                    mBarDrawable.setShape(mFabDrawable.getRequestedShape());
                }
            });
        }
    };

    private View.OnClickListener mReelFabClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            if (mBarDrawable.getCurrentShape() == PlayPauseDrawable.SHAPE_PLAY) {
                if (mFabShouldResume) {
                    PlaybackProxy.play();
                    mBarDrawable.setShape(PlayPauseDrawable.SHAPE_PAUSE);
                    mFabDrawable.setShape(PlayPauseDrawable.SHAPE_PAUSE);
                } else {
                    PlaybackProxy.playPlaylist(mPlaylist);
                }
            } else {
                mFabShouldResume = true;
                PlaybackProxy.pause();
                mBarDrawable.setShape(PlayPauseDrawable.SHAPE_PLAY);
                mFabDrawable.setShape(PlayPauseDrawable.SHAPE_PLAY);
                hideMaterialReelBar(mPlayFab);
            }
        }
    };

    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     *
     * @return A new instance of fragment PlaylistViewFragment.
     */
    public static PlaylistViewFragment newInstance(Playlist p) {
        PlaylistViewFragment fragment = new PlaylistViewFragment();
        Bundle bundle = new Bundle();
        bundle.putParcelable(KEY_PLAYLIST, p);
        fragment.setArguments(bundle);
        return fragment;
    }

    public PlaylistViewFragment() {
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mHandler = new PlaylistViewHandler(this);

        Bundle args = getArguments();
        if (args == null) {
            throw new IllegalArgumentException("This fragment must have a valid playlist");
        }

        // Get the playlist from the arguments, from the instantiation, and from the cache
        final String playlistRef = args.getString(KEY_PLAYLIST);

        if (AutoPlaylistHelper.REF_SPECIAL_FAVORITES.equals(playlistRef)) {
            mPlaylist = AutoPlaylistHelper.getFavoritesPlaylist(getActivity());
            mIsSpecialPlaylist = true;
        } else if (AutoPlaylistHelper.REF_SPECIAL_MOST_PLAYED.equals(playlistRef)) {
            mPlaylist = AutoPlaylistHelper.getMostPlayedPlaylist(getActivity());
            mIsSpecialPlaylist = true;
        } else {
            final ProviderAggregator aggregator = ProviderAggregator.getDefault();
            mPlaylist = aggregator.retrievePlaylist(playlistRef, null);
            mIsSpecialPlaylist = false;
        }

        if (mPlaylist == null) {
            Log.e(TAG, "Playlist is null (not in cache, aborting)");
            // TODO: Wait for playlist to be loaded, eventually
            Activity act = getActivity();
            if (act != null) {
                act.finish();
            }
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View root = inflater.inflate(R.layout.fragment_playlist_view, container, false);
        assert root != null;

        if (mPlaylist == null) {
            // If playlist couldn't load, abort early
            return root;
        }

        mListViewContents = (PlaylistListView) root.findViewById(R.id.lvPlaylistContents);

        // Setup the parallaxed header
        View headerView = inflater.inflate(R.layout.header_listview_songs, mListViewContents, false);
        mListViewContents.addParallaxedHeaderView(headerView);

        mAdapter = new PlaylistAdapter();
        mListViewContents.setAdapter(mAdapter);

        headerView.findViewById(R.id.pbAlbumLoading).setVisibility(View.GONE);

        mIvHero = (ImageView) headerView.findViewById(R.id.ivHero);

        mTvPlaylistName = (TextView) headerView.findViewById(R.id.tvAlbumName);

        // Download button
        mOfflineBtn = (CircularProgressButton) headerView.findViewById(R.id.cpbOffline);
        mOfflineBtn.setAlpha(0.0f);
        mOfflineBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ProviderIdentifier pi = mPlaylist.getProvider();
                IMusicProvider provider = PluginsLookup.getDefault().getProvider(pi).getBinder();
                try {
                    if (mPlaylist.getOfflineStatus() == BoundEntity.OFFLINE_STATUS_NO) {
                        provider.setPlaylistOfflineMode(mPlaylist.getRef(), true);
                        mOfflineBtn.setIndeterminateProgressMode(true);
                        mOfflineBtn.setProgress(1);

                        if (ProviderAggregator.getDefault().isOfflineMode()) {
                            Toast.makeText(getActivity(), R.string.toast_offline_playlist_sync,
                                    Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        provider.setPlaylistOfflineMode(mPlaylist.getRef(), false);
                        mOfflineBtn.setProgress(0);
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "Cannot set this playlist to offline mode", e);
                    mOfflineBtn.setProgress(-1);
                }
            }
        });

        mHandler.sendEmptyMessageDelayed(UPDATE_OFFLINE_STATUS, 300);
        mTvPlaylistName.setText(mPlaylist.getName());

        Bitmap hero = Utils.dequeueBitmap(PlaylistActivity.BITMAP_PLAYLIST_HERO);
        if (hero == null) {
            mIvHero.setImageResource(R.drawable.album_placeholder);
        } else {
            mIvHero.setImageBitmap(hero);
        }

        mPlayFab = (FloatingActionButton) headerView.findViewById(R.id.fabPlay);

        // Set source logo
        mIvSource = (ImageView) headerView.findViewById(R.id.ivSourceLogo);
        mLogoBitmap = PluginsLookup.getDefault().getCachedLogo(getResources(), mPlaylist);
        mIvSource.setImageDrawable(mLogoBitmap);

        // Set the FAB animated drawable
        mFabDrawable = new PlayPauseDrawable(getResources(), 1);
        mFabDrawable.setShape(PlayPauseDrawable.SHAPE_PLAY);
        mFabDrawable.setYOffset(6);

        mPlayFab.setImageDrawable(mFabDrawable);
        mPlayFab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (mFabDrawable.getCurrentShape() == PlayPauseDrawable.SHAPE_PLAY) {
                    if (mFabShouldResume) {
                        PlaybackProxy.play();
                        mFabDrawable.setShape(PlayPauseDrawable.SHAPE_PAUSE);
                        mFabDrawable.setBuffering(true);
                    } else {
                        playNow();
                    }
                    showMaterialReelBar(mPlayFab);
                } else {
                    mFabShouldResume = true;
                    PlaybackProxy.pause();
                    mFabDrawable.setBuffering(true);
                }
            }
        });

        // Fill the playlist
        mAdapter.setPlaylist(mPlaylist);

        // Set the list listener
        mListViewContents.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                Song song = mAdapter.getItem(i - 1);
                if (Utils.canPlaySong(song)) {
                    PlaybackProxy.clearQueue();
                    PlaybackProxy.queuePlaylist(mPlaylist, false);
                    PlaybackProxy.playAtIndex(i - 1);

                    // Update FAB
                    mFabShouldResume = true;
                    mFabDrawable.setShape(PlayPauseDrawable.SHAPE_PAUSE);
                    mFabDrawable.setBuffering(true);

                    showMaterialReelBar(mPlayFab);
                }
            }
        });

        // Set the display animation
        AlphaAnimation anim = new AlphaAnimation(0.f, 1.f);
        anim.setDuration(200);
        mListViewContents.setLayoutAnimation(new LayoutAnimationController(anim));

        setupMaterialReelBar(root, mReelFabClickListener);

        // Setup the opening animations
        if (Utils.hasLollipop()) {
            mTvPlaylistName.setVisibility(View.INVISIBLE);
            mHandler.postDelayed(new Runnable() {
                @Override
                @TargetApi(Build.VERSION_CODES.LOLLIPOP)
                public void run() {
                    if (mTvPlaylistName.isAttachedToWindow()) {
                        Utils.animateHeadingReveal(mTvPlaylistName);
                    }
                }
            }, 500);
        } else {
            mTvPlaylistName.setVisibility(View.VISIBLE);
            mTvPlaylistName.setAlpha(0.0f);
            mTvPlaylistName.animate().alpha(1.0f).setDuration(AlbumActivity.BACK_DELAY).start();
        }

        mIvSource.setAlpha(0.0f);
        mIvSource.animate().alpha(1).setDuration(200).start();

        return root;
    }

    @Override
    public void onResume() {
        super.onResume();

        ProviderAggregator.getDefault().addUpdateCallback(PlaylistViewFragment.this);
        PlaybackProxy.addCallback(mPlaybackCallback);
        updateFabStatus();
    }

    @Override
    public void onPause() {
        super.onPause();
        ProviderAggregator.getDefault().removeUpdateCallback(this);
        PlaybackProxy.removeCallback(mPlaybackCallback);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.playlist, menu);

        if (mIsSpecialPlaylist) {
            // Remove some options not applicable to the special playlist mode
            menu.removeItem(R.id.menu_remove_duplicates);
            menu.removeItem(R.id.menu_remove_playlist);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_play_now) {
            playNow();
            return true;
        } else if (item.getItemId() == R.id.menu_play_next) {
            playNext();
            return true;
        } else if (item.getItemId() == R.id.menu_add_to_queue) {
            PlaybackProxy.queuePlaylist(mPlaylist, false);
            return true;
        } else if (item.getItemId() == R.id.menu_remove_duplicates) {
            try {
                removeDuplicates();
            } catch (RemoteException e) {
                Log.e(TAG, "Cannot remove duplicates", e);
            }
            return true;
        } else if (item.getItemId() == R.id.menu_remove_playlist) {
            removePlaylistDialog();
            return true;
        }

        return false;
    }

    private void removePlaylistDialog() {
        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.remove_playlist_dialog_title)
                .setMessage(getString(R.string.remove_playlist_dialog_msg, mPlaylist.getName()))
                .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ProviderConnection conn = PluginsLookup.getDefault().getProvider(mPlaylist.getProvider());
                        if (conn != null) {
                            IMusicProvider provider = conn.getBinder();
                            if (provider != null) {
                                try {
                                    provider.deletePlaylist(mPlaylist.getRef());
                                } catch (RemoteException e) {
                                    Log.e(TAG, "Cannot remove playlist", e);
                                }
                            }
                        }
                        getActivity().finish();
                    }
                })
                .setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                }).show();
    }

    private void updateFabStatus() {
        final int state = PlaybackProxy.getState();
        switch (state) {
            case PlaybackService.STATE_PAUSED:
            case PlaybackService.STATE_STOPPED:
                mFabDrawable.setShape(PlayPauseDrawable.SHAPE_PLAY);
                mFabDrawable.setBuffering(false);
                break;

            case PlaybackService.STATE_BUFFERING:
            case PlaybackService.STATE_PAUSING:
                mFabDrawable.setShape(PlayPauseDrawable.SHAPE_PAUSE);
                mFabDrawable.setBuffering(true);
                break;

            case PlaybackService.STATE_PLAYING:
                mFabDrawable.setShape(PlayPauseDrawable.SHAPE_PAUSE);
                mFabDrawable.setBuffering(false);
                break;
        }
    }

    public void notifyReturnTransition() {
        if (Utils.hasLollipop()) {
            Utils.animateHeadingHiding(mTvPlaylistName);
            Utils.animateScale(mPlayFab, true, false);

            mIvSource.setAlpha(0.0f);
            mIvSource.animate().alpha(1).setDuration(200).start();
        }
    }

    public ImageView getHeroImageView() {
        return mIvHero;
    }

    private void playNow() {
        PlaybackProxy.playPlaylist(mPlaylist);
        mFabDrawable.setShape(PlayPauseDrawable.SHAPE_PAUSE);
        mFabDrawable.setBuffering(true);

        showMaterialReelBar(mPlayFab);
    }

    private void playNext() {
        // playNext adds elements after the current playing one. If we want to play the playlist
        // in the proper order, we need to put it backwards.
        ListIterator<String> it = mPlaylist.songsList().listIterator();
        while (it.hasNext()) {
            it.next();
        }

        final ProviderAggregator aggregator = ProviderAggregator.getDefault();
        while (it.hasPrevious()) {
            PlaybackProxy.playNext(aggregator.retrieveSong(it.previous(), mPlaylist.getProvider()));
        }
    }

    private void removeDuplicates() throws RemoteException {
        // Process each track and look for the same track
        Iterator<String> songsIt = mPlaylist.songs();
        List<String> knownTracks = new ArrayList<>();

        // Only process if the provider is up
        ProviderConnection conn = PluginsLookup.getDefault().getProvider(mPlaylist.getProvider());
        if (conn != null) {
            IMusicProvider provider = conn.getBinder();
            if (provider != null) {
                int position = 0;
                while (songsIt.hasNext()) {
                    String songRef = songsIt.next();

                    // If we know the track, remove it (it's the second occurrence of the track).
                    // Else, add it to the known list and move on.
                    if (knownTracks.contains(songRef)) {
                        // Delete the song and restart the process
                        provider.deleteSongFromPlaylist(position, mPlaylist.getRef());
                        removeDuplicates();
                        return;
                    } else {
                        knownTracks.add(songRef);
                    }

                    ++position;
                }
            }
        }

    }

    private void updateOfflineStatus() {
        if (mPlaylist == null) {
            Log.e(TAG, "Calling updateOfflineStatus when mPlaylist is null!");
            return;
        }

        // We bias the playlist status based on the current offline mode
        ProviderAggregator aggregator = ProviderAggregator.getDefault();
        final int offlineStatus = mPlaylist.getOfflineStatus();

        switch (offlineStatus) {
            case BoundEntity.OFFLINE_STATUS_NO:
                mOfflineBtn.setProgress(0);
                break;

            case BoundEntity.OFFLINE_STATUS_READY:
                mOfflineBtn.setProgress(100);
                break;

            case BoundEntity.OFFLINE_STATUS_ERROR:
                mOfflineBtn.setProgress(-1);
                break;

            case BoundEntity.OFFLINE_STATUS_PENDING:
                mOfflineBtn.setProgress(50);
                mOfflineBtn.setIndeterminateProgressMode(true);
                break;

            case BoundEntity.OFFLINE_STATUS_DOWNLOADING:
                if (aggregator.isOfflineMode()) {
                    mOfflineBtn.setProgress(50);
                    mOfflineBtn.setIndeterminateProgressMode(true);
                } else {
                    mOfflineBtn.setIndeterminateProgressMode(false);
                    float numSyncTracks = getNumSyncTracks();
                    float numTracksToSync = 0;

                    // Count the number of tracks to sync (ie. num of tracks available)
                    final Iterator<String> songs = mPlaylist.songs();
                    while (songs.hasNext()) {
                        String ref = songs.next();
                        Song s = aggregator.retrieveSong(ref, mPlaylist.getProvider());
                        if (s != null && s.isAvailable()) {
                            ++numTracksToSync;
                        }
                    }

                    mOfflineBtn.setProgress(Math.min(100, numSyncTracks * 100.0f / numTracksToSync + 0.1f));
                }
                break;
        }

        if (mPlaylist.isLoaded() && mPlaylist.isOfflineCapable()) {
            if (mOfflineBtn.getAlpha() != 1) {
                mOfflineBtn.animate().alpha(1.0f).setDuration(300).start();
            }
        } else {
            if (mOfflineBtn.getAlpha() != 0) {
                mOfflineBtn.animate().alpha(0.0f).setDuration(300).start();
            }
        }
    }

    private int getNumSyncTracks() {
        final Iterator<String> it = mPlaylist.songs();
        final ProviderAggregator aggregator = ProviderAggregator.getDefault();

        int numSync = 0;
        while (it.hasNext()) {
            final String songRef = it.next();
            Song song = aggregator.retrieveSong(songRef, mPlaylist.getProvider());
            if (song.getOfflineStatus() == BoundEntity.OFFLINE_STATUS_READY) {
                numSync++;
            }
        }

        Log.d(TAG, "Num sync tracks: " + numSync);

        return numSync;
    }

    @Override
    public void onSongUpdate(List<Song> s) {
        // We check if the song belongs to this playlist
        boolean hasPlaylist = false;
        Iterator<String> songsRef = mPlaylist.songs();
        while (songsRef.hasNext()) {
            String ref = songsRef.next();
            for (Song song : s) {
                if (song.getRef().equals(ref)) {
                    hasPlaylist = true;
                    break;
                }
            }

            if (hasPlaylist) {
                break;
            }
        }

        // It does, update the list then
        if (hasPlaylist) {
            if (!mHandler.hasMessages(UPDATE_DATA_SET)) {
                mHandler.sendEmptyMessage(UPDATE_DATA_SET);
            }
            if (!mHandler.hasMessages(UPDATE_OFFLINE_STATUS)) {
                mHandler.sendEmptyMessage(UPDATE_OFFLINE_STATUS);
            }
        }
    }

    @Override
    public void onAlbumUpdate(List<Album> a) {
    }

    @Override
    public void onPlaylistUpdate(final List<Playlist> p) {
        // If the currently watched playlist is updated, update me
        if (p.contains(mPlaylist)) {
            if (!mHandler.hasMessages(UPDATE_DATA_SET)) {
                mHandler.sendEmptyMessage(UPDATE_DATA_SET);
            }
            if (!mHandler.hasMessages(UPDATE_OFFLINE_STATUS)) {
                mHandler.sendEmptyMessage(UPDATE_OFFLINE_STATUS);
            }
        }
    }

    @Override
    public void onArtistUpdate(List<Artist> a) {
        // We check if the artists belongs to this playlist
        boolean hasPlaylist = false;
        Iterator<String> songsRef = mPlaylist.songs();
        final ProviderAggregator aggregator = ProviderAggregator.getDefault();
        while (songsRef.hasNext()) {
            String ref = songsRef.next();
            Song song = aggregator.retrieveSong(ref, mPlaylist.getProvider());
            for (Artist artist : a) {
                if (song != null && artist.getRef().equals(song.getArtist())) {
                    hasPlaylist = true;
                    break;
                }
            }

            if (hasPlaylist) {
                break;
            }
        }

        // It does, update the list then
        if (hasPlaylist) {
            if (!mHandler.hasMessages(UPDATE_OFFLINE_STATUS)) {
                mHandler.sendEmptyMessage(UPDATE_OFFLINE_STATUS);
            }
        }
    }

    @Override
    public void onProviderConnected(IMusicProvider provider) {
    }

    @Override
    public void onSearchResult(List<SearchResult> searchResult) {
    }
}
