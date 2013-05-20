
package com.battlelancer.seriesguide.sync;

import android.accounts.Account;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.Context;
import android.content.OperationApplicationException;
import android.content.SharedPreferences;
import android.content.SyncResult;
import android.database.Cursor;
import android.os.Bundle;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;

import com.battlelancer.seriesguide.SeriesGuideApplication;
import com.battlelancer.seriesguide.items.SearchResult;
import com.battlelancer.seriesguide.provider.SeriesContract.Episodes;
import com.battlelancer.seriesguide.provider.SeriesContract.Shows;
import com.battlelancer.seriesguide.ui.SeriesGuidePreferences;
import com.battlelancer.seriesguide.util.Lists;
import com.battlelancer.seriesguide.util.ServiceUtils;
import com.battlelancer.seriesguide.util.TaskManager;
import com.battlelancer.seriesguide.util.Utils;
import com.battlelancer.thetvdbapi.TheTVDB;
import com.jakewharton.apibuilder.ApiException;
import com.jakewharton.trakt.ServiceManager;
import com.jakewharton.trakt.TraktException;
import com.jakewharton.trakt.entities.Activity;
import com.jakewharton.trakt.entities.ActivityItem;
import com.jakewharton.trakt.entities.TvShowEpisode;
import com.jakewharton.trakt.enumerations.ActivityAction;
import com.jakewharton.trakt.enumerations.ActivityType;
import com.uwetrottmann.androidutils.AndroidUtils;
import com.uwetrottmann.tmdb.TmdbException;
import com.uwetrottmann.tmdb.entities.Configuration;

import org.xml.sax.SAXException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link AbstractThreadedSyncAdapter} which updates the show library.
 */
public class SgSyncAdapter extends AbstractThreadedSyncAdapter {

    private static final String TAG = "SgSyncAdapter";

    public static final int UPDATE_INTERVAL = 30;

    private ArrayList<SearchResult> mNewShows;

    public SgSyncAdapter(Context context, boolean autoInitialize) {
        super(context, autoInitialize);
        Log.d(TAG, "Creating SyncAdapter");
    }

    enum UpdateResult {
        SILENT_SUCCESS, ERROR, OFFLINE, CANCELLED, INCOMPLETE;
    }

    enum UpdateType {
        AUTO_SINGLE, DELTA, FULL
    }

    @Override
    public void onPerformSync(Account account, Bundle extras, String authority,
            ContentProviderClient provider, SyncResult syncResult) {
        Log.d(TAG, "Starting to sync shows");

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        final ContentResolver resolver = getContext().getContentResolver();
        final long currentTime = System.currentTimeMillis();
        UpdateType mUpdateType = UpdateType.DELTA;
        UpdateResult resultCode = UpdateResult.SILENT_SUCCESS;
        String[] mShows = null;
        final AtomicInteger updateCount = new AtomicInteger();

        // build a list of shows to update
        if (mShows == null) {
            mShows = getShowsToUpdate(mUpdateType, currentTime);
        }

        // actually update the shows
        for (int i = updateCount.get(); i < mShows.length; i++) {
            String id = mShows[i];

            // try to contact TVDb two times
            for (int itry = 0; itry < 2; itry++) {
                // stop sync if connectivity is lost
                if (!AndroidUtils.isNetworkConnected(getContext())) {
                    resultCode = UpdateResult.INCOMPLETE;
                    break;
                }

                try {
                    TheTVDB.updateShow(id, getContext());

                    // make sure overview and details loaders are notified
                    resolver.notifyChange(Episodes.CONTENT_URI_WITHSHOW, null);

                    break;
                } catch (SAXException e) {
                    if (itry == 1) {
                        // failed twice, report error
                        resultCode = UpdateResult.INCOMPLETE;
                        Utils.trackExceptionAndLog(getContext(), TAG, e);
                    }
                }
            }

            // stop sync if connectivity is lost
            if (!AndroidUtils.isNetworkConnected(getContext())) {
                resultCode = UpdateResult.INCOMPLETE;
                break;
            }
            updateCount.incrementAndGet();
        }

        /*
         * Renew search table, get trakt activity and the latest tmdb config if
         * we did update multiple shows.
         */
        if (mUpdateType != UpdateType.AUTO_SINGLE) {

            if (updateCount.get() > 0 && mShows.length > 0) {
                // renew search table
                TheTVDB.onRenewFTSTable(getContext());

                // get latest TMDb configuration
                try {
                    Configuration config = ServiceUtils.getTmdbServiceManager(getContext())
                            .configurationService()
                            .configuration().fire();
                    if (config != null && config.images != null
                            && !TextUtils.isEmpty(config.images.base_url)) {
                        prefs.edit()
                                .putString(SeriesGuidePreferences.KEY_TMDB_BASE_URL,
                                        config.images.base_url).commit();
                    }
                } catch (TmdbException e) {
                    Utils.trackExceptionAndLog(getContext(), TAG, e);
                } catch (ApiException e) {
                    Utils.trackExceptionAndLog(getContext(), TAG, e);
                }
            }

            // get newly watched episodes from trakt
            final UpdateResult traktResult = getTraktActivity(currentTime);

            // do not overwrite earlier failure codes
            if (resultCode == UpdateResult.SILENT_SUCCESS) {
                resultCode = traktResult;
            }

            // store time of update, set retry counter on failure
            if (resultCode == UpdateResult.SILENT_SUCCESS) {
                // we were successful, reset failed counter
                prefs.edit().putLong(SeriesGuidePreferences.KEY_LASTUPDATE, currentTime)
                        .putInt(SeriesGuidePreferences.KEY_FAILED_COUNTER, 0).commit();
            } else {
                int failed = prefs.getInt(SeriesGuidePreferences.KEY_FAILED_COUNTER, 0);

                /*
                 * Back off by 2**(failure + 2) * minutes. Purposely set a fake
                 * last update time, because the next update will be triggered
                 * UPDATE_INTERVAL minutes after the last update time. This way
                 * we can trigger it earlier (4min up to 32min).
                 */
                long fakeLastUpdateTime;
                if (failed < 4) {
                    fakeLastUpdateTime = currentTime
                            - ((UPDATE_INTERVAL - (int) Math.pow(2, failed + 2)) * DateUtils.MINUTE_IN_MILLIS);
                } else {
                    fakeLastUpdateTime = currentTime;
                }

                failed += 1;
                prefs.edit()
                        .putLong(SeriesGuidePreferences.KEY_LASTUPDATE, fakeLastUpdateTime)
                        .putInt(SeriesGuidePreferences.KEY_FAILED_COUNTER, failed).commit();
            }
        }

        // add newly discovered shows to database
        if (mNewShows != null && mNewShows.size() > 0) {
            TaskManager.getInstance(getContext()).performAddTask(mNewShows);
        }

        // There could have been new episodes added after an update
        Utils.runNotificationService(getContext());

    }

