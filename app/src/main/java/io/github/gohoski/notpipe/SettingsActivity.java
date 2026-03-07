package io.github.gohoski.notpipe;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Toast;

import io.github.gohoski.notpipe.api.Manager;
import io.github.gohoski.notpipe.config.Config;
import io.github.gohoski.notpipe.config.ConfigManager;
import io.github.gohoski.notpipe.ui.FixedApiRow;
import io.github.gohoski.notpipe.ui.InstanceSection;
import io.github.gohoski.notpipe.util.InstancesUpdater;

/**
 * Settings activity for managing API instances and app settings.
 * API instances are added programmatically, general settings are in the layout.
 */
public class SettingsActivity extends Activity implements InstancesUpdater.OnInstancesUpdatedListener {
    
    private LinearLayout apiInstancesContainer;
    private CheckBox updateInstancesChk, streamPlaybackChk, convertVideosChk;
    private LinearLayout updateInstancesLayout, convertLayout;
    private EditText instancesUrlEdit;
    private Spinner updateFreqSpinner, playerSpinner, codecSpinner;
    private Spinner qualitySpinner;
    private FixedApiRow s60TubeRow;
    private InstanceSection invidiousSection;
    private InstanceSection ytApiLegacySection;
    private InstanceSection yt2009Section;
    
    private ConfigManager configManager;
    private Config config;
    private Context context;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        
        context = this;
        configManager = ConfigManager.getInstance();
        config = configManager.getConfig();

        apiInstancesContainer = (LinearLayout) findViewById(R.id.apiInstancesContainer);
        updateInstancesChk = (CheckBox) findViewById(R.id.update_chk);
        updateInstancesLayout = (LinearLayout) findViewById(R.id.update_instances);
        instancesUrlEdit = (EditText) findViewById(R.id.instances_url);
        updateFreqSpinner = (Spinner) findViewById(R.id.update_spinner);
        playerSpinner = (Spinner) findViewById(R.id.player_spinner);
        streamPlaybackChk = (CheckBox) findViewById(R.id.stream_playback_chk);
        qualitySpinner = (Spinner) findViewById(R.id.quality_spinner);
        codecSpinner = (Spinner) findViewById(R.id.codec_spinner);
        convertVideosChk = (CheckBox) findViewById(R.id.convert_chk);
        convertLayout = (LinearLayout) findViewById(R.id.codec_layout);

        updateInstancesChk.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                updateInstancesLayout.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            }
        });
        convertVideosChk.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    convertLayout.setVisibility(View.VISIBLE);
                    setSpinnerSelection(qualitySpinner, "360p");
                    streamPlaybackChk.setChecked(false);
                    qualitySpinner.setEnabled(false);
                    streamPlaybackChk.setEnabled(false);
                } else {
                    convertLayout.setVisibility(View.GONE);
                    qualitySpinner.setEnabled(true);
                    streamPlaybackChk.setEnabled(true);
                }
            }
        });

        updateInstancesChk.setChecked(config.isUpdateInstancesFromUrl());
        updateInstancesLayout.setVisibility(config.isUpdateInstancesFromUrl() ? View.VISIBLE : View.GONE);
        instancesUrlEdit.setText(config.getInstancesUpdateUrl());
        updateFreqSpinner.setSelection(config.getUpdateFrequency());
        playerSpinner.setSelection(config.isUseExternalPlayer() ? 1 : 0);
        streamPlaybackChk.setChecked(config.isStreamPlayback());
        setSpinnerSelection(qualitySpinner, config.getPreferredQuality() + "p");
        convertVideosChk.setChecked(config.isConvertVideos());
        codecSpinner.setSelection(config.getConvertCodec());

        if (config.isConvertVideos()) {
            qualitySpinner.setEnabled(false);
            streamPlaybackChk.setEnabled(false);
        }

        invidiousSection = new InstanceSection(context, "Invidious", config.getInvidiousInstances());
        ytApiLegacySection = new InstanceSection(context, "YtAPILegacy", config.getYtApiLegacyInstances());
        yt2009Section = new InstanceSection(context, "yt2009", config.getYt2009Instances());
        s60TubeRow = new FixedApiRow(context, "S60Tube", config.isS60TubeEnabled());

        apiInstancesContainer.addView(ytApiLegacySection);
        apiInstancesContainer.addView(invidiousSection);
        apiInstancesContainer.addView(yt2009Section);
        apiInstancesContainer.addView(s60TubeRow);

        findViewById(R.id.cancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(context, MainActivity.class));
                finish();
            }
        });
        findViewById(R.id.ok).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveSettings();
                startActivity(new Intent(context, MainActivity.class));
                finish();
            }
        });
        findViewById(R.id.update_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveSettings();
                new InstancesUpdater(context, SettingsActivity.this).updateInstances();
            }
        });
    }
    
    private void saveSettings() {
        config.setS60TubeEnabled(s60TubeRow.isEnabled());
        config.setUpdateInstancesFromUrl(updateInstancesChk.isChecked());
        config.setInstancesUpdateUrl(instancesUrlEdit.getText().toString().trim());
        config.setUpdateFrequency(updateFreqSpinner.getSelectedItemPosition());
        config.setUseExternalPlayer(playerSpinner.getSelectedItemPosition() == 1);
        config.setStreamPlayback(streamPlaybackChk.isChecked());
        config.setPreferredQuality(((String) qualitySpinner.getSelectedItem()).replace("p", ""));
        config.setConvertVideos(convertVideosChk.isChecked());
        config.setConvertCodec(codecSpinner.getSelectedItemPosition());
        
        config.setInvidiousInstances(invidiousSection.getInstances());
        config.setYtApiLegacyInstances(ytApiLegacySection.getInstances());
        config.setYt2009Instances(yt2009Section.getInstances());
        
        configManager.saveConfig(config);
        Manager.getInstance().reloadInstances();
    }
    
    private void setSpinnerSelection(Spinner spinner, String value) {
        for (int i = 0; i < spinner.getCount(); i++) {
            if (spinner.getItemAtPosition(i).toString().equals(value)) {
                spinner.setSelection(i);
                return;
            }
        }
    }

    @Override
    public void onInstancesUpdated() {
        // Get fresh config from ConfigManager since it was updated
        config = configManager.getConfig();
        // Refresh the UI sections with the new instances from config
        invidiousSection.setInstances(config.getInvidiousInstances());
        ytApiLegacySection.setInstances(config.getYtApiLegacyInstances());
        yt2009Section.setInstances(config.getYt2009Instances());
        Toast.makeText(this, "Instances updated successfully", Toast.LENGTH_SHORT).show();
    }
}
