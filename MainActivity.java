package com.rich.smarthdd;

import android.app.Activity;
import android.app.PendingIntent;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.content.res.ColorStateList;
import android.hardware.usb.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

public class MainActivity extends Activity {

    // Keep USB payload transfers below Android/device-specific bulkTransfer limits.
    // Each chunk is sector-aligned for the 512-byte USB mass-storage path.
    private static final int MAX_BULK_DATA_CHUNK_BYTES = 64 * 1024;

    private static final String ACTION_USB_PERMISSION =
            "com.rich.smarthdd.USB_PERMISSION";

    private UsbManager usbManager;
    private UsbDevice usbDevice;
    private UsbDeviceConnection connection;
    private UsbInterface botInterface;
    private UsbEndpoint bulkIn;
    private UsbEndpoint bulkOut;
    private TextView output;
    private ScrollView outputScroll;
    private Button surfaceCancelButton;
    private ProgressBar surfaceProgressBar;
    private TextView surfaceProgressText;
    private TextView surfaceDeviceInfoText;
    private Button surfaceInfoStartButton;
    private boolean showingHome = true;
    private volatile boolean surfaceInfoScreenVisible = false;
    private volatile boolean surfaceInfoProbeRunning = false;
    private volatile boolean surfaceTestRunning = false;
    // Only the separate fake-card edition uses these write-test state values.
    private volatile boolean fakeCardTestRunning = false;
    private volatile boolean fakeCardCancelRequested = false;
    private long lastFakeProgressPostMs = 0;
    private volatile boolean fakeCardInfoScreenVisible = false;
    private volatile boolean fakeCardProbeRunning = false;
    private TextView fakeCardDeviceInfoText;
    private Button fakeCardStartButton;
    private ProgressBar fakeCardProgressBar;
    private TextView fakeCardProgressText;
    private Button fakeCardCancelButton;
    private FakeCardTarget fakeCardTarget;
    private volatile boolean botTransportFailed = false;
    private volatile String botTransportFailure = null;
    private boolean keepScreenAwake = false;
    private volatile boolean surfaceTestCancelRequested = false;
    private int commandTag = 0x10000000;
    private volatile boolean usbScanRunning = false;
    private volatile boolean smartReadRunning = false;
    private volatile boolean smartReadCancelRequested = false;
    private volatile boolean usbScanCancelRequested = false;
    private Runnable pendingUsbAction;
    private boolean usbPermissionReceiverRegistered = false;
    private final Map<Button, int[]> originalButtonPadding = new WeakHashMap<>();

    @Override
    protected void attachBaseContext(Context base) {
        String language = base.getSharedPreferences("smart_hdd_settings", Context.MODE_PRIVATE)
                .getString("app_language", "system");
        if ("en".equals(language)) {
            android.content.res.Configuration configuration =
                    new android.content.res.Configuration(base.getResources().getConfiguration());
            configuration.setLocale(Locale.ENGLISH);
            base = base.createConfigurationContext(configuration);
        }
        super.attachBaseContext(base);
    }

    private float phoneScaleFactor() {
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        float widthDp = metrics.widthPixels / metrics.density;
        float heightDp = availableScreenHeightDp(metrics);
        float shortSideDp = Math.min(widthDp, heightDp);
        if (shortSideDp >= 600f) return 1f;

        float widthScale = shortSideDp < 340f ? 0.82f
                : shortSideDp < 380f ? 0.88f
                : shortSideDp < 430f ? 0.94f : 1f;
        float heightScale = heightDp < 600f ? 0.76f
                : heightDp < 650f ? 0.82f
                : heightDp < 700f ? 0.88f
                : heightDp < 760f ? 0.94f : 1f;
        return Math.max(0.74f, widthScale * heightScale);
    }

    private float availableScreenHeightDp(android.util.DisplayMetrics displayMetrics) {
        float density = displayMetrics.density;
        int topInset;
        int bottomInset;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowMetrics metrics = getWindowManager().getCurrentWindowMetrics();
            android.graphics.Insets safeInsets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            topInset = safeInsets.top;
            bottomInset = safeInsets.bottom;
            if (topInset == 0) topInset = systemBarDimension("status_bar_height");
            bottomInset = Math.max(bottomInset, systemBarDimension("navigation_bar_height"));
            return Math.max(1f, (metrics.getBounds().height() - topInset - bottomInset) / density);
        }

