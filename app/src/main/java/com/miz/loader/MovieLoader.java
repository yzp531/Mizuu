/*
 * Copyright (C) 2014 Michell Bak
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

package com.miz.loader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.database.Cursor;
import android.text.TextUtils;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;
import com.miz.db.DbAdapterMovieMappings;
import com.miz.db.DbAdapterMovies;
import com.miz.db.DbAdapterSources;
import com.miz.functions.ColumnIndexCache;
import com.miz.functions.FileSource;
import com.miz.functions.Filepath;
import com.miz.functions.LibrarySectionAsyncTask;
import com.miz.functions.MediumMovie;
import com.miz.functions.MizLib;
import com.miz.mizuu.MizuuApplication;
import com.miz.mizuu.R;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.TreeMap;
import java.util.regex.Pattern;

import jcifs.smb.SmbFile;

public class MovieLoader {

    // For MovieLibraryType
    public static final int ALL_MOVIES = 1,
            FAVORITES = 2,
            NEW_RELEASES = 3,
            WATCHLIST = 4,
            WATCHED = 5,
            UNWATCHED = 6,
            COLLECTIONS = 7;

    // For MovieSortType
    public static final int TITLE = 1,
            RELEASE = 2,
            DURATION = 3,
            RATING = 4,
            WEIGHTED_RATING = 5,
            DATE_ADDED = 6,
            COLLECTION_TITLE = 7;

    private final Context mContext;
    private final MovieLibraryType mLibraryType;
    private final OnLoadCompletedCallback mCallback;
    private final DbAdapterMovies mDatabase;

    private MovieSortType mSortType;
    private ArrayList<MediumMovie> mResults = new ArrayList<MediumMovie>();
    private HashSet<MovieFilter> mFilters = new HashSet<MovieFilter>();
    private MovieLoaderAsyncTask mAsyncTask;
    private boolean mIgnorePrefixes = false,
            mShowingSearchResults = false;

    public MovieLoader(Context context, MovieLibraryType libraryType, OnLoadCompletedCallback callback) {
        mContext = context;
        mLibraryType = libraryType;
        mCallback = callback;
        mDatabase = MizuuApplication.getMovieAdapter();

        // Sort by title by default
        setSortType(mLibraryType != MovieLibraryType.COLLECTIONS ?
                MovieSortType.TITLE : MovieSortType.COLLECTION_TITLE);
    }

    public MovieLibraryType getType() {
        return mLibraryType;
    }

    /**
     * Determine whether the MovieLoader should ignore title prefixes.
     * @param ignore
     */
    public void setIgnorePrefixes(boolean ignore) {
        mIgnorePrefixes = ignore;
    }

    public void setSortType(MovieSortType type) {
        if (getSortType() == type) {
            getSortType().toggleSortOrder();
        } else {
            mSortType = type;
        }
    }

    public MovieSortType getSortType() {
        return mSortType;
    }

    public boolean isShowingSearchResults() {
        return mShowingSearchResults;
    }

    public void addFilter(MovieFilter filter) {
        mFilters.remove(filter);
        mFilters.add(filter);
    }

    public void clearFilters() {
        mFilters.clear();
    }

    public HashSet<MovieFilter> getFilters() {
        return mFilters;
    }

    public void load() {
        load("");
    }

    public void search(String query) {
        load(query);
    }

    private void load(String query) {
        if (mAsyncTask != null) {
            mAsyncTask.cancel(true);
        }

        mShowingSearchResults = !TextUtils.isEmpty(query);

        mAsyncTask = new MovieLoaderAsyncTask(query);
        mAsyncTask.execute();
    }

    /**
     * Creates movie objects from a Cursor and adds them to a list.
     * @param cursor
     * @return
     */
    private ArrayList<MediumMovie> listFromCursor(Cursor cursor) {

        // Normally we'd have to go through each movie and add filepaths mapped to that movie
        // one by one. This is a hacky approach that gets all filepaths at once and creates a
        // map of them. That way it's easy to get filepaths for a specific movie - and it's
        // 2-3x faster with ~750 movies.
        ArrayListMultimap<String, String> filepaths = ArrayListMultimap.create();
        Cursor paths = MizuuApplication.getMovieMappingAdapter().getAllFilepaths(false);
        if (paths != null) {
            try {
                while (paths.moveToNext()) {
                    filepaths.put(paths.getString(paths.getColumnIndex(DbAdapterMovieMappings.KEY_TMDB_ID)),
                            paths.getString(paths.getColumnIndex(DbAdapterMovieMappings.KEY_FILEPATH)));
                }
            } catch (Exception e) {} finally {
                paths.close();
                MizuuApplication.setMovieFilepaths(filepaths);
            }
        }

        HashMap<String, String> collectionsMap = MizuuApplication.getCollectionsAdapter().getCollectionsMap();
        ArrayList<MediumMovie> list = new ArrayList<MediumMovie>();

        if (cursor != null) {
            ColumnIndexCache cache = new ColumnIndexCache();

            try {
                while (cursor.moveToNext()) {
                    list.add(new MediumMovie(mContext,
                            cursor.getString(cache.getColumnIndex(cursor, DbAdapterMovies.KEY_TITLE)),
                            cursor.getString(cache.getColumnIndex(cursor, DbAdapterMovies.KEY_TMDB_ID)),
                            cursor.getString(cache.getColumnIndex(cursor, DbAdapterMovies.KEY_RATING)),
                            cursor.getString(cache.getColumnIndex(cursor, DbAdapterMovies.KEY_RELEASEDATE)),
                            cursor.getString(cache.getColumnIndex(cursor, DbAdapterMovies.KEY_GENRES)),
                            cursor.getString(cache.getColumnIndex(cursor, DbAdapterMovies.KEY_FAVOURITE)),
                            cursor.getString(cache.getColumnIndex(cursor, DbAdapterMovies.KEY_ACTORS)),
                            collectionsMap.get(cursor.getString(cache.getColumnIndex(cursor, DbAdapterMovies.KEY_COLLECTION_ID))),
                            cursor.getString(cache.getColumnIndex(cursor, DbAdapterMovies.KEY_COLLECTION_ID)),
                            cursor.getString(cache.getColumnIndex(cursor, DbAdapterMovies.KEY_TO_WATCH)),
                            cursor.getString(cache.getColumnIndex(cursor, DbAdapterMovies.KEY_HAS_WATCHED)),
                            cursor.getString(cache.getColumnIndex(cursor, DbAdapterMovies.KEY_DATE_ADDED)),
                            cursor.getString(cache.getColumnIndex(cursor, DbAdapterMovies.KEY_CERTIFICATION)),
                            cursor.getString(cache.getColumnIndex(cursor, DbAdapterMovies.KEY_RUNTIME)),
                            mIgnorePrefixes
                    ));
                }
            } catch (Exception e) {} finally {
                cursor.close();
                cache.clear();
            }
        }

        mResults = list;

        return list;
    }

    public ArrayList<MediumMovie> getResults() {
        return mResults;
    }

    private class MovieLoaderAsyncTask extends LibrarySectionAsyncTask<Void, Void, Void> {

        private final ArrayList<MediumMovie> mMovieList;
        private final String mSearchQuery;

        public MovieLoaderAsyncTask(String searchQuery) {
            // Lowercase in order to search more efficiently
            mSearchQuery = searchQuery.toLowerCase(Locale.getDefault());

            mMovieList = new ArrayList<MediumMovie>();
        }

        @Override
        protected Void doInBackground(Void... params) {

            // We're only interested in querying the database
            // if we're not searching for a query
            switch (mLibraryType) {
                case ALL_MOVIES:
                    mMovieList.addAll(listFromCursor(mDatabase.getAllMovies()));
                    break;
                case COLLECTIONS:
                    mMovieList.addAll(listFromCursor(mDatabase.getCollections()));
                    break;
                case FAVORITES:
                    mMovieList.addAll(listFromCursor(mDatabase.getFavorites()));
                    break;
                case NEW_RELEASES:
                    mMovieList.addAll(listFromCursor(mDatabase.getNewReleases()));
                    break;
                case UNWATCHED:
                    mMovieList.addAll(listFromCursor(mDatabase.getUnwatched()));
                    break;
                case WATCHED:
                    mMovieList.addAll(listFromCursor(mDatabase.getWatched()));
                    break;
                case WATCHLIST:
                    mMovieList.addAll(listFromCursor(mDatabase.getWatchlist()));
                    break;
                default:
                    break;
            }

            int totalSize = mMovieList.size();

            for (MovieFilter filter : getFilters()) {
                for (int i = 0; i < totalSize; i++) {

                    boolean condition = false;

                    switch (filter.getType()) {
                        case MovieFilter.GENRE:

                            if (mMovieList.get(i).getGenres().contains(filter.getFilter())) {
                                String[] genres = mMovieList.get(i).getGenres().split(",");
                                for (String genre : genres) {
                                    if (genre.trim().equals(filter.getFilter())) {
                                        condition = true;
                                        break;
                                    }
                                }
                            }

                            break;

                        case MovieFilter.CERTIFICATION:

                            condition = mMovieList.get(i).getCertification().trim().equals(filter.getFilter());

                            break;

                        case MovieFilter.FILE_SOURCE:

                            for (Filepath path : mMovieList.get(i).getFilepaths()) {
                                condition = path.getTypeAsString(mContext).equals(filter.getFilter());
                                if (condition)
                                    break;
                            }

                            break;

                        case MovieFilter.RELEASE_YEAR:

                            condition = mMovieList.get(i).getReleaseYear().trim().contains(filter.getFilter());

                            break;

                        case MovieFilter.FOLDER:

                            for (Filepath path : mMovieList.get(i).getFilepaths()) {
                                condition = path.getFilepath().trim().startsWith(filter.getFilter());
                                if (condition)
                                    break;
                            }

                            break;

                        case MovieFilter.OFFLINE_FILES:

                            for (Filepath path : mMovieList.get(i).getFilepaths()) {
                                condition = mMovieList.get(i).hasOfflineCopy(path);
                                if (condition)
                                    break;
                            }

                            break;

                        case MovieFilter.AVAILABLE_FILES:

                            ArrayList<FileSource> filesources = MizLib.getFileSources(MizLib.TYPE_MOVIE, true);

                            if (isCancelled())
                                return null;

                            for (Filepath path : mMovieList.get(i).getFilepaths()) {
                                if (path.isNetworkFile())
                                    if (mMovieList.get(i).hasOfflineCopy(path)) {
                                        condition = true;
                                        break; // break inner loop to continue to the next movie
                                    } else {
                                        if (path.getType() == FileSource.SMB) {
                                            if (MizLib.isWifiConnected(mContext)) {
                                                FileSource source = null;

                                                for (int j = 0; j < filesources.size(); j++)
                                                    if (path.getFilepath().contains(filesources.get(j).getFilepath())) {
                                                        source = filesources.get(j);
                                                        continue;
                                                    }

                                                if (source == null)
                                                    continue;

                                                try {
                                                    final SmbFile file = new SmbFile(
                                                            MizLib.createSmbLoginString(
                                                                    source.getDomain(),
                                                                    source.getUser(),
                                                                    source.getPassword(),
                                                                    path.getFilepath(),
                                                                    false
                                                            ));
                                                    if (file.exists()) {
                                                        condition = true;
                                                        break; // break inner loop to continue to the next movie
                                                    }
                                                } catch (Exception e) {}  // Do nothing - the file isn't available (either MalformedURLException or SmbException)
                                            }
                                        } else if (path.getType() == FileSource.UPNP) {
                                            if (MizLib.exists(path.getFilepath())) {
                                                condition = true;
                                                break; // break inner loop to continue to the next movie
                                            }
                                        }
                                    } else {
                                    if (new File(path.getFilepath()).exists()) {
                                        condition = true;
                                        break; // break inner loop to continue to the next movie
                                    }
                                }
                            }

                            break;
                    }

                    if (!condition && mMovieList.size() > i) {
                        mMovieList.remove(i);
                        i--;
                        totalSize--;
                    }
                }
            }

            // If we've got a search query, we should search based on it
            if (!TextUtils.isEmpty(mSearchQuery)) {

                ArrayList<MediumMovie> tempCollection = Lists.newArrayList();

                if (mSearchQuery.startsWith("actor:")) {
                    for (int i = 0; i < mResults.size(); i++) {
                        if (isCancelled())
                            return null;

                        if (mResults.get(i).getCast().toLowerCase(Locale.ENGLISH).contains(mSearchQuery.replace("actor:", "").trim()))
                            tempCollection.add(mResults.get(i));
                    }
                } else if (mSearchQuery.equalsIgnoreCase("missing_genres")) {
                    for (int i = 0; i < mResults.size(); i++) {
                        if (isCancelled())
                            return null;

                        if (TextUtils.isEmpty(mResults.get(i).getGenres()))
                            tempCollection.add(mResults.get(i));
                    }
                } else if (mSearchQuery.equalsIgnoreCase("multiple_versions")) {
                    DbAdapterMovieMappings db = MizuuApplication.getMovieMappingAdapter();

                    for (int i = 0; i < mResults.size(); i++) {
                        if (isCancelled())
                            return null;

                        if (db.hasMultipleFilepaths(mResults.get(i).getTmdbId()))
                            tempCollection.add(mResults.get(i));
                    }
                } else {
                    Pattern p = Pattern.compile(MizLib.CHARACTER_REGEX); // Use a pre-compiled pattern as it's a lot faster (approx. 3x for ~700 movies)

                    for (int i = 0; i < mResults.size(); i++) {
                        if (isCancelled())
                            return null;

                        String lowerCaseTitle = (getType() == MovieLibraryType.COLLECTIONS) ?
                                mResults.get(i).getCollection().toLowerCase(Locale.ENGLISH) :
                                mResults.get(i).getTitle().toLowerCase(Locale.ENGLISH);

                        boolean foundInTitle = false;

                        if (lowerCaseTitle.indexOf(mSearchQuery) != -1 || p.matcher(lowerCaseTitle).replaceAll("").indexOf(mSearchQuery) != -1) {
                            tempCollection.add(mResults.get(i));
                            foundInTitle = true;
                        }

                        if (!foundInTitle) {
                            for (Filepath path : mResults.get(i).getFilepaths()) {
                                String filepath = path.getFilepath().toLowerCase(Locale.ENGLISH);
                                if (filepath.indexOf(mSearchQuery) != -1) {
                                    tempCollection.add(mResults.get(i));
                                    break; // Break the loop
                                }
                            }
                        }
                    }
                }

                // Clear the movie list
                mMovieList.clear();

                // Add all the temporary ones after search completed
                mMovieList.addAll(tempCollection);

                // Clear the temporary list
                tempCollection.clear();
            }

            // Sort
            Collections.sort(mMovieList, getSortType().getComparator());

            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            if (!isCancelled()) {
                mResults = new ArrayList<>(mMovieList);
                mCallback.onLoadCompleted();
            } else
                mMovieList.clear();
        }
    }

    public void showGenresFilterDialog(Activity activity) {
        final TreeMap<String, Integer> map = new TreeMap<String, Integer>();
        String[] splitGenres;
        for (int i = 0; i < mResults.size(); i++) {
            if (!mResults.get(i).getGenres().isEmpty()) {
                splitGenres = mResults.get(i).getGenres().split(",");
                for (int j = 0; j < splitGenres.length; j++) {
                    if (map.containsKey(splitGenres[j].trim())) {
                        map.put(splitGenres[j].trim(), map.get(splitGenres[j].trim()) + 1);
                    } else {
                        map.put(splitGenres[j].trim(), 1);
                    }
                }
            }
        }

        createAndShowAlertDialog(activity, setupItemArray(map), R.string.selectGenre, MovieFilter.GENRE);
    }

    public void showCertificationsFilterDialog(Activity activity) {
        final TreeMap<String, Integer> map = new TreeMap<String, Integer>();
        for (int i = 0; i < mResults.size(); i++) {
            String certification = mResults.get(i).getCertification();
            if (!TextUtils.isEmpty(certification)) {
                if (map.containsKey(certification.trim())) {
                    map.put(certification.trim(), map.get(certification.trim()) + 1);
                } else {
                    map.put(certification.trim(), 1);
                }
            }
        }

        createAndShowAlertDialog(activity, setupItemArray(map), R.string.selectCertification, MovieFilter.CERTIFICATION);
    }

    public void showReleaseYearFilterDialog(Activity activity) {
        final TreeMap<String, Integer> map = new TreeMap<String, Integer>();
        for (int i = 0; i < mResults.size(); i++) {
            String year = mResults.get(i).getReleaseYear().trim();
            if (!TextUtils.isEmpty(year)) {
                if (map.containsKey(year)) {
                    map.put(year, map.get(year) + 1);
                } else {
                    map.put(year, 1);
                }
            }
        }

        createAndShowAlertDialog(activity, setupItemArray(map), R.string.selectReleaseYear, MovieFilter.RELEASE_YEAR);
    }

    public void showFileSourcesFilterDialog(Activity activity) {
        final TreeMap<String, Integer> map = new TreeMap<String, Integer>();

        for (int i = 0; i < mResults.size(); i++) {
            for (Filepath path : mResults.get(i).getFilepaths()) {
                String type = path.getTypeAsString(activity);
                if (map.containsKey(type)) {
                    map.put(type, map.get(type) + 1);
                } else {
                    map.put(type, 1);
                }
            }
        }

        createAndShowAlertDialog(activity, setupItemArray(map), R.string.selectFileSource, MovieFilter.FILE_SOURCE);
    }

    public void showFoldersFilterDialog(Activity activity) {
        final TreeMap<String, Integer> map = new TreeMap<String, Integer>();
        for (int i = 0; i < mResults.size(); i++) {
            for (Filepath path : mResults.get(i).getFilepaths()) {
                String folder = path.getFolder();
                if (!TextUtils.isEmpty(folder)) {
                    if (map.containsKey(folder)) {
                        map.put(folder, map.get(folder) + 1);
                    } else {
                        map.put(folder, 1);
                    }
                }
            }
        }

        createAndShowAlertDialog(activity, setupItemArray(map), R.string.selectFolder, MovieFilter.FOLDER);
    }

    private CharSequence[] setupItemArray(TreeMap<String, Integer> map) {
        final CharSequence[] tempArray = map.keySet().toArray(new CharSequence[map.keySet().size()]);
        for (int i = 0; i < tempArray.length; i++)
            tempArray[i] = tempArray[i] + " (" + map.get(tempArray[i]) +  ")";

        return tempArray;
    }

    private void createAndShowAlertDialog(Activity activity, final CharSequence[] temp, int title, final int type) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(title)
                .setItems(temp, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        // Let's get what the user selected and remove the parenthesis at the end
                        String selected = temp[which].toString();
                        selected = selected.substring(0, selected.lastIndexOf("(")).trim();

                        // Add filter
                        MovieFilter filter = new MovieFilter(type);
                        filter.setFilter(selected);
                        addFilter(filter);

                        // Re-load the library with the new filter
                        load();

                        // Dismiss the dialog
                        dialog.dismiss();
                    }
                });
        builder.show();
    }
}