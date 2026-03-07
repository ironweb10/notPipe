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

import java.io.IOException;
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
    private VideoAdapter adapter;
    private List<VideoInfo> videos;
    private AutoCompleteTextView searchQuery;
    private ListView listView;
    private Context context;
    private Trending trending = null;
    private Metadata metadata;
    private AutoCompleteAdapter autoCompleteAdapter;
    private Config config = ConfigManager.getInstance().getConfig();

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
        metadata = Manager.getInstance().getMetadata();

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
                searchQuery.showDropDown();
            }
        });
        searchQuery.setAdapter(autoCompleteAdapter);
        searchQuery.setThreshold(3);
        searchQuery.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                autoCompleteAdapter.setSearchActive(false);
                autoCompleteAdapter.requestSuggestions(s.toString());
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
                    noTrending.setVisibility(View.GONE);
                    loading.setVisibility(View.VISIBLE);
                    hideKeyboard();
                    new SearchTask().execute(query);
                }
            }
        });

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
            if (Math.round((double) ((now.getTimeInMillis() - lastUpdate.getTimeInMillis()) / 24 * 60 * 60 * 1000)) >= config.getUpdateFrequency()) {
                isUpdatingInstances = true;
                new InstancesUpdater(this, this).updateInstances();
            }
        }

        if (!isUpdatingInstances)
            new TrendingTask().execute();
    }

    @Override
    public void onInstancesUpdated() {
        new TrendingTask().execute();
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
    public void onLowMemory() {
        super.onLowMemory();
        ImageLoader.clearCache();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ImageLoader.clearCache();
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
                result.videos = metadata.search(params[0]);
            } catch (IOException e) {
                result.error = e;
            }
            return result;
        }

        @Override
        protected void onPostExecute(SearchResult result) {
            findViewById(R.id.loading).setVisibility(View.GONE);
            if (result.error != null) {
                Toast.makeText(MainActivity.this, "Search failed: " + result.error.getMessage(), Toast.LENGTH_LONG).show();
            } else if (result.videos != null) {
                videos = result.videos;
                adapter = new VideoAdapter(MainActivity.this, R.layout.video_item, videos);
                listView.setAdapter(adapter);
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
            }
            return result;
        }

        @Override
        protected void onPostExecute(TrendingResult result) {
            findViewById(R.id.loading).setVisibility(View.GONE);
            if (result.error != null) {
                findViewById(R.id.no_trending).setVisibility(View.VISIBLE);
                Toast.makeText(context, result.error.getMessage(), Toast.LENGTH_LONG).show();
            } else if (trending == null || result.videos.size() == 0) {
                findViewById(R.id.no_trending).setVisibility(View.VISIBLE);
            } else {
                videos = result.videos;
                adapter = new VideoAdapter(context, R.layout.video_item, videos);
                listView.setAdapter(adapter);
            }
        }
    }

    private class TrendingResult {
        List<VideoInfo> videos;
        Exception error;
    }
}