    /**
     * Returns an array of show ids to update.
     */
    private String[] getShowsToUpdate(UpdateType type, long currentTime) {
        switch (type) {
            case FULL:
                // get all show IDs for a full update
                final Cursor shows = getContext().getContentResolver().query(Shows.CONTENT_URI,
                        new String[] {
                            Shows._ID
                        }, null, null, null);

                String[] showIds = new String[shows.getCount()];
                int i = 0;
                while (shows.moveToNext()) {
                    showIds[i] = shows.getString(0);
                    i++;
                }

                shows.close();

                return showIds;
            case DELTA:
            default:
                // Get shows which have not been updated for a certain time.
                return TheTVDB.deltaUpdateShows(currentTime, getContext());
        }
    }

    private UpdateResult getTraktActivity(long currentTime) {
        if (!ServiceUtils.isTraktCredentialsValid(getContext())) {
            // trakt is not connected, we are done here
            return UpdateResult.SILENT_SUCCESS;
        }

        // return if connectivity is lost
        if (!AndroidUtils.isNetworkConnected(getContext())) {
            return UpdateResult.INCOMPLETE;
        }

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        final ContentResolver resolver = getContext().getContentResolver();

        // get last trakt update timestamp
        final long startTimeTrakt = prefs.getLong(SeriesGuidePreferences.KEY_LASTTRAKTUPDATE,
                currentTime) / 1000;

        ServiceManager manager = ServiceUtils
                .getTraktServiceManagerWithAuth(getContext(), false);
        if (manager == null) {
            return UpdateResult.INCOMPLETE;
        }

        // get episode activity from trakt
        Activity activity;
        try {
            activity = manager
                    .activityService()
                    .user(ServiceUtils.getTraktUsername(getContext()))
                    .types(ActivityType.Episode)
                    .actions(ActivityAction.Checkin, ActivityAction.Seen,
                            ActivityAction.Scrobble, ActivityAction.Collection)
                    .timestamp(startTimeTrakt).fire();
        } catch (TraktException e) {
            Utils.trackExceptionAndLog(getContext(), TAG, e);
            return UpdateResult.INCOMPLETE;
        } catch (ApiException e) {
            Utils.trackExceptionAndLog(getContext(), TAG, e);
            return UpdateResult.INCOMPLETE;
        }

        if (activity == null || activity.activity == null) {
            return UpdateResult.INCOMPLETE;
        }

        // get a list of existing shows
        boolean isAutoAddingShows = prefs.getBoolean(
                SeriesGuidePreferences.KEY_AUTO_ADD_TRAKT_SHOWS, true);
        final HashSet<String> existingShows = new HashSet<String>();
        if (isAutoAddingShows) {
            final Cursor shows = resolver.query(Shows.CONTENT_URI,
                    new String[] {
                        Shows._ID
                    }, null, null, null);
            if (shows != null) {
                while (shows.moveToNext()) {
                    existingShows.add(shows.getString(0));
                }
                shows.close();
            }
        }

        // build an update batch
        mNewShows = Lists.newArrayList();
        final HashSet<String> newShowIds = new HashSet<String>();
        final ArrayList<ContentProviderOperation> batch = Lists.newArrayList();
        for (ActivityItem item : activity.activity) {
            // check for null (potential fix for reported crash)
            if (item.action != null && item.show != null) {
                if (isAutoAddingShows && !existingShows.contains(item.show.tvdbId)
                        && !newShowIds.contains(item.show.tvdbId)) {
                    SearchResult show = new SearchResult();
                    show.title = item.show.title;
                    show.tvdbid = item.show.tvdbId;
                    mNewShows.add(show);
                    newShowIds.add(item.show.tvdbId); // prevent duplicates
                } else {
                    // show added, get watched episodes
                    switch (item.action) {
                        case Seen: {
                            // seen uses an array of episodes
                            List<TvShowEpisode> episodes = item.episodes;
                            int season = -1;
                            int number = -1;
                            for (TvShowEpisode episode : episodes) {
                                if (episode.season > season || episode.number > number) {
                                    season = episode.season;
                                    number = episode.number;
                                }
                                addEpisodeSeenUpdateOp(batch, episode, item.show.tvdbId);
                            }
                            // set highest season + number combo as last
                            // watched
                            if (season != -1 && number != -1) {
                                addLastWatchedUpdateOp(resolver, batch, season, number,
                                        item.show.tvdbId);
                            }
                            break;
                        }
                        case Checkin:
                        case Scrobble: {
                            // checkin and scrobble use a single episode
                            TvShowEpisode episode = item.episode;
                            addEpisodeSeenUpdateOp(batch, episode, item.show.tvdbId);
                            addLastWatchedUpdateOp(resolver, batch, episode.season,
                                    episode.number, item.show.tvdbId);
                            break;
                        }
                        case Collection: {
                            // collection uses an array of episodes
                            List<TvShowEpisode> episodes = item.episodes;
                            for (TvShowEpisode episode : episodes) {
                                addEpisodeCollectedUpdateOp(batch, episode, item.show.tvdbId);
                            }
                            break;
                        }
                        default:
                            break;
                    }
                }
            }
        }

        // execute the batch
        try {
            getContext().getContentResolver()
                    .applyBatch(SeriesGuideApplication.CONTENT_AUTHORITY, batch);
        } catch (RemoteException e) {
            // Failed binder transactions aren't recoverable
            Utils.trackExceptionAndLog(getContext(), TAG, e);
            throw new RuntimeException("Problem applying batch operation", e);
        } catch (OperationApplicationException e) {
            // Failures like constraint violation aren't
            // recoverable
            Utils.trackExceptionAndLog(getContext(), TAG, e);
            throw new RuntimeException("Problem applying batch operation", e);
        }

        // store time of this update as seen by the trakt server
        prefs.edit()
                .putLong(SeriesGuidePreferences.KEY_LASTTRAKTUPDATE,
                        activity.timestamps.current.getTime()).commit();

        return UpdateResult.SILENT_SUCCESS;
    }