        android.util.DisplayMetrics realMetrics = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(realMetrics);
        topInset = systemBarDimension("status_bar_height");
        bottomInset = systemBarDimension("navigation_bar_height");
        return Math.max(1f, (realMetrics.heightPixels - topInset - bottomInset) / density);
    }

    private int systemBarDimension(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id == 0 ? 0 : getResources().getDimensionPixelSize(id);
    }

    private float preferredTextScale() {
        int percent = getSharedPreferences("smart_hdd_settings", MODE_PRIVATE)
                .getInt("app_text_size_percent", 100);
        return Math.max(0.8f, Math.min(1.2f, percent / 100f));
    }

    private float preferredButtonScale() {
        int percent = getSharedPreferences("smart_hdd_settings", MODE_PRIVATE)
                .getInt("app_button_size_percent", 100);
        return Math.max(0.85f, Math.min(1f, percent / 100f));
    }

    private void setResponsiveTextSize(TextView view, float sizeSp) {
        float scaled = sizeSp * phoneScaleFactor() * preferredTextScale();
        float minimum = sizeSp <= 12f ? 9f : 11f;
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Math.max(minimum, scaled));
    }

    private void setResponsivePadding(View view, int left, int top, int right, int bottom) {
        float scale = phoneScaleFactor();
        view.setPadding(Math.round(left * scale), Math.round(top * scale),
                Math.round(right * scale), Math.round(bottom * scale));
    }

    private String getSelectedLanguageLabel() {
        boolean english = "en".equals(getSharedPreferences("smart_hdd_settings", MODE_PRIVATE)
                .getString("app_language", "system"));
        return getString(english ? R.string.language_english : R.string.language_system_default);
    }

    private void showLanguagePicker() {
        String current = getSharedPreferences("smart_hdd_settings", MODE_PRIVATE)
                .getString("app_language", "system");
        int checked = "en".equals(current) ? 1 : 0;
        String[] choices = { getString(R.string.language_system_default),
                getString(R.string.language_english) };
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.language_picker_title)
                .setSingleChoiceItems(choices, checked, (dialog, which) -> {
                    String language = which == 1 ? "en" : "system";
                    getSharedPreferences("smart_hdd_settings", MODE_PRIVATE).edit()
                            .putString("app_language", language).apply();
                    dialog.dismiss();
                    recreate();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showAboutSupport() {
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setMessage(R.string.about_message)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.buy_me_a_coffee, (dialog, which) -> openBuyMeACoffee())
                .setNegativeButton(R.string.project_license, (dialog, which) -> showLicenseSummary())
                .show();
    }

    private void openBuyMeACoffee() {
        String page = getString(R.string.buy_me_a_coffee_page_url).trim();
        if (!page.isEmpty()) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(page));
                startActivity(intent);
            } catch (android.content.ActivityNotFoundException noBrowser) {
                showCoffeeLinkUnavailable(page);
            } catch (Exception error) {
                showCoffeeLinkUnavailable(page);
            }
            return;
        }
        showCoffeeSetupInfo();
    }

    private void showCoffeeLinkUnavailable(String page) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.coffee_link_unavailable_title)
                .setMessage(getString(R.string.coffee_link_unavailable_message, page))
                .setPositiveButton(R.string.copy_link, (dialog, which) -> {
                    android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Buy Me a Coffee", page));
                        android.widget.Toast.makeText(this, R.string.coffee_link_copied, android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.ok, null)
                .show();
    }

    private void showCoffeeSetupInfo() {
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.coffee_page_not_ready_title)
                .setMessage(R.string.coffee_page_not_ready_message)
                .setPositiveButton(R.string.coffee_setup_link, (dialog, which) -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://help.buymeacoffee.com/en/articles/10184401-how-to-set-up-your-buy-me-a-coffee-page"));
                    try { startActivity(intent); } catch (Exception ignored) { }
                })
                .setNegativeButton(android.R.string.ok, null)
                .show();
    }

    private void showLicenseSummary() {
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.project_license)
                .setMessage(R.string.project_license_summary)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        showHome();
    }

    private int selectedTheme() {
        android.content.SharedPreferences preferences =
                getSharedPreferences("smart_hdd_settings", MODE_PRIVATE);
        if (preferences.contains("appearance_v2")) {
            return preferences.getInt("appearance_v2", 0);
        }
        // Preserve an earlier Standard/Retro choice when upgrading to four styles.
        int previousChoice = preferences.getInt("appearance", 0);
        int migratedChoice = previousChoice == 1 ? 2 : 0;
        preferences.edit().putInt("appearance_v2", migratedChoice).apply();
        return migratedChoice;
    }

    private String surfaceRatePreferenceKey(InquiryResult inquiry) {
        String identity = (safe(inquiry.vendor) + "_" + safe(inquiry.product))
                .toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "_");
        return "surface_rate_" + identity;
    }

    private boolean isDarkTheme() {
        int theme = selectedTheme();
        return theme == 1 || theme == 2;
    }

    private boolean isRetroTheme() {
        return selectedTheme() == 2 || selectedTheme() == 3;
    }

    private int themeBackground() {
        if (isRetroTheme()) {
            return isDarkTheme() ? Color.rgb(7, 11, 28) : Color.rgb(243, 249, 255);
        }
        return isDarkTheme() ? Color.rgb(18, 26, 36) : Color.rgb(244, 247, 250);
    }

    private int themePrimaryText() {
        if (!isRetroTheme()) {
            return isDarkTheme() ? Color.rgb(236, 242, 248) : Color.rgb(24, 34, 46);
        }
        return isDarkTheme() ? Color.rgb(70, 240, 255) : Color.rgb(0, 119, 150);
    }

    private int themeSecondaryText() {
        if (!isRetroTheme()) {
            return isDarkTheme() ? Color.rgb(174, 188, 202) : Color.rgb(83, 96, 110);
        }
        return isDarkTheme() ? Color.rgb(255, 79, 216) : Color.rgb(190, 20, 130);
    }

    private int themeAccent() {
        if (isRetroTheme()) {
            return isDarkTheme() ? Color.rgb(0, 217, 255) : Color.rgb(0, 127, 168);
        }
        return isDarkTheme() ? Color.rgb(84, 190, 255) : Color.rgb(21, 101, 192);
    }

    private int themeButtonFill() {
        if (isRetroTheme()) {
            return isDarkTheme() ? Color.rgb(17, 24, 55) : Color.rgb(220, 244, 255);
        }
        return isDarkTheme() ? Color.rgb(39, 54, 70) : Color.rgb(224, 234, 244);
    }

    private int themeButtonText() {
        if (!isRetroTheme()) return themePrimaryText();
        return isDarkTheme() ? Color.rgb(255, 73, 112) : Color.rgb(205, 22, 83);
    }

    private int themePanel() {
        if (isRetroTheme()) {
            return isDarkTheme() ? Color.rgb(12, 19, 45) : Color.rgb(232, 246, 255);
        }
        return isDarkTheme() ? Color.rgb(26, 38, 51) : Color.WHITE;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void addAccentRule(LinearLayout layout) {
        View rule = new View(this);
        rule.setBackgroundColor(themeAccent());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(2));
        params.setMargins(0, dp(4), 0, dp(10));
        layout.addView(rule, params);
    }

    private void applyCurrentTheme(LinearLayout root) {
        int theme = selectedTheme();
        int background = themeBackground();
        root.setBackgroundColor(background);
        styleThemeViews(root);
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        getWindow().getDecorView().setSystemUiVisibility(
                !isDarkTheme() ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : 0);
    }

    private ScrollView setScrollablePage(LinearLayout content) {
        ScrollView pageScroll = new ScrollView(this);
        pageScroll.setFillViewport(true);
        pageScroll.setClipToPadding(true);
        pageScroll.setVerticalScrollBarEnabled(true);
        pageScroll.setBackgroundColor(themeBackground());
        pageScroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        setContentView(pageScroll);
        applyCurrentTheme(content);
        applySystemBarInsets(pageScroll);
        return pageScroll;
    }

    private void applySystemBarInsets(ScrollView pageScroll) {
        pageScroll.setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
                android.graphics.Insets navigation = insets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.navigationBars());
                bottom = Math.max(bottom, navigation.bottom);
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            int navigationBarHeightId = getResources().getIdentifier(
                    "navigation_bar_height", "dimen", "android");
            if (navigationBarHeightId != 0) {
                int navigationBarHeight = getResources().getDimensionPixelSize(navigationBarHeightId);
                bottom = Math.max(bottom, navigationBarHeight + dp(8));
            }
            view.setPadding(left, top, right, bottom);
            return insets;
        });
        pageScroll.requestApplyInsets();
    }

    private void styleThemeViews(View view) {
        if (view instanceof SeekBar) {
            SeekBar bar = (SeekBar) view;
            ColorStateList tint = ColorStateList.valueOf(themeAccent());
            bar.setProgressTintList(tint);
            bar.setThumbTintList(tint);
        } else if (view instanceof ProgressBar) {
            ProgressBar bar = (ProgressBar) view;
            ColorStateList tint = ColorStateList.valueOf(themeAccent());
            bar.setProgressTintList(tint);
            bar.setIndeterminateTintList(tint);
        } else if (view instanceof Button) {
            Button button = (Button) view;
            button.setTextColor(themeButtonText());
            if (isRetroTheme()) {
                GradientDrawable retroButton = new GradientDrawable();
                retroButton.setColor(themeButtonFill());
                retroButton.setCornerRadius(dp(8));
                retroButton.setStroke(dp(1), themeAccent());
                button.setBackground(retroButton);
            } else {
                button.setBackgroundResource(android.R.drawable.btn_default);
                button.setBackgroundTintList(ColorStateList.valueOf(themeButtonFill()));
            }
            int[] original = originalButtonPadding.get(button);
            if (original == null) {
                original = new int[]{button.getPaddingLeft(), button.getPaddingTop(),
                        button.getPaddingRight(), button.getPaddingBottom()};
                originalButtonPadding.put(button, original);
            }
            float buttonScale = preferredButtonScale();
            button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                    Math.max(11f, 14f * phoneScaleFactor() * preferredTextScale() * buttonScale));
            button.setMinimumHeight(Math.round(dp(48) * buttonScale));
            button.setPadding(original[0], Math.round(original[1] * buttonScale),
                    original[2], Math.round(original[3] * buttonScale));
        } else if (view instanceof CompoundButton) {
            CompoundButton button = (CompoundButton) view;
            button.setTextColor(themePrimaryText());
            button.setButtonTintList(ColorStateList.valueOf(themeAccent()));
        } else if (view instanceof TextView) {
            TextView text = (TextView) view;
            Object saved = text.getTag();
            int original = saved instanceof Integer
                    ? (Integer) saved : text.getCurrentTextColor();
            if (!(saved instanceof Integer)) text.setTag(original);
            if (original == Color.rgb(0, 100, 0)) {
                text.setTextColor(original);
            } else if (original == Color.DKGRAY || original == Color.GRAY) {
                text.setTextColor(themeSecondaryText());
            } else {
                text.setTextColor(themePrimaryText());
            }
        }
        if (view == output) view.setBackgroundColor(themePanel());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                styleThemeViews(group.getChildAt(i));
            }
        }
    }

    private void showHome() {
        showingHome = true;
        surfaceInfoScreenVisible = false;
        fakeCardInfoScreenVisible = false;
        surfaceDeviceInfoText = null;
        surfaceInfoStartButton = null;
        surfaceProgressBar = null;
        surfaceProgressText = null;
        surfaceCancelButton = null;
        fakeCardCancelButton = null;
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        setResponsivePadding(layout, 24, 24, 24, 24);

        ImageView thumbnail = new ImageView(this);
        thumbnail.setImageResource(R.drawable.smart_hdd_brand);
        thumbnail.setContentDescription(getString(R.string.brand_icon_description));
        int thumbnailSize = phoneScaleFactor() < 1f ? dp(48) : 82;
        LinearLayout.LayoutParams thumbnailParams = new LinearLayout.LayoutParams(thumbnailSize, thumbnailSize);
        thumbnailParams.gravity = Gravity.CENTER_HORIZONTAL;
        thumbnail.setLayoutParams(thumbnailParams);

        TextView title = new TextView(this);
        title.setText("SMART HDD TESTER");
        setResponsiveTextSize(title, 30f);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER);
        setResponsivePadding(title, 0, 20, 0, 8);

        TextView subtitle = new TextView(this);
        subtitle.setText("Rich's Retro HDD Smart Tool");
        setResponsiveTextSize(subtitle, 16f);
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setGravity(Gravity.CENTER);
        setResponsivePadding(subtitle, 0, 0, 0, 24);

        TextView safety = new TextView(this);
        safety.setText("OFFLINE STORAGE DIAGNOSTICS\nSMART and surface checks are read-only");
        setResponsiveTextSize(safety, 14f);
        safety.setTextColor(Color.rgb(0, 100, 0));
        safety.setGravity(Gravity.CENTER);
        setResponsivePadding(safety, 0, 0, 0, 20);

        Button smartButton = new Button(this);
        smartButton.setText("STORAGE HEALTH & SMART");
        Button surfaceButton = new Button(this);
        surfaceButton.setText("READ-ONLY SURFACE TEST");
        Button mediaButton = new Button(this);
        mediaButton.setText("SD CARD & INTERNAL NAND");
        Button settingsButton = new Button(this);
        settingsButton.setText("SETTINGS / THEMES");
        Button homeDisconnectButton = new Button(this);
        homeDisconnectButton.setText("SAFE DISCONNECT USB");
        Button aboutButton = new Button(this);
        aboutButton.setText("ABOUT / SUPPORT");

        TextView footer = new TextView(this);
        footer.setText("Smart HDD v0.1\n100% offline • Diagnostics are read-only");
        setResponsiveTextSize(footer, 12f);
        footer.setTextColor(Color.GRAY);
        footer.setGravity(Gravity.CENTER);
        setResponsivePadding(footer, 0, 28, 0, 0);

        layout.addView(thumbnail);
        layout.addView(title);
        addAccentRule(layout);
        layout.addView(subtitle);
        layout.addView(safety);
        layout.addView(smartButton);
        layout.addView(surfaceButton);
        layout.addView(mediaButton);
        layout.addView(settingsButton);
        layout.addView(homeDisconnectButton);
        layout.addView(aboutButton);
        layout.addView(footer);
        TextView fakeHeading = new TextView(this);
        fakeHeading.setText("FAKE CARD DETECTOR");
        setResponsiveTextSize(fakeHeading, 25f);
        fakeHeading.setTypeface(null, android.graphics.Typeface.BOLD);
        fakeHeading.setTextColor(Color.rgb(220, 35, 55));
        fakeHeading.setGravity(Gravity.CENTER);
        setResponsivePadding(fakeHeading, 0, 30, 0, 8);
        TextView fakeWarning = new TextView(this);
        fakeWarning.setText(R.string.home_fake_card_warning);
        setResponsiveTextSize(fakeWarning, 15f);
        fakeWarning.setTextColor(Color.rgb(180, 20, 35));
        fakeWarning.setGravity(Gravity.CENTER);
        setResponsivePadding(fakeWarning, 8, 0, 8, 12);
        Button fakeButton = new Button(this);
        fakeButton.setText("OPEN DESTRUCTIVE USB CARD TEST");
        layout.addView(fakeHeading);
        layout.addView(fakeWarning);
        layout.addView(fakeButton);
        ScrollView homeScroll = new ScrollView(this);
        homeScroll.addView(layout);
        homeScroll.setFillViewport(true);
        homeScroll.setBackgroundColor(themeBackground());
        setContentView(homeScroll);
        applyCurrentTheme(layout);
        applySystemBarInsets(homeScroll);

        smartButton.setOnClickListener(v -> showSmartScreen());
        surfaceButton.setOnClickListener(v -> showSurfaceTestInfo());
        mediaButton.setOnClickListener(v -> showMediaDiagnostics());
        settingsButton.setOnClickListener(v -> showSettings());
        homeDisconnectButton.setOnClickListener(v -> disconnectUsbSafely());
        aboutButton.setOnClickListener(v -> showAboutSupport());
        fakeButton.setOnClickListener(v -> showFakeCardInfo());
    }

    private void showFakeCardInfo() {
        if (askBeforeInterruptingUsb(this::showFakeCardInfo)) return;
        showingHome = false;
        surfaceInfoScreenVisible = false;
        fakeCardInfoScreenVisible = true;
        fakeCardTarget = null;

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        setResponsivePadding(layout, 20, 20, 20, 20);
        Button back = new Button(this);
        back.setText("BACK");
        TextView title = new TextView(this);
        title.setText("FAKE CARD DETECTOR");
        setResponsiveTextSize(title, 24f);
        title.setTextColor(Color.rgb(220, 35, 55));
        title.setGravity(Gravity.CENTER);
        TextView explanation = new TextView(this);
        explanation.setText(R.string.fake_card_info_explanation);
        setResponsiveTextSize(explanation, 14f);
        setResponsivePadding(explanation, 0, 14, 0, 14);
        explanation.append("\n\n");
        explanation.append(getString(R.string.fake_card_screen_note));
        fakeCardDeviceInfoText = new TextView(this);
        setResponsiveTextSize(fakeCardDeviceInfoText, 14f);
        fakeCardDeviceInfoText.setText("Checking USB reader…");
        setResponsivePadding(fakeCardDeviceInfoText, 0, 8, 0, 18);
        Button refresh = new Button(this);
        refresh.setText("CHECK USB CARD AGAIN");
        fakeCardStartButton = new Button(this);
        fakeCardStartButton.setText("CONTINUE TO WARNINGS");
        fakeCardStartButton.setEnabled(false);
        layout.addView(back);
        layout.addView(title);
        addAccentRule(layout);
        layout.addView(explanation);
        layout.addView(fakeCardDeviceInfoText);
        layout.addView(refresh);
        layout.addView(fakeCardStartButton);
        setScrollablePage(layout);
        back.setOnClickListener(v -> showHome());
        refresh.setOnClickListener(v -> probeFakeCardDevice());
        fakeCardStartButton.setOnClickListener(v -> chooseFakeCardMode());
        probeFakeCardDevice();
    }

    private void probeFakeCardDevice() {
        if (!fakeCardInfoScreenVisible || fakeCardProbeRunning) return;
        fakeCardProbeRunning = true;
        fakeCardTarget = null;
        runOnUiThread(() -> {
            if (fakeCardDeviceInfoText != null) fakeCardDeviceInfoText.setText("Checking USB reader and card…");
            if (fakeCardStartButton != null) fakeCardStartButton.setEnabled(false);
        });
        new Thread(() -> {
            String details;
            FakeCardTarget target = null;
            try {
                usbDevice = findMassStorageDevice();
                if (usbDevice == null) {
                    details = "USB CARD STATUS\nNo USB mass-storage device found. Connect a card reader by USB/OTG. The built-in SD slot and internal NAND are not accessible to this tool.";
                } else if (!usbManager.hasPermission(usbDevice)) {
                    details = "USB CARD STATUS\nUSB permission is needed. Allow the prompt, then check again.";
                    requestUsbPermission(usbDevice);
                } else if (!openBot()) {
                    details = "USB CARD STATUS\nCould not open the USB mass-storage device. Check the reader and connection.";
                } else {
                    InquiryResult inquiry = performInquiry();
                    CapacityResult capacity = performReadCapacity();
                    if (inquiry == null || capacity == null || capacity.blockSize != 512 ||
                            capacity.lastLba <= 0 || capacity.lastLba > 0xFFFFFFFFL) {
                        details = "USB CARD STATUS\nCould not read a supported 512-byte-sector USB card. No data was written. This test requires USB mass storage with 512-byte sectors and a capacity addressable by WRITE(10).";
                    } else if (!inquiry.removable) {
                        details = "USB CARD STATUS\nThis USB device does not identify itself as removable media, so the destructive test is blocked. No data was written.";
                    } else {
                        target = new FakeCardTarget();
                        target.vendor = safe(inquiry.vendor).trim();
                        target.product = safe(inquiry.product).trim();
                        target.revision = safe(inquiry.revision).trim();
                        target.lastLba = capacity.lastLba;
                        target.blockSize = capacity.blockSize;
                        target.capacityBytes = capacity.capacityBytes;
                        target.usbDeviceName = usbDevice.getDeviceName();
                        target.usbVendorId = usbDevice.getVendorId();
                        target.usbProductId = usbDevice.getProductId();
                        details = "USB CARD READY\nVendor: " + target.vendor + "\nModel: " + target.product +
                                "\nRevision: " + target.revision + "\nReported capacity: " + formatCapacity(target.capacityBytes) +
                                "\nSectors: " + (target.lastLba + 1) + " × 512 bytes\n\nThis is a USB removable device. No writes have happened. Proceed only if this is the card you intend to erase.";
                    }
                }
            } catch (Exception e) {
                details = "USB CARD STATUS\nCould not inspect the USB card: " + safe(e.getMessage()) + "\nNo test was started.";
            } finally {
                closeUsb();
                fakeCardProbeRunning = false;
            }
            final String result = details;
            final FakeCardTarget found = target;
            runOnUiThread(() -> {
                if (!fakeCardInfoScreenVisible) return;
                fakeCardTarget = found;
                if (fakeCardDeviceInfoText != null) fakeCardDeviceInfoText.setText(result);
                if (fakeCardStartButton != null) fakeCardStartButton.setEnabled(found != null);
            });
        }, "fake-card-probe").start();
    }

    private void chooseFakeCardMode() {
        FakeCardTarget target = fakeCardTarget;
        if (target == null) return;
        final int[] selected = {0};
        new android.app.AlertDialog.Builder(this)
                .setTitle("CHOOSE TEST TYPE")
                .setSingleChoiceItems(new String[] {
                        "QUICK CHECK — randomized samples; faster, but can miss some fakes",
                        "FULL WRITE TEST — every sector; thorough, may take hours"
                }, 0, (dialog, which) -> selected[0] = which)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("CONTINUE TO WARNINGS", (d, w) -> confirmFakeCardWarningOne(target, selected[0] == 1))
                .show();
    }

    private void confirmFakeCardWarningOne(FakeCardTarget target, boolean full) {
        String message = full
                ? "The full test overwrites every sector on the USB card. All existing data will be destroyed and cannot be recovered by this app. It may take many hours."
                : "The quick test overwrites up to 1 GiB in scattered areas across the USB card. Files or filesystem data may be destroyed or corrupted. Assume you will lose everything on it. This cannot be undone.";
        new android.app.AlertDialog.Builder(this)
                .setTitle("WARNING 1 OF 3 — DATA LOSS")
                .setMessage(message)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("I UNDERSTAND — CONTINUE", (d, w) -> confirmFakeCardWarningTwo(target, full))
                .show();
    }

    private void confirmFakeCardWarningTwo(FakeCardTarget target, boolean full) {
        String testDetails = full
                ? "The app will write and verify every reported sector, then read the full card. This can take many hours."
                : "The app will overwrite up to 1 GiB in 1 MiB blocks scattered across the reported capacity, then verify those blocks. This is much faster but can miss some fakes or faults.";
        new android.app.AlertDialog.Builder(this)
                .setTitle("WARNING 2 OF 3 — CHECK THE DEVICE")
                .setMessage("The only target is this USB removable device:\n\n" + target.vendor + " " + target.product +
                        "\n" + formatCapacity(target.capacityBytes) + "\n\n" + testDetails + " A counterfeit controller may map distant addresses onto the same storage. Disconnecting or losing power can leave the card unusable. Internal NAND and the built-in SD slot are not touched.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("THAT USB CARD — CONTINUE", (d, w) -> confirmFakeCardWarningThree(target, full))
                .show();
    }

    private void confirmFakeCardWarningThree(FakeCardTarget target, boolean full) {
        EditText confirmation = new EditText(this);
        confirmation.setSingleLine(true);
        confirmation.setHint("Type ERASE to enable the final button");
        String finalMessage = full
                ? "Final check: every sector on the USB card will be overwritten; all existing data will be lost. Type ERASE below to start the full test. No reports are saved to internal storage."
                : "Final check: scattered test areas on this USB card will be overwritten and existing files may be corrupted. Assume the card’s contents are lost. Type ERASE below to start. No reports are saved to internal storage.";
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("WARNING 3 OF 3 — FINAL CONFIRMATION")
                .setMessage(finalMessage)
                .setView(confirmation)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("ERASE USB CARD & TEST", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!"ERASE".equalsIgnoreCase(confirmation.getText().toString().trim())) {
                confirmation.setError("Type ERASE exactly to confirm");
                return;
            }
            dialog.dismiss();
            startFakeCardTest(target, full);
        }));
        dialog.show();
    }

    private void startFakeCardTest(FakeCardTarget target, boolean full) {
        if (askBeforeInterruptingUsb(() -> startFakeCardTest(target, full))) return;
        showingHome = false;
        fakeCardInfoScreenVisible = false;
        fakeCardTestRunning = true;
        fakeCardCancelRequested = false;
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        setResponsivePadding(layout, 20, 20, 20, 20);
        TextView title = new TextView(this);
        title.setText("DESTRUCTIVE USB CARD TEST");
        setResponsiveTextSize(title, 22f);
        title.setTextColor(Color.rgb(220, 35, 55));
        TextView device = new TextView(this);
        device.setText(target.vendor + " " + target.product + " • " + formatCapacity(target.capacityBytes));
        fakeCardProgressText = new TextView(this);
        fakeCardProgressText.setText("Rechecking USB target before any write…");
        setResponsivePadding(fakeCardProgressText, 0, 24, 0, 16);
        fakeCardProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        fakeCardProgressBar.setMax(1000);
        Button cancel = new Button(this);
        cancel.setText("CANCEL TEST");
        fakeCardCancelButton = cancel;
        layout.addView(title);
        layout.addView(device);
        layout.addView(fakeCardProgressText);
        layout.addView(fakeCardProgressBar);
        layout.addView(cancel);
        setScrollablePage(layout);
        setKeepScreenAwake(true);
        cancel.setOnClickListener(v -> requestFakeCardCancel());
        title.setText(full ? "FULL USB CARD WRITE TEST" : "QUICK RANDOMIZED USB TEST");
        new Thread(() -> runFakeCardTest(target, full), "fake-card-test").start();
    }

    private void requestFakeCardCancel() {
        if (!fakeCardTestRunning) return;
        new android.app.AlertDialog.Builder(this)
                .setTitle("Stop the USB card test?")
                .setMessage("Some data may already have been overwritten. The card will be left partially tested and may not contain usable files.")
                .setNegativeButton("KEEP TESTING", null)
                .setPositiveButton("STOP AFTER CURRENT USB COMMAND", (d, w) -> {
                    fakeCardCancelRequested = true;
                    if (fakeCardProgressText != null) fakeCardProgressText.setText("Stopping after the current USB command… Data may already be destroyed.");
                }).show();
    }

    private void runFakeCardTest(FakeCardTarget target, boolean full) {
        String result = "";
        boolean success = false;
        botTransportFailed = false;
        botTransportFailure = null;
        long totalSectors = target.lastLba + 1;
        final int blocksPerChunk = 2048; // 1 MiB sample blocks at 512 bytes/sector.
        try {
            usbDevice = findMassStorageDevice();
            if (usbDevice == null || !usbManager.hasPermission(usbDevice) || !openBot()) {
                result = "USB connection or permission was lost. No write was performed.";
            } else {
                InquiryResult inquiry = performInquiry();
                CapacityResult capacity = performReadCapacity();
                boolean sameTarget = inquiry != null && capacity != null && inquiry.removable &&
                        target.usbDeviceName.equals(usbDevice.getDeviceName()) &&
                        target.usbVendorId == usbDevice.getVendorId() &&
                        target.usbProductId == usbDevice.getProductId() &&
                        target.vendor.equals(safe(inquiry.vendor).trim()) &&
                        target.product.equals(safe(inquiry.product).trim()) &&
                        target.revision.equals(safe(inquiry.revision).trim()) &&
                        target.lastLba == capacity.lastLba && target.blockSize == capacity.blockSize;
                if (!sameTarget || capacity.blockSize != 512) {
                    result = "The connected USB target changed since confirmation. Test aborted before any write.";
                } else if (full) {
                    TestOutcome outcome = runFullCardWriteTest(totalSectors);
                    result = outcome.message;
                    success = outcome.success;
                } else {
                    long regionCount = totalSectors / blocksPerChunk;
                    int sampleCount = (int)Math.min(1024L, regionCount);
                    if (sampleCount < 1) {
                        result = "The USB card is too small for this sample test. No write was performed.";
                    } else {
                        long[] sampleLbas = chooseRandomSampleLbas(regionCount, sampleCount, blocksPerChunk);
                        byte[] buffer = new byte[blocksPerChunk * 512];
                            int completed = 0;
                            int mismatchCount = 0;
                            int aliasedSampleCount = 0;
                            long wrapGcd = 0;
                            long firstAliasSourceLba = -1;
                        long bytesToWrite = (long)sampleCount * buffer.length;
                        String sampleSummary = getString(R.string.fake_card_sample_summary,
                                sampleCount, formatCapacity(bytesToWrite));
                        publishFakeProgress(0, "Writing randomized samples… 0 of " + sampleCount + "\n" + sampleSummary);
                        while (completed < sampleCount && !fakeCardCancelRequested) {
                            long lba = sampleLbas[completed];
                            fillCardPattern(buffer, lba, blocksPerChunk);
                            if (!performWrite10(lba, blocksPerChunk, buffer)) {
                                result = getString(botTransportFailed
                                        ? R.string.fake_card_usb_sample_write_error
                                        : R.string.fake_card_media_sample_write_error, lba, safe(botTransportFailure));
                                break;
                            }
                            completed++;
                            publishFakeProgress((int)(500.0 * completed / sampleCount), "Writing randomized samples… " + completed + " of " + sampleCount + "\n" + sampleSummary);
                        }
                        if (fakeCardCancelRequested) {
                            result = "Test cancelled during writing. Some scattered areas have been overwritten; files or the filesystem may be damaged.";
                        } else if (completed == sampleCount && !botTransportFailed) {
                            completed = 0;
                            publishFakeProgress(500, "Reading back randomized samples… 0 of " + sampleCount + "\nThe sample write pass is complete.");
                            while (completed < sampleCount && !fakeCardCancelRequested) {
                                long lba = sampleLbas[completed];
                                byte[] read = performRead10(lba, blocksPerChunk, buffer.length);
                                if (read == null || read.length < buffer.length) {
                                    result = getString(botTransportFailed
                                            ? R.string.fake_card_usb_sample_read_error
                                            : R.string.fake_card_media_sample_read_error, lba, safe(botTransportFailure));
                                    break;
                                }
                                fillCardPattern(buffer, lba, blocksPerChunk);
                                int mismatch = firstMismatch(read, buffer, buffer.length);
                                if (mismatch >= 0) {
                                    mismatchCount++;
                                    boolean thisSampleAliases = false;
                                    int validAliasSectors = 0;
                                    byte[] candidatePattern = new byte[512];
                                    for (int sector = 0; sector < blocksPerChunk; sector++) {
                                        int base = sector * 512;
                                        long expectedLba = lba + sector;
                                        long storedLba = ByteBuffer.wrap(read, base, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
                                        if (storedLba >= 0 && storedLba < totalSectors && storedLba != expectedLba) {
                                            fillCardPattern(candidatePattern, storedLba, 1);
                                            boolean intactOtherPattern = true;
                                            for (int byteIndex = 0; byteIndex < 512; byteIndex++) {
                                                if (read[base + byteIndex] != candidatePattern[byteIndex]) {
                                                    intactOtherPattern = false;
                                                    break;
                                                }
                                            }
                                            if (intactOtherPattern) {
                                                thisSampleAliases = true;
                                                long distance = Math.abs(storedLba - expectedLba);
                                                if (distance > 0) wrapGcd = gcd(wrapGcd, distance);
                                                if (firstAliasSourceLba < 0) firstAliasSourceLba = storedLba;
                                                if (++validAliasSectors >= 8) break;
                                            }
                                        }
                                    }
                                    if (thisSampleAliases) aliasedSampleCount++;
                                }
                                completed++;
                                publishFakeProgress(500 + (int)(500.0 * completed / sampleCount), "Verifying randomized samples… " + completed + " of " + sampleCount + "\n" + sampleSummary);
                            }
                            if (!fakeCardCancelRequested && completed == sampleCount && mismatchCount > 0) {
                                if (aliasedSampleCount > 0) {
                                    String estimate = wrapGcd > 0
                                            ? getString(R.string.fake_card_candidate_wrap_distance, formatCapacity(wrapGcd * 512L))
                                            : getString(R.string.fake_card_wrap_distance_unknown);
                                    String source = firstAliasSourceLba >= 0
                                            ? getString(R.string.fake_card_first_alias_source, firstAliasSourceLba) : "";
                                    result = getString(R.string.fake_card_quick_alias_result,
                                            aliasedSampleCount, source, estimate);
                                } else {
                                    result = getString(R.string.fake_card_quick_media_failure, mismatchCount);
                                }
                            } else if (!fakeCardCancelRequested && completed == sampleCount && result.isEmpty()) {
                                result = "RANDOMIZED SAMPLE PASSED\nAll " + sampleCount + " scattered 1 MiB samples were written and read back correctly. This quick check can catch common fake-capacity behaviour, but it does not test every sector and cannot guarantee the card is genuine or reliable.";
                                success = true;
                            }
                            if (fakeCardCancelRequested) result = "Test cancelled during verification. Scattered sample areas remain overwritten; files or the filesystem may be damaged.";
                        }
                    }
                }
            }
        } catch (Exception e) {
            result = getString(botTransportFailed ? R.string.fake_card_unexpected_usb_error
                    : R.string.fake_card_unexpected_test_error, safe(e.getMessage()));
        } finally {
            closeUsb();
            fakeCardTestRunning = false;
            fakeCardCancelRequested = false;
            setKeepScreenAwake(false);
            final String finalResult = result.isEmpty() ? "Test stopped. Check the USB connection and card." : result;
            final boolean passed = success;
            runOnUiThread(() -> {
                if (fakeCardProgressBar != null) fakeCardProgressBar.setProgress(passed ? 1000 : fakeCardProgressBar.getProgress());
                if (fakeCardProgressText != null) fakeCardProgressText.setText(finalResult + "\n\nNo report was saved to internal storage.");
                if (fakeCardCancelButton != null) {
                    fakeCardCancelButton.setText("DONE");
                    fakeCardCancelButton.setOnClickListener(v -> showHome());
                }
                runPendingUsbAction();
            });
        }
    }

    private TestOutcome runFullCardWriteTest(long totalSectors) {
        final int maxBlocks = 8192; // 4 MiB per command; the last chunk may be smaller.
        long completed = 0;
        long isolatedReadErrors = 0;
        publishFakeProgress(0, "Writing and reading each 4 MiB block…\nThe test stops at the first failure. Existing data is being destroyed.");
        long[] canaryLbas = buildCanaryLbas(totalSectors);
        TestOutcome canarySetup = establishPersistentCanaries(canaryLbas, totalSectors);
        if (canarySetup != null) return canarySetup;
        publishFakeProgress(0, getString(R.string.fake_card_canaries_ready));
        while (completed < totalSectors && !fakeCardCancelRequested) {
            int blocks = (int)Math.min(maxBlocks, totalSectors - completed);
            int bytes = blocks * 512;
            byte[] expected = new byte[bytes];
            fillCardPattern(expected, completed, blocks);
            if (!performWrite10(completed, blocks, expected)) {
                int message = botTransportFailed ? R.string.fake_card_full_usb_write_error
                        : R.string.fake_card_full_media_write_error;
                return new TestOutcome(getString(message, completed,
                        formatCapacity(completed * 512L), safe(botTransportFailure)), false);
            }
            byte[] actual = performRead10(completed, blocks, bytes);
            if (actual == null || actual.length < bytes) {
                int message = botTransportFailed ? R.string.fake_card_full_usb_read_error
                        : R.string.fake_card_full_media_read_error;
                return new TestOutcome(getString(message, completed,
                        formatCapacity(completed * 512L), safe(botTransportFailure)), false);
            }
            int mismatch = firstMismatch(actual, expected, bytes);
            if (mismatch >= 0) {
                long badLba = completed + mismatch / 512;
                byte[] retry = performRead10(completed, blocks, bytes);
                if (retry == null && botTransportFailed) {
                    return new TestOutcome("USB read failed again near sector " + badLba + ". The connection or reader may be unstable, so the test stopped. Last fully verified position: " + formatCapacity(completed * 512L) + ".", false);
                }
                boolean retryStillBad = retry == null || firstMismatch(retry, expected, bytes) >= 0;
                long nextLba = completed + blocks;
                int followingFailures = 0;
                int followingChecked = 0;
                boolean hardUsbFailure = false;
                while (followingChecked < 2 && nextLba < totalSectors) {
                    int followBlocks = (int)Math.min(maxBlocks, totalSectors - nextLba);
                    int followBytes = followBlocks * 512;
                    byte[] followExpected = new byte[followBytes];
                    fillCardPattern(followExpected, nextLba, followBlocks);
                    boolean failed = !performWrite10(nextLba, followBlocks, followExpected);
                    if (!failed) {
                        byte[] followActual = performRead10(nextLba, followBlocks, followBytes);
                        failed = followActual == null || firstMismatch(followActual, followExpected, followBytes) >= 0;
                    }
                    if (botTransportFailed) { hardUsbFailure = true; break; }
                    if (failed) followingFailures++;
                    followingChecked++;
                    nextLba += followBlocks;
                    publishFakeProgress((int)(1000.0 * nextLba / totalSectors), "Confirming a possible capacity limit… " + followingChecked + " of 2 extra blocks checked\n" + formatCapacity(nextLba * 512L) + " written and read");
                }
                if (hardUsbFailure) {
                    return new TestOutcome("USB transfer failed while confirming the first read error near sector " + badLba + ". The reader or connection may be unstable. Last fully verified position: " + formatCapacity(completed * 512L) + ".", false);
                }

                TestOutcome canaryFailure = inspectPersistentCanaries(canaryLbas, canaryLbas.length,
                        totalSectors, badLba);
                if (canaryFailure != null) return canaryFailure;
                if (followingChecked == 2 && followingFailures == 2) {
                    return new TestOutcome(getString(R.string.fake_card_repeated_media_failure,
                            formatCapacity(badLba * 512L), formatCapacity(nextLba * 512L)), false);
                }

                isolatedReadErrors += 1 + followingFailures + (retryStillBad ? 1 : 0);
                completed = nextLba;
                publishFakeProgress((int)(1000.0 * completed / totalSectors), "One read error did not repeat across the next blocks; continuing the full test…\n" + formatCapacity(completed * 512L) + " tested");
                continue;
            }

            // Recheck several persistent address-specific markers. Their payloads
            // identify the source LBA if a later address aliases an earlier one.
            if (completed > 0 && (completed / maxBlocks) % 64 == 0) {
                TestOutcome canaryFailure = inspectPersistentCanaries(canaryLbas,
                        canaryLbas.length, totalSectors, completed);
                if (canaryFailure != null) return canaryFailure;
            }
            completed += blocks;
            publishFakeProgress((int)(1000.0 * completed / totalSectors), "Writing then reading each block… " + percent(completed, totalSectors) + "%\n" + formatCapacity(completed * 512L) + " written and verified");
        }
        if (fakeCardCancelRequested) return new TestOutcome("Test cancelled between write/read blocks. The card has been partially overwritten.", false);
        String isolatedNote = isolatedReadErrors == 0 ? "" : "\n" + isolatedReadErrors + " isolated read/write mismatch(es) did not repeat across the confirmation blocks.";
        return new TestOutcome("FULL WRITE TEST PASSED\nEvery sector in the reported " + formatCapacity(totalSectors * 512L) + " capacity was written and read back immediately." + isolatedNote, true);
    }

    private static long[] buildCanaryLbas(long totalSectors) {
        TreeSet<Long> points = new TreeSet<>();
        points.add(0L);
        points.add(totalSectors / 16);
        points.add(totalSectors / 8);
        points.add(totalSectors / 4);
        points.add(totalSectors / 2);
        points.add((totalSectors * 3) / 4);
        points.add((totalSectors * 7) / 8);
        points.add((totalSectors * 15) / 16);
        if (totalSectors > 1) points.add(totalSectors - 1);
        long[] result = new long[points.size()];
        int index = 0;
        for (Long point : points) result[index++] = point;
        return result;
    }

    private TestOutcome establishPersistentCanaries(long[] canaryLbas, long totalSectors) {
        for (int i = 0; i < canaryLbas.length; i++) {
            long lba = canaryLbas[i];
            byte[] expected = new byte[512];
            fillCardPattern(expected, lba, 1);
            if (!performWrite10(lba, 1, expected)) {
                int message = botTransportFailed ? R.string.fake_card_canary_usb_write_error
                        : R.string.fake_card_canary_media_write_error;
                return new TestOutcome(getString(message, lba, safe(botTransportFailure)), false);
            }
            TestOutcome check = inspectPersistentCanaries(canaryLbas, i + 1, totalSectors, lba);
            if (check != null) return check;
        }
        return null;
    }

    private TestOutcome inspectPersistentCanaries(long[] canaryLbas, int count,
            long totalSectors, long progressLba) {
        for (int i = 0; i < count; i++) {
            long canaryLba = canaryLbas[i];
            byte[] actual = performRead10(canaryLba, 1, 512);
            if (actual == null || actual.length < 512) {
                int message = botTransportFailed ? R.string.fake_card_canary_usb_read_error
                        : R.string.fake_card_canary_media_read_error;
                return new TestOutcome(getString(message, canaryLba, safe(botTransportFailure)), false);
            }
            if (isPatternSector(actual, 0, canaryLba)) continue;
            long sourceLba = ByteBuffer.wrap(actual, 0, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
            if (sourceLba >= 0 && sourceLba < totalSectors && sourceLba != canaryLba
                    && isPatternSector(actual, 0, sourceLba)) {
                long distance = Math.abs(sourceLba - canaryLba);
                String estimate = distance > 0
                        ? getString(R.string.fake_card_candidate_wrap_distance, formatCapacity(distance * 512L))
                        : getString(R.string.fake_card_wrap_distance_unknown);
                return new TestOutcome(getString(R.string.fake_card_canary_alias_result,
                        canaryLba, sourceLba, formatCapacity(sourceLba * 512L), estimate), false);
            }
            return new TestOutcome(getString(R.string.fake_card_canary_media_corruption,
                    canaryLba, formatCapacity(progressLba * 512L)), false);
        }
        return null;
    }

    private static boolean isPatternSector(byte[] actual, int offset, long expectedLba) {
        if (actual == null || offset < 0 || offset + 512 > actual.length) return false;
        byte[] expected = new byte[512];
        fillCardPattern(expected, expectedLba, 1);
        for (int i = 0; i < 512; i++) if (actual[offset + i] != expected[i]) return false;
        return true;
    }

    private static long gcd(long a, long b) {
        while (b != 0) { long t = a % b; a = b; b = t; }
        return a;
    }

    private static long[] chooseRandomSampleLbas(long regionCount, int sampleCount, int blocksPerRegion) {
        if (regionCount > Integer.MAX_VALUE) throw new IllegalArgumentException("Card has too many sample regions.");
        HashSet<Long> selected = new HashSet<>();
        Random random = new Random(System.nanoTime());
        while (selected.size() < sampleCount) {
            long region = random.nextInt((int)regionCount);
            selected.add(region * blocksPerRegion);
        }
        long[] result = new long[sampleCount];
        int i = 0;
        for (Long lba : selected) result[i++] = lba;
        Arrays.sort(result);
        return result;
    }

    private void publishFakeProgress(int progress, String message) {
        long now = android.os.SystemClock.elapsedRealtime();
        synchronized (this) {
            // Keep long USB tests from flooding the main-thread queue with one UI
            // update per block. One update per second is smooth and leaves the UI responsive.
            if (now - lastFakeProgressPostMs < 1000) return;
            lastFakeProgressPostMs = now;
        }
        runOnUiThread(() -> {
            if (fakeCardProgressBar != null) fakeCardProgressBar.setProgress(progress);
            if (fakeCardProgressText != null) fakeCardProgressText.setText(message);
        });
    }

    private static int percent(long done, long total) {
        return total <= 0 ? 0 : (int)Math.min(100, done * 100.0 / total);
    }

    private static void fillCardPattern(byte[] data, long firstLba, int blocks) {
        for (int sector = 0; sector < blocks; sector++) {
            long lba = firstLba + sector;
            int base = sector * 512;
            long seed = lba ^ 0x9E3779B97F4A7C15L;
            for (int i = 0; i < 512; i++) {
                seed ^= seed << 13;
                seed ^= seed >>> 7;
                seed ^= seed << 17;
                data[base + i] = (byte)(seed >>> 24);
            }
            ByteBuffer.wrap(data, base, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(lba);
        }
    }

    private static int firstMismatch(byte[] actual, byte[] expected, int length) {
        for (int i = 0; i < length; i++) if (actual[i] != expected[i]) return i;
        return -1;
    }

    private boolean performWrite10(long lba, int blockCount, byte[] data) {
        if (connection == null || blockCount <= 0 || blockCount > 0xFFFF || data.length != blockCount * 512) return false;
        byte[] cdb = new byte[10];
        cdb[0] = 0x2A; // WRITE(10)
        cdb[2] = (byte)(lba >>> 24); cdb[3] = (byte)(lba >>> 16);
        cdb[4] = (byte)(lba >>> 8); cdb[5] = (byte)lba;
        cdb[7] = (byte)(blockCount >>> 8); cdb[8] = (byte)blockCount;
        int tag = commandTag++;
        byte[] cbw = new byte[31];
        ByteBuffer bb = ByteBuffer.wrap(cbw).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(0x43425355); bb.putInt(tag); bb.putInt(data.length);
        bb.put((byte)0); bb.put((byte)0); bb.put((byte)cdb.length); bb.put(cdb);
        if (connection.bulkTransfer(bulkOut, cbw, cbw.length, 5000) != 31) {
            botTransportFailed = true; botTransportFailure = "USB write command could not be sent."; return false;
        }
        int total = 0;
        while (total < data.length) {
            int chunkLength = Math.min(data.length - total, MAX_BULK_DATA_CHUNK_BYTES);
            int sent = connection.bulkTransfer(bulkOut, data, total, chunkLength, 10000);
            if (sent <= 0) { botTransportFailed = true; botTransportFailure = "USB card write transfer failed (" + sent + ")."; return false; }
            total += sent;
        }
        byte[] csw = new byte[13];
        int received = connection.bulkTransfer(bulkIn, csw, csw.length, 5000);
        if (received != 13) { botTransportFailed = true; botTransportFailure = "USB write status was not returned."; return false; }
        ByteBuffer status = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN);
        if (status.getInt() != 0x53425355 || status.getInt() != tag) { botTransportFailed = true; botTransportFailure = "USB write status did not match the command."; return false; }
        int residue = status.getInt(); int commandStatus = status.get() & 0xFF;
        if (residue != 0 || commandStatus != 0) { botTransportFailure = "USB card rejected a write (status " + commandStatus + ")."; return false; }
        return true;
    }

    private void showSettings() {
        showingHome = false;
        surfaceInfoScreenVisible = false;
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        setResponsivePadding(layout, 20, 20, 20, 20);

        Button backButton = new Button(this);
        backButton.setText("BACK");
        TextView title = new TextView(this);
        title.setText("SETTINGS & THEMES");
        setResponsiveTextSize(title, 22f);
        title.setTextColor(Color.BLACK);
        setResponsivePadding(title, 0, 14, 0, 16);

        TextView description = new TextView(this);
        description.setText("Pick the look that suits you. Standard stays simple; Retro Neon brings the arcade colours.");
        setResponsiveTextSize(description, 16f);
        description.setTextColor(Color.DKGRAY);
        setResponsivePadding(description, 0, 0, 0, 12);

        RadioGroup themes = new RadioGroup(this);
        themes.setOrientation(RadioGroup.VERTICAL);
        String[] names = {
                "Standard Light",
                "Standard Dark",
                "Retro Neon Dark",
                "Retro Neon Light"
        };
        for (int i = 0; i < names.length; i++) {
            RadioButton option = new RadioButton(this);
            option.setId(100 + i);
            option.setText(names[i]);
            setResponsiveTextSize(option, 18f);
            themes.addView(option);
        }
        themes.check(100 + selectedTheme());

        layout.addView(backButton);
        layout.addView(title);
        addAccentRule(layout);
        layout.addView(description);
        TextView languageLabel = new TextView(this);
        languageLabel.setText(R.string.language_section_title);
        setResponsiveTextSize(languageLabel, 18f);
        layout.addView(languageLabel);
        Button languageButton = new Button(this);
        languageButton.setText(getString(R.string.language_setting, getSelectedLanguageLabel()));
        layout.addView(languageButton);

        TextView fitHeading = new TextView(this);
        fitHeading.setText(R.string.screen_fit_heading);
        setResponsiveTextSize(fitHeading, 18f);
        setResponsivePadding(fitHeading, 0, 12, 0, 2);
        layout.addView(fitHeading);

        TextView fitDescription = new TextView(this);
        fitDescription.setText(R.string.screen_fit_description);
        setResponsiveTextSize(fitDescription, 14f);
        setResponsivePadding(fitDescription, 0, 0, 0, 8);
        layout.addView(fitDescription);

        int[] textSteps = {80, 90, 100, 110, 120};
        int[] buttonSteps = {85, 92, 100};
        android.content.SharedPreferences preferences =
                getSharedPreferences("smart_hdd_settings", MODE_PRIVATE);
        TextView textSizeLabel = new TextView(this);
        int textIndex = stepIndex(textSteps, preferences.getInt("app_text_size_percent", 100));
        textSizeLabel.setText(getString(R.string.text_size_setting, textSteps[textIndex]));
        setResponsiveTextSize(textSizeLabel, 15f);
        layout.addView(textSizeLabel);
        SeekBar textSizeSlider = new SeekBar(this);
        textSizeSlider.setMax(textSteps.length - 1);
        textSizeSlider.setProgress(textIndex);
        layout.addView(textSizeSlider);

        TextView buttonSizeLabel = new TextView(this);
        int buttonIndex = stepIndex(buttonSteps, preferences.getInt("app_button_size_percent", 100));
        buttonSizeLabel.setText(getString(R.string.button_size_setting, buttonSteps[buttonIndex]));
        setResponsiveTextSize(buttonSizeLabel, 15f);
        layout.addView(buttonSizeLabel);
        SeekBar buttonSizeSlider = new SeekBar(this);
        buttonSizeSlider.setMax(buttonSteps.length - 1);
        buttonSizeSlider.setProgress(buttonIndex);
        layout.addView(buttonSizeSlider);

        layout.addView(themes);
        setScrollablePage(layout);

        backButton.setOnClickListener(v -> showHome());
        languageButton.setOnClickListener(v -> showLanguagePicker());
        textSizeSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int percent = textSteps[progress];
                textSizeLabel.setText(getString(R.string.text_size_setting, percent));
                preferences.edit().putInt("app_text_size_percent", percent).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { showSettings(); }
        });
        buttonSizeSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int percent = buttonSteps[progress];
                buttonSizeLabel.setText(getString(R.string.button_size_setting, percent));
                preferences.edit().putInt("app_button_size_percent", percent).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { showSettings(); }
        });
        themes.setOnCheckedChangeListener((group, checkedId) -> {
            int theme = checkedId - 100;
            if (theme < 0 || theme > 3) return;
            getSharedPreferences("smart_hdd_settings", MODE_PRIVATE)
                    .edit().putInt("appearance_v2", theme).apply();
            applyCurrentTheme(layout);
        });
    }

    private int stepIndex(int[] steps, int value) {
        for (int i = 0; i < steps.length; i++) {
            if (steps[i] == value) return i;
        }
        return steps.length - 1;
    }

    @Override
    public void onBackPressed() {
        if (!showingHome) {
            handleAppBack();
            return;
        }
        super.onBackPressed();
    }

    private void handleAppBack() {
        if (fakeCardTestRunning) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("USB card test is running")
                    .setMessage("Cancel after the current USB command and leave? Some or all existing data may already have been overwritten.")
                    .setPositiveButton("STOP TEST & LEAVE", (dialog, which) -> {
                        pendingUsbAction = this::showHome;
                        fakeCardCancelRequested = true;
                    })
                    .setNegativeButton("KEEP TESTING", null)
                    .show();
            return;
        }
        if (!surfaceTestRunning) {
            showHome();
            return;
        }

        new android.app.AlertDialog.Builder(this)
                .setTitle("Surface test is running")
                .setMessage("Cancel the test and leave this screen? The current USB read will finish first.")
                .setPositiveButton("CANCEL TEST & LEAVE", (dialog, which) -> {
                    pendingUsbAction = this::showHome;
                    surfaceTestCancelRequested = true;
                    log("");
                    log("CANCEL REQUESTED — waiting for the current USB read to finish...");
                })
                .setNegativeButton("KEEP SCANNING", null)
                .show();
    }

    private final BroadcastReceiver usbReceiver =
            new BroadcastReceiver() {

        @Override
        public void onReceive(
                Context context,
                Intent intent) {

            if (ACTION_USB_PERMISSION.equals(
                    intent.getAction())) {

                UsbDevice device =
                        intent.getParcelableExtra(
                                UsbManager.EXTRA_DEVICE
                        );

                if (device != null &&
                        usbManager.hasPermission(device)) {

                    usbDevice = device;

                    log("USB permission granted.");
                    log("Device ready.");
                    if (surfaceInfoScreenVisible && surfaceDeviceInfoText != null) {
                        surfaceDeviceInfoText.postDelayed(
                                MainActivity.this::probeSurfaceTestDevice, 250);
                    }

                    if (fakeCardInfoScreenVisible && fakeCardDeviceInfoText != null) {
                        fakeCardDeviceInfoText.postDelayed(
                                MainActivity.this::probeFakeCardDevice, 250);
                    }

                } else {

                    log("USB permission denied.");
                }
            }
        }
    };

    private void showSmartScreen() {
        showSmartScreen(true);
    }

    private void showSmartScreen(boolean scanUsbOnOpen) {
        showingHome = false;
        surfaceInfoScreenVisible = false;

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        setResponsivePadding(layout, 20, 20, 20, 20);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);

        Button backButton = new Button(this);
        backButton.setText("BACK");

        surfaceCancelButton = new Button(this);
        surfaceCancelButton.setText("CANCEL");
        surfaceCancelButton.setVisibility(View.GONE);

        TextView title = new TextView(this);
        title.setText("  HDD SMART INFO");
        setResponsiveTextSize(title, 22f);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER_VERTICAL);

        header.addView(backButton);
        header.addView(surfaceCancelButton);
        header.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView safety = new TextView(this);
        safety.setText(
                "SAFE READ-ONLY MODE\n" +
                "No formatting • No partitioning • No filesystem writes"
        );
        setResponsiveTextSize(safety, 14f);
        safety.setTextColor(Color.rgb(0, 100, 0));
        setResponsivePadding(safety, 0, 15, 0, 15);

        Button scanButton = new Button(this);
        scanButton.setText("SCAN USB");

        Button smartButton = new Button(this);
        smartButton.setText("READ SMART DATA");

        Button surfaceButton = new Button(this);
        surfaceButton.setText("HDD SURFACE TEST");

        Button disconnectButton = new Button(this);
        disconnectButton.setText("SAFE DISCONNECT USB");

        output = new TextView(this);
        setResponsiveTextSize(output, 14f);
        output.setTextColor(Color.BLACK);
        setResponsivePadding(output, 0, 15, 0, 0);

        surfaceProgressBar = new ProgressBar(
                this, null, android.R.attr.progressBarStyleHorizontal);
        surfaceProgressBar.setMax(1000);
        surfaceProgressBar.setVisibility(View.GONE);

        surfaceProgressText = new TextView(this);
        setResponsiveTextSize(surfaceProgressText, 14f);
        surfaceProgressText.setTextColor(Color.DKGRAY);
        setResponsivePadding(surfaceProgressText, 0, 6, 0, 4);
        surfaceProgressText.setVisibility(View.GONE);

        layout.addView(header);
        addAccentRule(layout);
        layout.addView(safety);
        layout.addView(scanButton);
        layout.addView(smartButton);
        layout.addView(surfaceButton);
        layout.addView(disconnectButton);
        layout.addView(output);
        layout.addView(surfaceProgressBar);
        layout.addView(surfaceProgressText);

        outputScroll = setScrollablePage(layout);

        backButton.setOnClickListener(v -> handleAppBack());

        surfaceCancelButton.setOnClickListener(v -> {
            if (surfaceTestRunning) {
                surfaceTestCancelRequested = true;
                log("");
                log("CANCEL REQUESTED.");
                log("Stopping after the current USB read...");
            }
        });

        scanButton.setOnClickListener(v -> scanUsb());
        disconnectButton.setOnClickListener(v -> disconnectUsbSafely());

        smartButton.setOnClickListener(v -> startSmart());

        surfaceButton.setOnClickListener(v -> showSurfaceTestInfo());

        if (scanUsbOnOpen) scanUsb();
    }

    private void showSurfaceTestInfo() {
        if (askBeforeInterruptingUsb(this::showSurfaceTestInfo)) return;
        showingHome = false;
        surfaceInfoScreenVisible = true;
        surfaceProgressBar = null;
        surfaceProgressText = null;
        surfaceCancelButton = null;

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        setResponsivePadding(layout, 20, 20, 20, 20);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);

        Button backButton = new Button(this);
        backButton.setText("BACK");
        TextView title = new TextView(this);
        title.setText("  HDD SURFACE TEST");
        setResponsiveTextSize(title, 22f);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(backButton);
        header.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView info = new TextView(this);
        setResponsiveTextSize(info, 16f);
        info.setTextColor(Color.DKGRAY);
        setResponsivePadding(info, 0, 18, 0, 18);
        info.setText("This test reads the drive from beginning to end to check that its data can be read.\n\n"
                + "READ-ONLY: it does not write to, format, or partition the drive.\n\n"
                + "It can take a while, depending on the drive, USB reader, and connection. Read errors are retried and reported. If the USB connection itself fails, the test stops.\n\n"
                + "Keep the drive connected while the test runs. You can cancel from the test screen; Back will ask before stopping it.");

        surfaceDeviceInfoText = new TextView(this);
        setResponsiveTextSize(surfaceDeviceInfoText, 16f);
        surfaceDeviceInfoText.setTextColor(Color.BLACK);
        setResponsivePadding(surfaceDeviceInfoText, 0, 8, 0, 18);
        surfaceDeviceInfoText.setText("Reading connected drive information…");

        LinearLayout infoContent = new LinearLayout(this);
        infoContent.setOrientation(LinearLayout.VERTICAL);
        infoContent.addView(info);
        infoContent.addView(surfaceDeviceInfoText);

        Button refreshButton = new Button(this);
        refreshButton.setText("READ DEVICE INFO AGAIN");

        Button startButton = new Button(this);
        startButton.setText("START TEST");
        startButton.setEnabled(false);
        surfaceInfoStartButton = startButton;

        layout.addView(header);
        addAccentRule(layout);
        layout.addView(infoContent);
        layout.addView(refreshButton);
        layout.addView(startButton);
        setScrollablePage(layout);

        backButton.setOnClickListener(v -> showHome());
        refreshButton.setOnClickListener(v -> probeSurfaceTestDevice());
        startButton.setOnClickListener(v -> {
            if (!startButton.isEnabled()) return;
            surfaceInfoScreenVisible = false;
            showSmartScreen(false);
            startSurfaceTest();
        });
        probeSurfaceTestDevice();
    }

    private void probeSurfaceTestDevice() {
        if (!surfaceInfoScreenVisible || surfaceInfoProbeRunning) return;
        surfaceInfoProbeRunning = true;
        runOnUiThread(() -> {
            if (surfaceDeviceInfoText != null) {
                surfaceDeviceInfoText.setText("Reading connected drive information…");
            }
            if (surfaceInfoStartButton != null) {
                surfaceInfoStartButton.setEnabled(false);
            }
        });

        new Thread(() -> {
            String details;
            boolean canStart = false;
            try {
                usbDevice = findMassStorageDevice();
                if (usbDevice == null) {
                    details = "CURRENT DRIVE\nNo USB mass-storage drive found. Connect the drive and tap READ DEVICE INFO AGAIN.";
                } else if (!usbManager.hasPermission(usbDevice)) {
                    details = "CURRENT DRIVE\nUSB permission is needed. Allow the permission prompt and I’ll read the model and capacity.";
                    requestUsbPermission(usbDevice);
                } else if (!openBot()) {
                    details = "CURRENT DRIVE\nCould not open the USB drive. Check the adapter and connection, then try again.";
                } else {
                    InquiryResult inquiry = performInquiry();
                    CapacityResult capacity = performReadCapacity();
                    if (inquiry == null || capacity == null ||
                            capacity.capacityBytes <= 0 || capacity.blockSize <= 0) {
                        details = "CURRENT DRIVE\nThe device is connected, but its model or capacity could not be read. Check the connection and try again.";
                    } else {
                        boolean supported = capacity.blockSize == 512 &&
                                capacity.lastLba <= 0xFFFFFFFFL;
                        long estimatedRate = getSharedPreferences(
                                "smart_hdd_settings", MODE_PRIVATE).getLong(
                                surfaceRatePreferenceKey(inquiry), 0L);
                        boolean usingPreviousRate = estimatedRate > 0;
                        if (!usingPreviousRate) {
                            estimatedRate = 32L * 1024L * 1024L;
                        }
                        long seconds = (long) Math.ceil(
                                capacity.capacityBytes / (double) estimatedRate);
                        details = "CURRENT DRIVE\n"
                                + "Model: " + safe(inquiry.product).trim() + "\n"
                                + "Vendor: " + safe(inquiry.vendor).trim() + "\n"
                                + "Capacity: " + formatCapacity(capacity.capacityBytes) + "\n"
                                + "Estimated test time: about " + formatDuration(seconds) + "\n"
                                + (usingPreviousRate
                                        ? "Estimate based on your last completed test with this USB device."
                                        : "Rough estimate using a typical USB read speed; actual time can vary.")
                                + (supported ? "" : "\n\nThis drive’s sector format or size is not supported by the current surface test.");
                        canStart = supported;
                    }
                }
            } catch (Exception e) {
                details = "CURRENT DRIVE\nCould not read drive information: "
                        + safe(e.getMessage()) + "\nCheck the connection and try again.";
            } finally {
                closeUsb();
                surfaceInfoProbeRunning = false;
            }

            final String result = details;
            final boolean startEnabled = canStart;
            runOnUiThread(() -> {
                if (!surfaceInfoScreenVisible) return;
                if (surfaceDeviceInfoText != null) {
                    surfaceDeviceInfoText.setText(result);
                }
                if (surfaceInfoStartButton != null) {
                    surfaceInfoStartButton.setEnabled(startEnabled);
                }
            });
        }, "surface-device-info").start();
    }

    private void log(String text) {
        runOnUiThread(() -> {
            if (output != null) {
                output.append(text + "\n");

                if (outputScroll != null) {
                    outputScroll.post(() ->
                            outputScroll.fullScroll(
                                    View.FOCUS_DOWN));
                }
            }
        });
    }
    private void clearOutput() {
        runOnUiThread(() -> {
            if (output != null) {
                output.setText("");
            }
        });
    }
    private boolean askBeforeInterruptingUsb(Runnable nextAction) {
        if (!usbScanRunning && !smartReadRunning && !surfaceTestRunning && !fakeCardTestRunning) return false;
        String operation = smartReadRunning ? "SMART read" :
                (fakeCardTestRunning ? "destructive USB card test" :
                (surfaceTestRunning ? "surface test" : "USB scan"));
        new android.app.AlertDialog.Builder(this)
                .setTitle("USB operation in progress")
                .setMessage("The " + operation + " is using the USB device. Cancel it and continue after the current USB command finishes, or wait for it to finish?")
                .setPositiveButton("Cancel read & continue", (dialog, which) -> {
                    pendingUsbAction = nextAction;
                    smartReadCancelRequested = true;
                    surfaceTestCancelRequested = true;
                    usbScanCancelRequested = true;
                    fakeCardCancelRequested = true;
                    log("Finishing the current USB command before switching...");
                })
                .setNegativeButton("Wait", null)
                .show();
        return true;
    }

    private void runPendingUsbAction() {
        runOnUiThread(() -> {
            Runnable action = pendingUsbAction;
            pendingUsbAction = null;
            if (action != null) action.run();
        });
    }
    private void showSurfaceProgress() {
        runOnUiThread(() -> {
            if (surfaceProgressBar != null) {
                surfaceProgressBar.setProgress(0);
                surfaceProgressBar.setVisibility(View.VISIBLE);
            }
            if (surfaceProgressText != null) {
                surfaceProgressText.setText("Starting surface test…");
                surfaceProgressText.setVisibility(View.VISIBLE);
            }
        });
    }
    private void updateSurfaceProgress(long scannedBytes, long totalBytes,
                                       long readBytes, double scanBytesPerSecond) {
        int progress = totalBytes <= 0 ? 0
                : (int) Math.min(1000L, scannedBytes * 1000L / totalBytes);
        long remainingBytes = Math.max(0L, totalBytes - scannedBytes);
        String speedText = scanBytesPerSecond > 0
                ? String.format(Locale.US, "%.1f MB/s",
                        scanBytesPerSecond / (1024.0 * 1024.0))
                : "Measuring speed…";
        String etaText = scannedBytes >= totalBytes
                ? "Finished"
                : (scanBytesPerSecond > 0
                        ? "About " + formatDuration(
                                (long) Math.ceil(remainingBytes / scanBytesPerSecond)) + " left"
                        : "Calculating time left…");
        String status = "Scanned " + formatCapacity(scannedBytes) + " of "
                + formatCapacity(totalBytes) + "  •  Read "
                + formatCapacity(readBytes) + "\n"
                + speedText + "  •  " + etaText;
        runOnUiThread(() -> {
            if (surfaceProgressBar != null) {
                surfaceProgressBar.setProgress(progress);
            }
            if (surfaceProgressText != null) {
                surfaceProgressText.setText(status);
            }
        });
    }

    private String formatDuration(long totalSeconds) {
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) return hours + "h " + minutes + "m";
        if (minutes > 0) return minutes + "m " + seconds + "s";
        return seconds + "s";
    }
    private void startSurfaceTest() {
        if (askBeforeInterruptingUsb(this::startSurfaceTest)) return;
        clearOutput();
        surfaceTestRunning = true;
        surfaceTestCancelRequested = false;
        botTransportFailed = false;
        botTransportFailure = null;
        showSurfaceProgress();

        setKeepScreenAwake(true);

        runOnUiThread(() -> {
            if (surfaceCancelButton != null) {
                surfaceCancelButton.setVisibility(View.VISIBLE);
            }
        });

        new Thread(() -> {
            try {
                usbDevice = findMassStorageDevice();
                if (usbDevice == null) {
                    log("No USB Mass Storage device found.");
                    log("Connect a USB-SATA/IDE storage adapter.");
                    return;
                }
                if (!usbManager.hasPermission(usbDevice)) {
                    log("USB permission is not available. Press SCAN USB and allow access.");
                    return;
                }
                if (!openBot()) return;
                setKeepScreenAwake(true);
                log("════════════════════════════");
                log("       HDD SURFACE TEST");
                log("════════════════════════════");
                log("");
                log("READ-ONLY TEST");
                log("No data will be written.");
                log("");

                InquiryResult inquiry = performInquiry();

                if (inquiry == null) {
                    log("SCSI INQUIRY FAILED.");
                    return;
                }

                CapacityResult capacity =
                        performReadCapacity();

                if (capacity == null ||
                        capacity.blockSize <= 0 ||
                        capacity.capacityBytes <= 0) {

                    log("Unable to determine drive capacity.");
                    return;
                }

                log("Model: " + safe(inquiry.product));
                log("Capacity: " +
                        formatCapacity(capacity.capacityBytes));
                log("Block size: " +
                        capacity.blockSize + " bytes");
                log("");
                log("Reading drive sequentially...");
                log("");

                // READ(10) uses a 32-bit LBA, so this implementation
                // supports normal HDDs up to roughly 2 TiB at 512 bytes/LBA.
                if (capacity.blockSize != 512) {
                    log("SURFACE TEST STOPPED.");
                    log("This first version expects 512-byte sectors.");
                    return;
                }

                long maxRead10Lba = 0xFFFFFFFFL;

                if (capacity.lastLba > maxRead10Lba) {
                    log("SURFACE TEST STOPPED.");
                    log("Drive is too large for READ(10).");
                    log("READ(16) support can be added later.");
                    return;
                }

                final int blocksPerRead = 8192; // 4 MiB
                final int bytesPerRead =
                        blocksPerRead * (int) capacity.blockSize;

                long totalBlocks = capacity.lastLba + 1L;
                long totalBytes = capacity.capacityBytes;
                long currentLba = 0;
                long scanStartedAt = System.currentTimeMillis();

                long bytesRead = 0;
                long readErrors = 0;
                long recoveredErrors = 0;
                long unrecoverableErrors = 0;

                ArrayList<String> surfaceLog =
                        new ArrayList<>();

                long firstErrorLba = -1;
                long lastErrorLba = -1;

                long lastProgressTime = 0;
                long lastRateSampleTime = System.currentTimeMillis();
                long lastRateSampleBytes = 0;
                double scanBytesPerSecond = 0;

                while (currentLba < totalBlocks) {

                    if (surfaceTestCancelRequested || usbScanCancelRequested || smartReadCancelRequested) {
                        log("");
                        log("SURFACE TEST CANCELLED.");
                        log("No further HDD reads will be started.");
                        break;
                    }

                    int blocksThisRead =
                            (int) Math.min(
                                    blocksPerRead,
                                    totalBlocks - currentLba
                            );

                    int bytesThisRead =
                            blocksThisRead *
                                    (int) capacity.blockSize;

                    byte[] data =
                            performRead10(
                                    currentLba,
                                    blocksThisRead,
                                    bytesThisRead
                            );

                    if (data == null) {

                        if (botTransportFailed) {
                            log("");
                            log("USB CONNECTION LOST.");
                            log(botTransportFailure == null
                                    ? "Stopping the surface test because USB transfers failed."
                                    : botTransportFailure);
                            log("Reconnect the reader/device before trying again.");
                            break;
                        }

                        readErrors++;

                        if (firstErrorLba < 0) {
                            firstErrorLba = currentLba;
                        }
                        lastErrorLba = currentLba;

                        log("");
                        log("⚠ READ ERROR");
                        log("Location: " +
                                formatCapacity(
                                        currentLba *
                                                capacity.blockSize));
                        log("LBA: " + currentLba);
                        log("Retrying read...");

                        boolean recovered = false;

                        // First retry: same 4 MiB range.
                        byte[] retry =
                                performRead10(
                                        currentLba,
                                        blocksThisRead,
                                        bytesThisRead
                                );

                        if (retry != null) {
                            recovered = true;
                            recoveredErrors++;

                            log("✓ RETRY SUCCESSFUL");
                            log("Area is readable on retry.");
                            bytesRead += bytesThisRead;

                            surfaceLog.add(
                                    "RECOVERED @ " +
                                    formatCapacity(
                                            currentLba *
                                                    capacity.blockSize) +
                                    " - full-block retry successful"
                            );

                        } else {

                            if (botTransportFailed) {
                                log("USB connection failed during retry.");
                                log("Stopping the surface test.");
                                break;
                            }

                            log("⚠ RETRY FAILED");
                            log("Attempting non-destructive recovery...");
                            log("Splitting failed area into smaller reads.");

                            // Non-destructive recovery:
                            // progressively read smaller chunks.
                            // No ATA writes are issued.
                            int smallBlocks =
                                    Math.max(
                                            1,
                                            blocksThisRead / 8
                                    );

                            boolean allSmallReadsGood = true;

                            long subLba = currentLba;

                            while (subLba <
                                    currentLba + blocksThisRead) {

                                if (surfaceTestCancelRequested || usbScanCancelRequested || smartReadCancelRequested) {
                                    allSmallReadsGood = false;
                                    break;
                                }

                                int remaining =
                                        (int) (
                                                currentLba +
                                                blocksThisRead -
                                                subLba
                                        );

                                int count =
                                        Math.min(
                                                smallBlocks,
                                                remaining
                                        );

                                byte[] small =
                                        performRead10(
                                                subLba,
                                                count,
                                                count * (int)
                                                        capacity.blockSize
                                        );

                                if (small == null) {
                                    allSmallReadsGood = false;
                                    if (botTransportFailed) {
                                        log("USB connection failed during recovery.");
                                    }
                                    break;
                                }

                                bytesRead += (long) count * capacity.blockSize;
                                subLba += count;
                            }

                            if (allSmallReadsGood) {

                                recovered = true;
                                recoveredErrors++;

                                log("✓ NON-DESTRUCTIVE RECOVERY SUCCESSFUL");
                                log("Smaller reads succeeded.");

                                surfaceLog.add(
                                        "RECOVERED @ " +
                                        formatCapacity(
                                                currentLba *
                                                        capacity.blockSize) +
                                        " - smaller reads successful"
                                );

                            } else {

                                if (botTransportFailed) {
                                    log("USB connection lost during recovery.");
                                    log("Stopping the surface test instead of continuing with stale USB state.");
                                    break;
                                }

                                unrecoverableErrors++;

                                log("✗ NON-DESTRUCTIVE RECOVERY FAILED");
                                log("Area will be logged and skipped.");

                                surfaceLog.add(
                                        "UNRECOVERABLE @ " +
                                        formatCapacity(
                                                currentLba *
                                                        capacity.blockSize) +
                                        " - read failed after retry/recovery"
                                );
                            }
                        }

                        // Always continue to the next area unless the user
                        // cancelled the test.
                        currentLba += blocksThisRead;

                    } else {

                        currentLba += blocksThisRead;
                        bytesRead += bytesThisRead;
                    }

                    long now =
                            System.currentTimeMillis();

                    if (now - lastProgressTime >= 500 ||
                            currentLba >= totalBlocks) {
                        long scannedBytes = currentLba * capacity.blockSize;
                        long rateElapsedMs = now - lastRateSampleTime;
                        if (rateElapsedMs > 0 && scannedBytes > lastRateSampleBytes) {
                            double measuredRate =
                                    (scannedBytes - lastRateSampleBytes) * 1000.0
                                            / rateElapsedMs;
                            scanBytesPerSecond = scanBytesPerSecond == 0
                                    ? measuredRate
                                    : scanBytesPerSecond * 0.65 + measuredRate * 0.35;
                            lastRateSampleTime = now;
                            lastRateSampleBytes = scannedBytes;
                        }
                        updateSurfaceProgress(
                                scannedBytes,
                                totalBytes,
                                bytesRead,
                                scanBytesPerSecond);
                        lastProgressTime = now;
                    }
                }

                long elapsedMs = System.currentTimeMillis() - scanStartedAt;
                if (currentLba >= totalBlocks && elapsedMs >= 10000L) {
                    long measuredRate = (long) (currentLba
                            * capacity.blockSize * 1000.0 / elapsedMs);
                    if (measuredRate > 0) {
                        getSharedPreferences("smart_hdd_settings", MODE_PRIVATE)
                                .edit().putLong(surfaceRatePreferenceKey(inquiry),
                                        measuredRate).apply();
                    }
                }

                updateSurfaceProgress(
                        currentLba * capacity.blockSize,
                        totalBytes,
                        bytesRead,
                        scanBytesPerSecond);

                log("");
                log("════════════════════════════");
                log("       SURFACE TEST RESULT");
                log("════════════════════════════");
                log("");

                log("Data read: " +
                        formatCapacity(bytesRead));

                log("Initial read errors: " +
                        readErrors);

                log("Recovered areas: " +
                        recoveredErrors);

                log("Unrecoverable areas: " +
                        unrecoverableErrors);

                if (firstErrorLba >= 0) {
                    log("");
                    log("First error: " +
                            formatCapacity(
                                    firstErrorLba *
                                            capacity.blockSize));

                    log("Last error: " +
                            formatCapacity(
                                    lastErrorLba *
                                            capacity.blockSize));
                }

                if (surfaceTestCancelRequested) {

                    log("");
                    log("STATUS: CANCELLED");
                    log("The test was stopped by the user.");

                } else if (unrecoverableErrors == 0 &&
                        currentLba >= totalBlocks) {

                    log("");
                    log("STATUS: PASS");
                    log("All areas were read successfully.");

                    if (recoveredErrors > 0) {
                        log("Some areas required non-destructive retries.");
                    }

                } else {

                    log("");
                    log("STATUS: WARNING");
                    log("One or more areas remained unreadable.");
                }

                if (!surfaceLog.isEmpty()) {

                    log("");
                    log("────────────────────────");
                    log("ERROR / RECOVERY LOG");
                    log("────────────────────────");

                    for (String entry : surfaceLog) {
                        log(entry);
                    }
                }

                log("");
                log("NOTE: A PASS does not prove");
                log("mechanical health. SMART and");
                log("surface testing measure different");
                log("things.");

            } catch (Exception e) {

                log("");
                log("SURFACE TEST ERROR: " +
                        e.getMessage());

            } finally {

                closeUsb();
                surfaceTestRunning = false;
                surfaceTestCancelRequested = false;
                usbScanCancelRequested = false;
                smartReadCancelRequested = false;
                setKeepScreenAwake(false);
                runPendingUsbAction();

                runOnUiThread(() -> {
                    if (surfaceCancelButton != null) {
                        surfaceCancelButton.setVisibility(View.GONE);
                    }
                    if (surfaceProgressText != null &&
                            surfaceProgressText.getText().toString().startsWith("Starting")) {
                        surfaceProgressText.setText("Surface test did not start.");
                    }
                });
            }

        }).start();
    }
    private byte[] performRead10(
            long lba,
            int blockCount,
            int transferLength) {

        byte[] cdb = new byte[10];

        cdb[0] = 0x28; // READ(10)

        cdb[2] = (byte) ((lba >> 24) & 0xFF);
        cdb[3] = (byte) ((lba >> 16) & 0xFF);
        cdb[4] = (byte) ((lba >> 8) & 0xFF);
        cdb[5] = (byte) (lba & 0xFF);

        cdb[7] = (byte) ((blockCount >> 8) & 0xFF);
        cdb[8] = (byte) (blockCount & 0xFF);

        return performBotCommand(
                cdb,
                transferLength,
                true
        );
    }
    private void disconnectUsbSafely() {
        if (askBeforeInterruptingUsb(this::disconnectUsbSafely)) return;
        new android.app.AlertDialog.Builder(this)
                .setTitle("Safely disconnect USB?")
                .setMessage("Smart HDD will release its USB connection. After that, use your device’s system controls to eject or unmount the storage before physically unplugging the card reader or drive.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("RELEASE USB", (dialog, which) -> {
                    closeUsb();
                    usbDevice = null;
                    new android.app.AlertDialog.Builder(this)
                            .setTitle("USB connection released")
                            .setMessage("Smart HDD has closed its USB connection. Your device controls whether the storage volume is mounted, so eject or unmount it using the system controls before unplugging.")
                            .setNegativeButton("DONE", null)
                            .setPositiveButton("OPEN STORAGE SETTINGS", (d, w) -> openStorageSettings())
                            .show();
                })
                .show();
    }

    private void openStorageSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MEMORY_CARD_SETTINGS));
        } catch (Exception ignored) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception e) {
                new android.app.AlertDialog.Builder(this)
                        .setMessage("Open your device’s Settings and look for Storage or USB options to eject or unmount the drive before unplugging.")
                        .setPositiveButton("OK", null)
                        .show();
            }
        }
    }
    private void scanUsb() {
        if (askBeforeInterruptingUsb(this::scanUsb)) return;
        usbScanRunning = true;
        clearOutput();
        log("Scanning USB...");
        log("");
        new Thread(() -> {
            try {
                scanUsbInBackground();
            } catch (Exception e) {
                log("USB scan error: " + e.getMessage());
            } finally {
                usbScanRunning = false;
                usbScanCancelRequested = false;
                runPendingUsbAction();
            }
        }, "usb-scan").start();
    }

    private void scanUsbInBackground() {
        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
        if (devices.isEmpty()) {
            log("No USB devices found.");
            return;
        }
        for (UsbDevice device : devices.values()) {
            log("USB DEVICE");
            log("----------------------------");
            log("VID: 0x" + Integer.toHexString(device.getVendorId()));
            log("PID: 0x" + Integer.toHexString(device.getProductId()));
            log("Manufacturer: " + safe(device.getManufacturerName()));
            log("Product: " + safe(device.getProductName()));
            log("Serial: " + safeUsbSerial(device));
            log("Interfaces: " + device.getInterfaceCount());
            boolean massStorage = false;
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface intf = device.getInterface(i);
                log("  Interface " + i +
                        " class=" + intf.getInterfaceClass() +
                        " subclass=" + intf.getInterfaceSubclass() +
                        " protocol=" + intf.getInterfaceProtocol());
                if (intf.getInterfaceClass() == 8) massStorage = true;
            }
            log("");
            if (massStorage) {
                log("USB MASS STORAGE DEVICE FOUND.");
                usbDevice = device;
                if (!usbManager.hasPermission(device)) {
                    requestUsbPermission(device);
                } else {
                    log("USB permission already granted.");
                    log("Ready for SMART.");
                }
                log("");
            }
        }
    }

    private void requestUsbPermission(UsbDevice device) {
        runOnUiThread(() -> {
            try {
                if (!usbPermissionReceiverRegistered) {
                    IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
                    registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
                    usbPermissionReceiverRegistered = true;
                }
                PendingIntent permissionIntent = PendingIntent.getBroadcast(
                        this,
                        0,
                        new Intent(ACTION_USB_PERMISSION),
                        PendingIntent.FLAG_IMMUTABLE);
                usbManager.requestPermission(device, permissionIntent);
                log("USB permission requested.");
            } catch (Exception e) {
                log("Could not request USB permission: " + e.getMessage());
            }
        });
    }

    private void showMediaDiagnostics() {
        showingHome = false;
        surfaceInfoScreenVisible = false;
        surfaceProgressBar = null;
        surfaceProgressText = null;
        surfaceCancelButton = null;
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        setResponsivePadding(layout, 20, 20, 20, 20);

        Button backButton = new Button(this);
        backButton.setText("BACK");
        TextView title = new TextView(this);
        title.setText("SD CARD & INTERNAL STORAGE");
        setResponsiveTextSize(title, 22f);
        title.setTextColor(Color.BLACK);
        setResponsivePadding(title, 0, 12, 0, 12);

        Button scanButton = new Button(this);
        scanButton.setText("SCAN SD CARD & NAND");
        TextView results = new TextView(this);
        setResponsiveTextSize(results, 15f);
        results.setTextColor(Color.BLACK);
        setResponsivePadding(results, 0, 12, 0, 0);
        layout.addView(backButton);
        layout.addView(title);
        addAccentRule(layout);
        layout.addView(scanButton);
        layout.addView(results);
        setScrollablePage(layout);

        backButton.setOnClickListener(v -> showHome());
        scanButton.setOnClickListener(v -> scanMediaStorage(results));
        scanMediaStorage(results);
    }

    private void scanMediaStorage(TextView results) {
        StringBuilder report = new StringBuilder();
        report.append("READ-ONLY STORAGE CHECK\n\n");

        File dataDirectory = Environment.getDataDirectory();
        report.append("INTERNAL STORAGE (usually NAND/eMMC)\n");
        report.append("Location: ").append(dataDirectory.getAbsolutePath()).append("\n");
        appendCapacity(report, dataDirectory);
        report.append("\nInternal flash health:\n");
        boolean foundHealth = appendFlashHealth(report);
        if (!foundHealth) {
            report.append("The device does not expose eMMC life-time or pre-EOL health data to this app.\n");
            report.append("This means health is unavailable, not that the flash is healthy.\n");
        }

        report.append("\nSD CARD\n");
        boolean foundSd = false;
        try {
            StorageManager storageManager = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
            if (storageManager != null) {
                for (StorageVolume volume : storageManager.getStorageVolumes()) {
                    if (!volume.isRemovable()) continue;
                    foundSd = true;
                    report.append(volume.getDescription(this)).append("\n");
                    report.append("State: ").append(volume.getState()).append("\n");
                    File directory = null;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        directory = volume.getDirectory();
                    }
                    if (directory == null) {
                        report.append("Capacity unavailable through this Android version.\n");
                    } else if (Environment.MEDIA_MOUNTED.equals(volume.getState()) ||
                            Environment.MEDIA_MOUNTED_READ_ONLY.equals(volume.getState())) {
                        appendCapacity(report, directory);
                    } else {
                        report.append("Card is not mounted; capacity cannot be read.\n");
                    }
                    report.append("\n");
                }
            }
        } catch (SecurityException e) {
            report.append("Android denied access to removable storage details.\n");
        } catch (Exception e) {
            report.append("Could not read SD card details: ").append(e.getMessage()).append("\n");
        }
        if (!foundSd) {
            report.append("No removable SD card volume is currently visible to Android.\n");
        }

        report.append("\nHealth data depends on what Android exposes. This check does not write to storage.");
        results.setText(report.toString());
    }

    private void appendCapacity(StringBuilder report, File path) {
        try {
            StatFs stats = new StatFs(path.getAbsolutePath());
            long totalBytes = stats.getTotalBytes();
            long availableBytes = stats.getAvailableBytes();
            report.append("Total capacity: ").append(formatCapacity(totalBytes)).append("\n");
            report.append("Available: ").append(formatCapacity(availableBytes)).append("\n");
        } catch (Exception e) {
            report.append("Capacity unavailable: ").append(e.getMessage()).append("\n");
        }
    }

    private boolean appendFlashHealth(StringBuilder report) {
        ArrayList<File> candidates = new ArrayList<>();
        File mmcHost = new File("/sys/class/mmc_host");
        File[] hosts = mmcHost.listFiles();
        if (hosts != null) {
            for (File host : hosts) {
                File[] cards = host.listFiles();
                if (cards != null) {
                    for (File card : cards) {
                        candidates.add(new File(card, "life_time"));
                        candidates.add(new File(card, "pre_eol_info"));
                        candidates.add(new File(new File(card, "device"), "life_time"));
                        candidates.add(new File(new File(card, "device"), "pre_eol_info"));
                    }
                }
            }
        }
        candidates.add(new File("/sys/class/block/mmcblk0/device/life_time"));
        candidates.add(new File("/sys/class/block/mmcblk0/device/pre_eol_info"));

        boolean found = false;
        Set<String> printed = new HashSet<>();
        for (File candidate : candidates) {
            if (!candidate.isFile() || !printed.add(candidate.getAbsolutePath())) continue;
            String value = readSmallTextFile(candidate);
            if (value == null || value.isEmpty()) continue;
            found = true;
            String name = candidate.getName().equals("life_time") ? "eMMC life-time estimate" : "eMMC pre-EOL status";
            report.append(name).append(" (raw): ").append(value).append("\n");
            if (candidate.getName().equals("life_time")) {
                report.append("Each life-time value is a vendor-reported wear estimate; consult the device maker for interpretation.\n");
            } else {
                report.append("Pre-EOL codes are device-reported warning levels; consult the device maker for interpretation.\n");
            }
        }
        return found;
    }

    private String readSmallTextFile(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            return reader.readLine();
        } catch (Exception ignored) {
            return null;
        }
    }
    private String safeUsbSerial(UsbDevice device) {
        try {
            return safe(device.getSerialNumber());
        } catch (SecurityException ignored) {
            return "(grant USB permission to read)";
        }
    }

    private void setKeepScreenAwake(boolean awake) {
        keepScreenAwake = awake;
        runOnUiThread(() -> {
            if (awake) {
                getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });
    }
    private String safe(String value) {
        return value == null ? "(unknown)" : value;
    }
    private void startSmart() {
        if (askBeforeInterruptingUsb(this::startSmart)) return;
        smartReadRunning = true;
        smartReadCancelRequested = false;
        clearOutput();
        new Thread(() -> {
            try {
                log("Checking USB storage...");
                usbDevice = findMassStorageDevice();
                if (usbDevice == null) {
                    log("No USB Mass Storage device found.");
                    log("Connect a USB-SATA/IDE storage adapter.");
                    return;
                }
                if (!usbManager.hasPermission(usbDevice)) {
                    log("USB permission is not available. Press SCAN USB and allow access.");
                    return;
                }
                if (!openBot()) return;
                setKeepScreenAwake(true);
                log("USB Mass Storage BOT ready.");
                log("");
                InquiryResult inquiry = performInquiry();
                if (inquiry == null) {
                    log("SCSI INQUIRY FAILED.");
                    return;
                }
                if (smartReadCancelRequested) return;
                log("SCSI INQUIRY SUCCESS.");
                log("");
                log("Vendor:  " + inquiry.vendor);
                log("Product: " + inquiry.product);
                log("Revision: " + inquiry.revision);
                log("");
                CapacityResult capacity = performReadCapacity();
                if (smartReadCancelRequested) return;
                displayDriveInformation(inquiry, capacity);
                log("");
                log("Attempting SAT SMART READ DATA...");
                log("");
                byte[] smart = performSmartRead();
                if (smartReadCancelRequested) {
                    log("SMART read cancelled after the current USB command.");
                    return;
                }
                if (smart == null) {
                    log("");
                    log("SMART DATA UNAVAILABLE.");
                    log("");
                    log("The USB adapter may not support");
                    log("ATA/SAT SMART commands.");
                    return;
                }
                SmartResult result = parseSmart(smart);
                displayHealth(result);
                log("");
                log("────────────────────────");
                log("RAW SMART DATA");
                log("────────────────────────");
                log("");
                dumpRawSmart(result);
                log("");
                log("SMART command completed.");
            } catch (Exception e) {
                log("");
                log("ERROR: " + e.getMessage());
            } finally {
                closeUsb();
                smartReadRunning = false;
                smartReadCancelRequested = false;
                usbScanCancelRequested = false;
                setKeepScreenAwake(false);
                runPendingUsbAction();
            }
        }, "smart-read").start();
    }
    private UsbDevice findMassStorageDevice() {

        HashMap<String, UsbDevice> devices =
                usbManager.getDeviceList();

        // First preference: Mass Storage interface.

        for (UsbDevice device : devices.values()) {

            for (int i = 0;
                 i < device.getInterfaceCount();
                 i++) {

                UsbInterface intf =
                        device.getInterface(i);

                if (intf.getInterfaceClass() == 8) {

                    return device;
                }
            }
        }

        return null;
    }
    private boolean openBot() {

        connection =
                usbManager.openDevice(usbDevice);

        if (connection == null) {

            log("Unable to open USB device.");
            return false;
        }

        botInterface = null;

        // Prefer BOT:
        //
        // Class    08h = Mass Storage
        // Subclass 06h = SCSI transparent
        // Protocol 50h = Bulk Only Transport

        for (int i = 0;
             i < usbDevice.getInterfaceCount();
             i++) {

            UsbInterface intf =
                    usbDevice.getInterface(i);

            if (intf.getInterfaceClass() == 8 &&
                    intf.getInterfaceSubclass() == 6 &&
                    intf.getInterfaceProtocol() == 0x50) {

                botInterface = intf;
                break;
            }
        }

        if (botInterface == null) {

            log("BOT interface not found.");
            closeUsb();
            return false;
        }

        bulkIn = null;
        bulkOut = null;

        for (int i = 0;
             i < botInterface.getEndpointCount();
             i++) {

            UsbEndpoint ep =
                    botInterface.getEndpoint(i);

            if (ep.getType() ==
                    UsbConstants.USB_ENDPOINT_XFER_BULK) {

                if (ep.getDirection() ==
                        UsbConstants.USB_DIR_IN) {

                    bulkIn = ep;

                } else {

                    bulkOut = ep;
                }
            }
        }

        if (bulkIn == null ||
                bulkOut == null) {

            log("Bulk endpoints not found.");
            closeUsb();
            return false;
        }

        if (!connection.claimInterface(
                botInterface,
                true)) {

            log("Could not claim BOT interface.");
            closeUsb();
            return false;
        }

        log("BOT interface claimed.");

        log("Bulk OUT: EP" +
                bulkOut.getEndpointNumber());

        log("Bulk IN: EP" +
                bulkIn.getEndpointNumber());

        return true;
    }
    private InquiryResult performInquiry() {

        byte[] cdb = new byte[16];

        cdb[0] = 0x12;       // INQUIRY
        cdb[4] = 36;         // allocation length

        byte[] data =
                performBotCommand(
                        cdb,
                        36,
                        true
                );

        if (data == null ||
                data.length < 36) {

            return null;
        }

        InquiryResult result =
                new InquiryResult();

        result.vendor =
                ascii(data, 8, 8);

        result.product =
                ascii(data, 16, 16);

        result.revision =
                ascii(data, 32, 4);

        result.removable = (data[1] & 0x80) != 0;

        return result;
    }
    private CapacityResult performReadCapacity() {

        // READ CAPACITY (10)
        byte[] cdb = new byte[10];
        cdb[0] = 0x25;

        byte[] data = performBotCommand(cdb, 8, true);

        if (data != null && data.length >= 8) {

            long lastLba =
                    ((long)(data[0] & 0xFF) << 24) |
                    ((long)(data[1] & 0xFF) << 16) |
                    ((long)(data[2] & 0xFF) << 8) |
                    ((long)(data[3] & 0xFF));

            long blockSize =
                    ((long)(data[4] & 0xFF) << 24) |
                    ((long)(data[5] & 0xFF) << 16) |
                    ((long)(data[6] & 0xFF) << 8) |
                    ((long)(data[7] & 0xFF));

            // 0xFFFFFFFF means READ CAPACITY (16) is required.
            if (lastLba != 0xFFFFFFFFL && blockSize > 0) {
                CapacityResult result = new CapacityResult();
                result.lastLba = lastLba;
                result.blockSize = blockSize;
                result.capacityBytes = (lastLba + 1L) * blockSize;
                return result;
            }
        }

        // READ CAPACITY (16) fallback.
        byte[] cdb16 = new byte[16];
        cdb16[0] = (byte) 0x9E;
        cdb16[1] = 0x10;

        // Allocation length = 32 bytes, big-endian.
        cdb16[13] = 0x20;

        byte[] data16 = performBotCommand(cdb16, 32, true);

        if (data16 == null || data16.length < 12) {
            return null;
        }

        long lastLba = 0;

        for (int i = 0; i < 8; i++) {
            lastLba = (lastLba << 8) | (data16[i] & 0xFFL);
        }

        long blockSize =
                ((long)(data16[8] & 0xFF) << 24) |
                ((long)(data16[9] & 0xFF) << 16) |
                ((long)(data16[10] & 0xFF) << 8) |
                ((long)(data16[11] & 0xFF));

        if (blockSize <= 0) {
            return null;
        }

        CapacityResult result = new CapacityResult();
        result.lastLba = lastLba;
        result.blockSize = blockSize;
        result.capacityBytes = (lastLba + 1L) * blockSize;

        return result;
    }
    private void displayDriveInformation(
            InquiryResult inquiry,
            CapacityResult capacity) {

        log("");
        log("════════════════════════════");
        log("       DRIVE INFORMATION");
        log("════════════════════════════");
        log("");

        log("Model             " + safe(inquiry.product));

        if (capacity != null) {
            log("Capacity          " +
                    formatCapacity(capacity.capacityBytes));
        } else {
            log("Capacity          UNAVAILABLE");
        }

        log("Vendor            " + safe(inquiry.vendor));
        log("Firmware          " + safe(inquiry.revision));

        log("");
        log("════════════════════════════");
    }
    private String formatCapacity(long bytes) {

        final double GB = 1000.0 * 1000.0 * 1000.0;
        final double TB = GB * 1000.0;

        if (bytes >= TB) {
            return String.format(
                    Locale.US,
                    "%.2f TB (%d bytes)",
                    bytes / TB,
                    bytes
            );
        }

        return String.format(
                Locale.US,
                "%.1f GB (%d bytes)",
                bytes / GB,
                bytes
        );
    }
    private byte[] performSmartRead() {

        byte[] cdb = new byte[16];

        // ATA PASS-THROUGH (16)
        cdb[0] = (byte) 0x85;

        // Protocol = PIO Data-In
        cdb[1] = (byte) 0x08;

        // T_DIR = 1
        // BYTE_BLOCK = 1
        // T_LENGTH = 2
        cdb[2] = (byte) 0x0E;

        // FEATURES = D0h
        cdb[3] = 0x00;
        cdb[4] = (byte) 0xD0;

        // SECTOR COUNT = 1
        cdb[5] = 0x00;
        cdb[6] = 0x01;

        // LBA LOW
        cdb[7] = 0x00;
        cdb[8] = 0x00;

        // LBA MID = 4Fh
        cdb[9] = 0x00;
        cdb[10] = 0x4F;

        // LBA HIGH = C2h
        cdb[11] = 0x00;
        cdb[12] = (byte) 0xC2;

        // DEVICE
        cdb[13] = 0x00;

        // ATA COMMAND = SMART
        cdb[14] = (byte) 0xB0;

        // CONTROL
        cdb[15] = 0x00;

        return performBotCommand(
                cdb,
                512,
                true
        );
    }
    private byte[] performBotCommand(
            byte[] cdb,
            int transferLength,
            boolean dataIn) {

        int tag =
                commandTag++;

        byte[] cbw =
                new byte[31];

        ByteBuffer bb =
                ByteBuffer.wrap(cbw)
                        .order(ByteOrder.LITTLE_ENDIAN);

        // CBW signature = USBC
        bb.putInt(0x43425355);

        bb.putInt(tag);

        bb.putInt(transferLength);

        bb.put(
                (byte)
                (dataIn ? 0x80 : 0x00)
        );

        bb.put((byte) 0x00); // LUN

        bb.put((byte) cdb.length);

        bb.put(cdb);

        int sent =
                connection.bulkTransfer(
                        bulkOut,
                        cbw,
                        cbw.length,
                        5000
                );

        if (sent != 31) {

            botTransportFailed = true;
            botTransportFailure = "CBW transfer failed (" + sent + ").";

            log("CBW transfer failed: " +
                    sent);

            return null;
        }

        byte[] data =
                new byte[transferLength];

        int total = 0;

        while (total < transferLength) {

            int remaining =
                    transferLength - total;
            int chunkLength =
                    Math.min(remaining, MAX_BULK_DATA_CHUNK_BYTES);

            int received =
                    connection.bulkTransfer(
                            bulkIn,
                            data,
                            total,
                            chunkLength,
                            5000
                    );

            if (received <= 0) {

                botTransportFailed = true;
                botTransportFailure = "USB data transfer failed (" + received + ").";

                log("USB data transfer failed.");

                return null;
            }

            total += received;
        }

        // Read CSW

        byte[] csw =
                new byte[13];

        int cswReceived =
                connection.bulkTransfer(
                        bulkIn,
                        csw,
                        csw.length,
                        5000
                );

        if (cswReceived != 13) {

            botTransportFailed = true;
            botTransportFailure = "CSW transfer failed (" + cswReceived + ").";

            log("CSW transfer failed.");

            return null;
        }

        ByteBuffer cswBuffer =
                ByteBuffer.wrap(csw)
                        .order(ByteOrder.LITTLE_ENDIAN);

        int signature =
                cswBuffer.getInt();

        int returnedTag =
                cswBuffer.getInt();

        int residue =
                cswBuffer.getInt();

        int status =
                cswBuffer.get() & 0xFF;

        if (signature != 0x53425355) {

            botTransportFailed = true;
            botTransportFailure = "USB status block had an invalid signature.";
            log("Invalid BOT CSW signature.");

            return null;
        }

        if (returnedTag != tag) {

            botTransportFailed = true;
            botTransportFailure = "USB status block did not match the command.";
            log("BOT tag mismatch.");

            return null;
        }

        if (status != 0) {

            log("BOT command failed.");
            log("Status: " + status);
            log("Residue: " + residue);

            return null;
        }

        return data;
    }
    private SmartResult parseSmart(
            byte[] data) {

        SmartResult result =
                new SmartResult();

        result.version =
                u16(data, 0);

        int checksum = 0;

        for (byte b : data) {

            checksum +=
                    b & 0xFF;
        }

        result.checksumValid =
                (checksum & 0xFF) == 0;

        for (int offset = 2;
             offset + 12 <= 362;
             offset += 12) {

            int id =
                    data[offset] & 0xFF;

            if (id == 0) {
                continue;
            }

            int current =
                    data[offset + 3] & 0xFF;

            int worst =
                    data[offset + 4] & 0xFF;

            long raw = 0;

            for (int i = 0; i < 6; i++) {

                raw |=
                        ((long)
                                data[offset + 5 + i]
                                & 0xFF)
                                << (8 * i);
            }

            SmartAttribute attr =
                    new SmartAttribute();

            attr.id = id;
            attr.current = current;
            attr.worst = worst;
            attr.raw = raw;

            result.attributes.add(attr);

            switch (id) {

                case 5:
                    result.reallocated = raw;
                    break;

                case 9:
                    result.powerOnHours = raw;
                    break;

                case 12:
                    result.powerCycles = raw;
                    break;

                case 194:
                    result.temperature =
                            data[offset + 5]
                                    & 0xFF;
                    break;

                case 197:
                    result.pending = raw;
                    break;

                case 198:
                    result.uncorrectable = raw;
                    break;

                case 199:
                    result.crcErrors = raw;
                    break;
            }
        }

        if (!result.checksumValid) {

            result.health =
                    "BAD";

        } else if (
                result.pending > 0 ||
                result.uncorrectable > 0) {

            result.health =
                    "BAD";

        } else if (
                result.reallocated > 0 ||
                result.crcErrors > 0) {

            result.health =
                    "GOOD";

        } else {

            result.health =
                    "EXCELLENT";
        }

        return result;
    }
    private void displayHealth(
            SmartResult r) {

        log("");
        log("════════════════════════════");
        log("        DRIVE HEALTH");
        log("════════════════════════════");
        log("");

        log("HEALTH: " +
                r.health);

        log("");

        log("Temperature       " +
                r.temperature +
                " °C");

        log("Power-on time     " +
                formatHours(
                        r.powerOnHours));

        log("Power cycles      " +
                r.powerCycles);

        log("");

        log("Reallocated       " +
                r.reallocated);

        log("Pending sectors   " +
                r.pending);

        log("Uncorrectable     " +
                r.uncorrectable);

        log("CRC errors        " +
                r.crcErrors);

        log("");

        log("SMART checksum    " +
                (r.checksumValid
                        ? "VALID"
                        : "INVALID"));

        log("");

        log("════════════════════════════");
    }
    private String formatHours(
            long hours) {

        long days =
                hours / 24;

        long remaining =
                hours % 24;

        if (days > 0) {

            return hours +
                    " hours (" +
                    days +
                    " days, " +
                    remaining +
                    " hours)";
        }

        return hours +
                " hours";
    }
    private void dumpRawSmart(
            SmartResult result) {

        log(String.format(
                "SMART VERSION: 0x%04X",
                result.version
        ));

        log("");

        for (SmartAttribute a :
                result.attributes) {

            log(String.format(
                    "ID %d  Current=%d Worst=%d Raw=%d",
                    a.id,
                    a.current,
                    a.worst,
                    a.raw
            ));
        }

        log("");

        log("SMART checksum: " +
                (result.checksumValid
                        ? "VALID"
                        : "INVALID"));
    }
    private String ascii(
            byte[] data,
            int offset,
            int length) {

        StringBuilder sb =
                new StringBuilder();

        for (int i = 0;
             i < length;
             i++) {

            int c =
                    data[offset + i]
                            & 0xFF;

            if (c >= 32 &&
                    c <= 126) {

                sb.append((char)c);
            }
        }

        return sb.toString().trim();
    }
    private int u16(
            byte[] data,
            int offset) {

        return
                (data[offset] & 0xFF)
                |
                ((data[offset + 1]
                        & 0xFF) << 8);
    }
    private void closeUsb() {

        if (connection != null) {

            try {

                if (botInterface != null) {

                    connection.releaseInterface(
                            botInterface);
                }

            } catch (Exception ignored) {}

            connection.close();

            connection = null;
        }

        botInterface = null;
        bulkIn = null;
        bulkOut = null;
    }

    private static class InquiryResult {
        String vendor;
        String product;
        String revision;
        boolean removable;
    }

    private static class FakeCardTarget {
        String vendor;
        String product;
        String revision;
        long lastLba;
        long blockSize;
        long capacityBytes;
        String usbDeviceName;
        int usbVendorId;
        int usbProductId;
    }

    private static class TestOutcome {
        final String message;
        final boolean success;
        TestOutcome(String message, boolean success) {
            this.message = message;
            this.success = success;
        }
    }

    private static class CapacityResult {
        long lastLba;
        long blockSize;
        long capacityBytes;
    }

    private static class SmartResult {
        int version;
        boolean checksumValid;
        long reallocated;
        long powerOnHours;
        long powerCycles;
        long temperature;
        long pending;
        long uncorrectable;
        long crcErrors;
        String health;
        ArrayList<SmartAttribute> attributes = new ArrayList<>();
    }

    private static class SmartAttribute {
        int id;
        int current;
        int worst;
        long raw;
    }
}
