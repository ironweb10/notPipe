package io.github.gohoski.notpipe;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.AutoCompleteTextView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Calendar;
import java.util.List;

import io.github.gohoski.notpipe.api.Manager;
import io.github.gohoski.notpipe.api.Metadata;
import io.github.gohoski.notpipe.api.Trending;
import io.github.gohoski.notpipe.config.Config;
import io.github.gohoski.notpipe.config.ConfigManager;
import io.github.gohoski.notpipe.data.VideoInfo;
import io.github.gohoski.notpipe.ui.AutoCompleteAdapter;
import io.github.gohoski.notpipe.ui.VideoAdapter;
import io.github.gohoski.notpipe.util.ImageLoader;
import io.github.gohoski.notpipe.util.InstancesUpdater;

public class MainActivity extends Activity implements InstancesUpdater.OnInstancesUpdatedListener {
    private static final String STATE_SEARCH_QUERY = "search_query";
    private static final String STATE_IS_SEARCH_MODE = "is_search_mode";

    private VideoAdapter adapter;
    private List<VideoInfo> videos;
    private AutoCompleteTextView searchQuery;
    private ListView listView;
    private Context context;
    private Trending trending = null;
    private Metadata metadata;
    private AutoCompleteAdapter autoCompleteAdapter;
    private Config config = ConfigManager.getInstance().getConfig();
    private boolean isSearchMode = false;
    private boolean isDestroyedFlag = false;

    private static class RetainedState {
        List<VideoInfo> videos;
        boolean isSearchMode;
        String query;
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        RetainedState state = new RetainedState();
        state.videos = this.videos;
        state.isSearchMode = this.isSearchMode;
        if (searchQuery != null) {
            state.query = searchQuery.getText().toString();
        }
        return state;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        listView = (ListView) findViewById(R.id.videos);
        searchQuery = (AutoCompleteTextView) findViewById(R.id.search_query);
        final ImageButton searchBtn = (ImageButton) findViewById(R.id.search_btn);
        final ProgressBar loading = (ProgressBar) findViewById(R.id.loading);
        final LinearLayout noTrending = (LinearLayout) findViewById(R.id.no_trending);
        context = this;

        try {
            trending = Manager.getInstance().getTrending();
        } catch(IllegalStateException ignored) {}
        try {
            metadata = Manager.getInstance().getMetadata();
        } catch(IllegalStateException ignored) {}

