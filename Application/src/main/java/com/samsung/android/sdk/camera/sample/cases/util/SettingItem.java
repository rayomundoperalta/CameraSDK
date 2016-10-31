package com.samsung.android.sdk.camera.sample.cases.util;

import android.app.Activity;
import android.view.View;

/**
 * Abstract Class for SettingItem
 */
public abstract class SettingItem<K, V> {
    public static final int SETTING_TYPE_REQUEST_KEY = -1;
    public static final int SETTING_TYPE_CAMERA_FACING = 0;
    public static final int SETTING_TYPE_IMAGE_FORMAT = 1;
    public static final int SETTING_TYPE_JPEG_SIZE = 2;

    protected final Activity mActivity;
    protected final String mName;
    protected final K mKey;

    protected OnSettingItemValueChangedListener mListener;

    protected View mView;

    public SettingItem(Activity activity, String name, K key) {
        mActivity = activity;
        mName = name;
        mKey = key;
    }

    /**
     * Returns key for this setting item.
     */
    public K getKey() {
        return mKey;
    }

    /**
     * Returns current setting value for this setting item.
     */
    public abstract V getValue();

    /**
     * Sets value for this setting item.
     */
    public abstract void setValue(V value);

    /**
     * Returns view for this setting item.
     */
    public View getView() {
        return mView;
    }

    /**
     * Returns whether this setting item is enabled.
     */
    public abstract boolean isEnabled();

    /**
     * Enables/Disables this setting item.
     */
    public abstract void setEnabled(boolean enable);

    /**
     * Set initial setting value for this setting item.
     */
    @SuppressWarnings("unchecked")
    public void setInitialValue(Object value) {
        if (value != null) setValue((V) value);
        if (mListener != null) mListener.onSettingItemValueChanged(mKey, getValue());
    }

    /**
     * Set {@link SettingItem.OnSettingItemValueChangedListener} for this setting item.
     */
    public void setOnSettingItemValueChangedListener(OnSettingItemValueChangedListener listener) {
        mListener = listener;
    }

    public interface OnSettingItemValueChangedListener {
        public <K_Listener, V_Listener> void onSettingItemValueChanged(K_Listener key, V_Listener value);
    }
}