    /**
     * Helper method to build update to flag an episode watched.
     */
    private static void addEpisodeSeenUpdateOp(final ArrayList<ContentProviderOperation> batch,
            TvShowEpisode episode, String showTvdbId) {
        batch.add(ContentProviderOperation.newUpdate(Episodes.buildEpisodesOfShowUri(showTvdbId))
                .withSelection(Episodes.NUMBER + "=? AND " + Episodes.SEASON + "=?", new String[] {
                        String.valueOf(episode.number), String.valueOf(episode.season)
                }).withValue(Episodes.WATCHED, true).build());
    }

    /**
     * Helper method to build update to flag an episode collected.
     */
    private static void addEpisodeCollectedUpdateOp(ArrayList<ContentProviderOperation> batch,
            TvShowEpisode episode, String showTvdbId) {
        batch.add(ContentProviderOperation.newUpdate(Episodes.buildEpisodesOfShowUri(showTvdbId))
                .withSelection(Episodes.NUMBER + "=? AND " + Episodes.SEASON + "=?", new String[] {
                        String.valueOf(episode.number), String.valueOf(episode.season)
                }).withValue(Episodes.COLLECTED, true).build());
    }

    /**
     * Queries for an episode id and adds a content provider op to set it as
     * last watched for the given show.
     */
    private void addLastWatchedUpdateOp(ContentResolver resolver,
            ArrayList<ContentProviderOperation> batch, int season, int number, String showTvdbId) {
        // query for the episode id
        final Cursor episode = resolver.query(
                Episodes.buildEpisodesOfShowUri(showTvdbId),
                new String[] {
                    Episodes._ID
                }, Episodes.SEASON + "=" + season + " AND "
                        + Episodes.NUMBER + "=" + number, null, null);

        // store the episode id as last watched for the given show
        if (episode != null) {
            if (episode.moveToFirst()) {
                batch.add(ContentProviderOperation.newUpdate(Shows.buildShowUri(showTvdbId))
                        .withValue(Shows.LASTWATCHEDID, episode.getInt(0)).build());
            }

            episode.close();
        }
    }

}