        RetainedState retained = (RetainedState) getLastNonConfigurationInstance();
        if (retained != null) {
            videos = retained.videos;
            isSearchMode = retained.isSearchMode;
        } else if (savedInstanceState != null) {
            isSearchMode = savedInstanceState.getBoolean(STATE_IS_SEARCH_MODE, false);
        }

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                ImageLoader.clearCache();
                System.gc();
                Intent intent = new Intent(MainActivity.this, VideoActivity.class);
                intent.putExtra("ID", ((VideoInfo) parent.getItemAtPosition(position)).id);
                startActivity(intent);
            }
        });

        autoCompleteAdapter = new AutoCompleteAdapter(this);
        autoCompleteAdapter.setOnSuggestionsLoadedListener(new AutoCompleteAdapter.OnSuggestionsLoadedListener() {
            @Override
            public void onSuggestionsLoaded() {
                if (!isDestroyedFlag && searchQuery != null) {
                    searchQuery.showDropDown();
                }
            }
        });
        searchQuery.setAdapter(autoCompleteAdapter);
        searchQuery.setThreshold(3);

        if (retained != null && retained.query != null) {
            searchQuery.setText(retained.query);
        } else if (savedInstanceState != null) {
            String query = savedInstanceState.getString(STATE_SEARCH_QUERY);
            if (query != null) searchQuery.setText(query);
        }
        searchQuery.dismissDropDown();

        searchQuery.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (searchQuery.hasFocus()) {
                    autoCompleteAdapter.setSearchActive(false);
                    autoCompleteAdapter.requestSuggestions(s.toString());
                }
            }
        });
        searchQuery.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                String suggestion = (String) parent.getItemAtPosition(position);
                autoCompleteAdapter.setSearchActive(true);
                searchQuery.setText(suggestion);
                hideKeyboard();
                searchBtn.performClick();
            }
        });
        searchBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String query = searchQuery.getText().toString().trim();
                if (query.length() != 0) {
                    searchQuery.dismissDropDown();
                    autoCompleteAdapter.setSearchActive(true);
                    isSearchMode = true;
                    noTrending.setVisibility(View.GONE);
                    loading.setVisibility(View.VISIBLE);
                    hideKeyboard();
                    new SearchTask().execute(query);
                }
            }
        });

        if (videos == null) {
            boolean isUpdatingInstances = false;
            if (config.isUpdateInstancesFromUrl()) {
                Calendar now = Calendar.getInstance();
                Calendar lastUpdate = Calendar.getInstance();
                now.setTimeInMillis(System.currentTimeMillis());
                lastUpdate.setTimeInMillis(config.getLastUpdate());
                now.set(Calendar.HOUR_OF_DAY, 0);
                now.set(Calendar.MINUTE, 0);
                now.set(Calendar.SECOND, 0);
                now.set(Calendar.MILLISECOND, 0);
                lastUpdate.set(Calendar.HOUR_OF_DAY, 0);
                lastUpdate.set(Calendar.MINUTE, 0);
                lastUpdate.set(Calendar.SECOND, 0);
                lastUpdate.set(Calendar.MILLISECOND, 0);
                if (Math.round((double) ((now.getTimeInMillis() - lastUpdate.getTimeInMillis()) / (24L * 60 * 60 * 1000))) >= config.getUpdateFrequency()) {
                    isUpdatingInstances = true;
                    new InstancesUpdater(this, this).updateInstances();
                }
            }

            if (!isUpdatingInstances) {
                if (isSearchMode && searchQuery.getText().toString().trim().length() > 0) {
                    loading.setVisibility(View.VISIBLE);
                    new SearchTask().execute(searchQuery.getText().toString().trim());
                } else {
                    isSearchMode = false;
                    new TrendingTask().execute();
                }
            }
        } else {
            loading.setVisibility(View.GONE);
            noTrending.setVisibility(View.GONE);
            adapter = new VideoAdapter(this, R.layout.video_item, videos);
            listView.setAdapter(adapter);
        }
    }

    @Override
    public void onInstancesUpdated() {
        if (isDestroyedFlag) return;
        if (videos == null) {
            if (isSearchMode && searchQuery.getText().toString().trim().length() > 0) {
                findViewById(R.id.no_trending).setVisibility(View.GONE);
                findViewById(R.id.loading).setVisibility(View.VISIBLE);
                new SearchTask().execute(searchQuery.getText().toString().trim());
            } else {
                isSearchMode = false;
                new TrendingTask().execute();
            }
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && searchQuery != null) {
            imm.hideSoftInputFromWindow(searchQuery.getWindowToken(), 0);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (searchQuery != null) {
            outState.putString(STATE_SEARCH_QUERY, searchQuery.getText().toString());
        }
        outState.putBoolean(STATE_IS_SEARCH_MODE, isSearchMode);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        // Skip calling super when the adapter isn't attached yet to prevent
        // ListView crashes, but safely restore list scroll position if it is.
        if (adapter != null) {
            try {
                super.onRestoreInstanceState(savedInstanceState);
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        ImageLoader.clearCache();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isDestroyedFlag = true;
        // Only clear the cache if the activity is dying completely, NOT on rotation
        if (isFinishing()) {
            ImageLoader.clearCache();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            finish();
            return true;
        } else if (item.getItemId() == R.id.menu_about) {
            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(15, 15, 15, 15);

            final TextView app = new TextView(this);
            app.setText(getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME);
            app.setTypeface(null, Typeface.BOLD);
            app.setTextSize(20);
            app.setGravity(Gravity.CENTER_HORIZONTAL);
            layout.addView(app);

            final ImageView imageView = new ImageView(this);
            imageView.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.FILL_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            imageView.setImageResource(R.drawable.main_icon);
            layout.addView(imageView);

            final TextView text = new TextView(this);
            text.setText(getString(R.string.about_) + "\n\nMIT License\n" +
                    "\n" +
                    "Copyright (c) 2021-2025 Arman Jussupgaliyev\n" +
                    "\n" +
                    "Permission is hereby granted, free of charge, to any person obtaining a copy\n" +
                    "of this software and associated documentation files (the \"Software\"), to deal\n" +
                    "in the Software without restriction, including without limitation the rights\n" +
                    "to use, copy, modify, merge, publish, distribute, sublicense, and/or sell\n" +
                    "copies of the Software, and to permit persons to whom the Software is\n" +
                    "furnished to do so, subject to the following conditions:\n" +
                    "\n" +
                    "The above copyright notice and this permission notice shall be included in all\n" +
                    "copies or substantial portions of the Software.\n" +
                    "\n" +
                    "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR\n" +
                    "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,\n" +
                    "FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE\n" +
                    "AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER\n" +
                    "LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,\n" +
                    "OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE\n" +
                    "SOFTWARE.");
            layout.addView(text);

            ScrollView scrollView = new ScrollView(this);
            scrollView.addView(layout);

            new android.app.AlertDialog.Builder(this)
                    .setTitle(R.string.app_name)
                    .setView(scrollView)
                    .setNeutralButton(android.R.string.ok, null).show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private class SearchTask extends AsyncTask<String, Void, SearchResult> {
        @Override
        protected SearchResult doInBackground(String... params) {
            SearchResult result = new SearchResult();
            try {
                if (metadata != null) {
                    result.videos = metadata.search(params[0]);
                } else {
                    result.error = new Exception("Metadata client not initialized.");
                }
            } catch (Exception e) {
                result.error = e;
            }
            return result;
        }

        @Override
        protected void onPostExecute(SearchResult result) {
            if (isDestroyedFlag) return; // Discard safely if activity was killed during rotation

            View loadingView = findViewById(R.id.loading);
            if (loadingView != null) loadingView.setVisibility(View.GONE);

            if (result.error != null) {
                Toast.makeText(MainActivity.this, "Search failed: " + result.error.getMessage(), Toast.LENGTH_LONG).show();
            } else if (result.videos != null) {
                videos = result.videos;
                adapter = new VideoAdapter(MainActivity.this, R.layout.video_item, videos);
                if (listView != null) listView.setAdapter(adapter);
            }
        }
    }

    private class SearchResult {
        List<VideoInfo> videos;
        Exception error;
    }

    private class TrendingTask extends AsyncTask<Void, Void, TrendingResult> {
        @Override
        protected TrendingResult doInBackground(Void... params) {
            TrendingResult result = new TrendingResult();
            if (trending != null) {
                try {
                    result.videos = trending.getTrendingVideos();
                } catch (Exception e) {
                    result.error = e;
                }
            } else {
                result.error = new Exception("Trending unavailable");
            }
            return result;
        }

        @Override
        protected void onPostExecute(TrendingResult result) {
            if (isDestroyedFlag) return; // Discard safely if activity was killed during rotation

            View loadingView = findViewById(R.id.loading);
            if (loadingView != null) loadingView.setVisibility(View.GONE);

            View noTrendingView = findViewById(R.id.no_trending);
            if (result.error != null) {
                if (noTrendingView != null) noTrendingView.setVisibility(View.VISIBLE);
                Toast.makeText(context, result.error.getMessage(), Toast.LENGTH_LONG).show();
            } else if (trending == null || result.videos == null || result.videos.size() == 0) {
                if (noTrendingView != null) noTrendingView.setVisibility(View.VISIBLE);
            } else {
                if (noTrendingView != null) noTrendingView.setVisibility(View.GONE);
                videos = result.videos;
                adapter = new VideoAdapter(context, R.layout.video_item, videos);
                if (listView != null) listView.setAdapter(adapter);
            }
        }
    }

    private class TrendingResult {
        List<VideoInfo> videos;
        Exception error;
    }
}