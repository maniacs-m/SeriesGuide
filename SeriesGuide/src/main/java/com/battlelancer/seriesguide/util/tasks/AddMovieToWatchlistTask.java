package com.battlelancer.seriesguide.util.tasks;

import android.content.Context;
import android.support.annotation.NonNull;
import com.battlelancer.seriesguide.R;
import com.battlelancer.seriesguide.SgApp;
import com.battlelancer.seriesguide.util.MovieTools;
import com.uwetrottmann.seriesguide.backend.movies.model.Movie;
import com.uwetrottmann.trakt5.entities.SyncItems;
import com.uwetrottmann.trakt5.entities.SyncResponse;
import com.uwetrottmann.trakt5.services.Sync;
import retrofit2.Call;

public class AddMovieToWatchlistTask extends BaseMovieActionTask {

    private final SgApp app;

    public AddMovieToWatchlistTask(SgApp app, int movieTmdbId) {
        super(app, movieTmdbId);
        this.app = app;
    }

    @Override
    protected boolean doDatabaseUpdate(Context context, int movieTmdbId) {
        return MovieTools.getInstance(app).addToList(movieTmdbId, MovieTools.Lists.WATCHLIST);
    }

    @Override
    protected void setHexagonMovieProperties(Movie movie) {
        movie.setIsInWatchlist(true);
    }

    @NonNull
    @Override
    protected String getTraktAction() {
        return "add movie to watchlist";
    }

    @NonNull
    @Override
    protected Call<SyncResponse> doTraktAction(Sync traktSync, SyncItems items) {
        return traktSync.addItemsToWatchlist(items);
    }

    @Override
    protected int getSuccessTextResId() {
        return R.string.watchlist_added;
    }
}
