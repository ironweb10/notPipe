package io.github.gohoski.notpipe.ui;

import android.content.Context;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.gohoski.notpipe.R;

/**
 * Component for fixed URL APIs (like S60Tube) that only need enable/disable.
 */
public class FixedApiRow extends LinearLayout {
    
    private CheckBox checkbox;
    
    public FixedApiRow(Context context, String apiName, boolean isEnabled) {
        super(context);
        
        inflate(context, R.layout.fixed_api_row, this);
        
        TextView titleView = (TextView) findViewById(R.id.fixedApiTitle);
        checkbox = (CheckBox) findViewById(R.id.fixedApiCheckbox);
        
        titleView.setText(apiName);
        checkbox.setChecked(isEnabled);
    }
    
    public boolean isEnabled() {
        return checkbox.isChecked();
    }
}
